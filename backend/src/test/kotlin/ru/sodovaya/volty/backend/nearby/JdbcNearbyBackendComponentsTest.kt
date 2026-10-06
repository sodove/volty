package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcNearbyBackendComponentsTest {
    private fun id()=UUID.randomUUID().toString()
    private val owner=id();private val viewer=id();private val outsider=id()
    private val schema="assembled_"+id().replace("-","")
    private val source=NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private var at=100L;private var tokens=0
    private val sfu=RecordingSfu()
    private val components=NearbyBackendComponents.create(source,sfu,SessionCredentialIssuer {row ->
        tokens++;SessionCredentials("wss://media.test","opaque",row.operation.sessionId!!,row.sfuEpoch!!.roomEpoch,row.operation.deadlineEpochMillis!!)
    },MediaWebhookVerifier("test-key","test-secret"),{at})
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for(actor in listOf(owner,viewer,outsider)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
        }}
    }
    private fun community()=assertIs<CommunityWriteResult.Accepted>(components.communities.create(owner,id(),"Riders","",CommunityVisibility.PUBLIC)).snapshot
    private fun join(actor: String,channel: String)=assertIs<SessionAction.Ready>(components.sessions.join(actor,id(),channel)).credentials.sessionId

    @Test fun assembledMemberDirectoryRequiresCurrentOwnerAndNeverAllocatesVoice() {
        val group=community()
        assertIs<CommunityWriteResult.Accepted>(components.communities.join(viewer,group.community.id))
        val page=assertIs<CommunityMembersResult.Listed>(components.members.list(owner,group.community.id,null,20))
        assertEquals(group.community.id,page.communityId);assertEquals(owner,page.ownerId)
        assertEquals(setOf(owner,viewer),page.items.map {it.userId}.toSet())
        assertEquals(CommunityMembersResult.Denied,components.members.list(viewer,group.community.id,null,20))
        assertEquals(CommunityMembersResult.Denied,components.members.list(outsider,group.community.id,null,20))
        assertTrue(sfu.calls.isEmpty());assertEquals(0,tokens)
    }
    @Test fun assembledChannelSharingDisconnectKeepsMembershipAndConsistentRoster() {
        val group=community();val channel=group.channels.single().id
        assertIs<CommunityWriteResult.Accepted>(components.communities.join(viewer,group.community.id))
        val original=join(owner,channel);val observing=join(viewer,channel);val generation=id()
        assertEquals(2,assertNotNull(components.roster.snapshot(owner,channel)).peers.size)
        assertNotNull(components.sharing.start(owner,original,generation,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        val packet=ChannelSharingPacket(0,at,LocationDto(0.0,0.0,5.0,at,at+15_000))
        assertNotNull(components.packets.publish(owner,original,generation,packet))
        assertEquals(packet,assertNotNull(components.packets.audience(viewer,observing)).peers.single().packet)
        assertIs<SessionAction.Ended>(components.sessions.disconnect(owner,original))
        assertNull(components.sharing.status(owner,original,generation))
        assertTrue(assertNotNull(components.packets.audience(viewer,observing)).peers.isEmpty())
        assertEquals(1,assertNotNull(components.roster.snapshot(viewer,channel)).peers.size)
        assertNotNull(components.communities.detail(owner,group.community.id))
        assertNotNull(components.communities.detail(viewer,group.community.id))
        assertEquals(2,tokens);assertTrue("revoke:$original" in sfu.calls)
    }

    @Test fun assembledMembershipCannotStandInForVoiceAndForeignActorNeverReachesSfu() {
        val group=community();val channel=group.channels.single().id
        assertIs<CommunityWriteResult.Accepted>(components.communities.join(viewer,group.community.id))
        assertNull(components.sharing.start(viewer,id(),id(),ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        assertNull(components.packets.audience(viewer,id()))
        assertIs<SessionAction.Denied>(components.sessions.join(outsider,id(),channel))
        assertNull(components.roster.snapshot(outsider,channel))
        assertTrue(sfu.calls.isEmpty());assertEquals(0,tokens)
    }

    @Test fun assembledAcceptedFriendsCallUsesTypedRoomAndCannotPublishChannelSharing() {
        source.connection.use {c -> c.createStatement().use {it.execute("INSERT INTO friendships(id,requester_id,addressee_id,status,created_at) VALUES ('${id()}','$owner','$viewer','ACCEPTED',0)")}}
        val call=id();val operation=id();val calleeOperation=id()
        assertEquals(DirectCallPhase.RINGING,components.calls.create(owner,viewer,call,id(),operation).call?.phase)
        val accepted=components.calls.accept(viewer,call,calleeOperation,0)
        val callee=assertIs<SessionAction.Ready>(accepted.session).credentials.sessionId
        val caller=assertIs<SessionAction.Ready>(components.calls.resume(owner,call,operation).session).credentials.sessionId
        assertNotNull(components.calls.roster(owner,call));assertEquals(2,tokens)
        assertNull(components.sharing.start(owner,caller,id(),ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        assertNull(components.packets.audience(viewer,callee))
        assertEquals(DirectCallOutcome.CALLER_ENDED,components.calls.stop(owner,call).call?.outcome)
        assertEquals(setOf("revoke:$caller","revoke:$callee"),sfu.calls.filter {it.startsWith("revoke:")}.toSet())
        assertTrue(sfu.rooms.all {it=="volty-call-$call"})
    }

    @Test fun assembledBoundedReconciliationExpiresOriginalLeaseWithoutRemovingMembership() {
        val group=community();val channel=group.channels.single().id;val original=join(owner,channel)
        val generation=id();assertNotNull(components.sharing.start(owner,original,generation,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        at+=30_001
        assertTrue(components.reconcileSession())
        assertTrue(assertNotNull(components.roster.snapshot(owner,channel)).peers.isEmpty())
        assertNull(components.sharing.status(owner,original,generation))
        assertNotNull(components.communities.detail(owner,group.community.id))
        assertTrue("revoke:$original" in sfu.calls);assertEquals(1,tokens)
        assertFalse(components.reconcileSession());assertFalse(components.reconcileWebhook())
    }

    private inner class RecordingSfu : SfuLeaseClient,SfuConnectedLeaseClient {
        val calls=mutableListOf<String>();val rooms=mutableListOf<String>()
        override fun inspect(room: String): SfuReply<SfuNodeStatus> {rooms+=room;calls+="inspect";return SfuReply.Accepted(SfuNodeStatus("node",true,true,true))}
        override fun register(room: String,session: String,expectedNodeEpoch: String): SfuReply<LeaseReceipt> {rooms+=room;calls+="register:$session";return SfuReply.Accepted(LeaseReceipt(at+30_000))}
        override fun renew(room: String,session: String,expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("unobserved renewal")
        override fun revoke(room: String,session: String,expectedNodeEpoch: String): SfuReply<Unit> {rooms+=room;calls+="revoke:$session";return SfuReply.Accepted(Unit)}
        override fun observeParticipant(room: String,session: String,expectedNodeEpoch: String)=SfuReply.Accepted(SfuParticipantObservation("sid-$session",SfuParticipantState.ACTIVE))
        override fun renewConnected(room: String,session: String,expectedNodeEpoch: String,expectedParticipantSid: String)=SfuReply.Accepted(LeaseReceipt(at+30_000))
    }
}

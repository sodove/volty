package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*
import io.ktor.server.testing.testApplication
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*

@Tag("postgres")
class JdbcNearbyProductionBootstrapTest {
    private fun id()=UUID.randomUUID().toString()
    private val owner=id();private val friend=id();private val group=id();private val sharing=id();private val history=id()
    private val schema="activation_"+id().replace("-","")
    private val source=NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        sql("""
            INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES
            ('$owner','$owner@example.invalid','test','Owner',0),('$friend','$friend@example.invalid','test','Friend',0);
            INSERT INTO friendships(id,requester_id,addressee_id,status,created_at) VALUES ('${id()}','$owner','$friend','ACCEPTED',0);
            INSERT INTO groups(id,name,owner_id,invite_code,created_at) VALUES ('$group','Old group','$owner','old-invite',0);
            INSERT INTO group_members(group_id,user_id,role,joined_at) VALUES ('$group','$owner','OWNER',0);
            INSERT INTO sharing_sessions(id,user_id,group_id,profile,started_at,expires_at,revoked_at) VALUES
            ('$sharing','$owner','$group','LOCATION',0,9999999999999,NULL),('$history','$friend','$group','LOCATION',0,100,17);
            INSERT INTO live_updates(user_id,group_id,location_json,captured_at,last_seen_at) VALUES ('$owner','$group','sensitive-old-location',1,1);
        """.trimIndent())
    }
    private fun sql(value: String)=source.connection.use {c -> c.createStatement().use {it.execute(value)} }
    private fun count(query: String)=source.connection.use {c -> c.createStatement().use {s -> s.executeQuery(query).use {r -> check(r.next());r.getLong(1)} } }

    @Test fun activeBootstrapWithdrawsLegacySharingOnceAndPreservesFriendsHistoryAndNewConsent() {
        val sfu=object : SfuLeaseClient,SfuConnectedLeaseClient {
            override fun inspect(room: String)=SfuReply.Accepted(SfuNodeStatus("node",true,true,true))
            override fun register(room: String,session: String,expectedNodeEpoch: String)=SfuReply.Accepted(LeaseReceipt(30100))
            override fun renew(room: String,session: String,expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("no renewal")
            override fun revoke(room: String,session: String,expectedNodeEpoch: String)=SfuReply.Accepted(Unit)
            override fun observeParticipant(room: String,session: String,expectedNodeEpoch: String)=SfuReply.Accepted(SfuParticipantObservation("sid-$session",SfuParticipantState.ACTIVE))
            override fun renewConnected(room: String,session: String,expectedNodeEpoch: String,expectedParticipantSid: String)=SfuReply.Accepted(LeaseReceipt(30100))
        }
        val graph=NearbyBackendComponents.create(source,sfu,SessionCredentialIssuer {row ->
            SessionCredentials("wss://media.invalid","opaque",row.operation.sessionId!!,row.sfuEpoch!!.roomEpoch,row.operation.deadlineEpochMillis!!)
        },MediaWebhookVerifier("test","secret"),{100})
        val community=assertIs<CommunityWriteResult.Accepted>(graph.communities.create(owner,id(),"New server","",CommunityVisibility.PUBLIC)).snapshot
        val session=assertIs<SessionAction.Ready>(graph.sessions.join(owner,id(),community.channels.single().id)).credentials.sessionId
        val generation=id()
        val consent=assertNotNull(graph.sharing.start(owner,session,generation,ChannelSharingMode.LOCATION,ChannelSharingTtl.HALF_HOUR))
        prepareBackendSchema(source,NearbyCompatibility.VOICE_SPACES)
        val revoked=count("SELECT revoked_at FROM sharing_sessions WHERE id='$sharing'")
        assertTrue(revoked>0);assertEquals(0,count("SELECT count(*) FROM live_updates"))
        prepareBackendSchema(source,NearbyCompatibility.VOICE_SPACES)
        assertEquals(revoked,count("SELECT revoked_at FROM sharing_sessions WHERE id='$sharing'"))
        assertEquals(17,count("SELECT revoked_at FROM sharing_sessions WHERE id='$history'"))
        assertEquals(2,count("SELECT count(*) FROM users"));assertEquals(1,count("SELECT count(*) FROM friendships WHERE status='ACCEPTED'"))
        assertEquals(1,count("SELECT count(*) FROM groups"));assertEquals(1,count("SELECT count(*) FROM group_members"))
        assertEquals(1,count("SELECT count(*) FROM nearby_communities WHERE state='ACTIVE'"))
        assertEquals(consent,graph.sharing.status(owner,session,generation))
        assertNotNull(graph.communities.detail(owner,community.community.id))
    }
    @Test fun payloadCleanupFailureRollsBackConsentWithdrawalAndPreventsBootstrapSuccess() {
        sql("""
            CREATE FUNCTION fail_old_payload_delete() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'} BEGIN RAISE EXCEPTION 'blocked'; END; ${'$'}${'$'};
            CREATE TRIGGER block_old_payload BEFORE DELETE ON live_updates FOR EACH ROW EXECUTE FUNCTION fail_old_payload_delete();
        """.trimIndent())
        assertFails {prepareBackendSchema(source,NearbyCompatibility.VOICE_SPACES)}
        assertEquals(1,count("SELECT count(*) FROM sharing_sessions WHERE id='$sharing' AND revoked_at IS NULL"))
        assertEquals(1,count("SELECT count(*) FROM live_updates"))
    }
    @Test fun legacyBootstrapDoesNotWithdrawExistingLegacyConsent() {
        prepareBackendSchema(source)
        assertEquals(1,count("SELECT count(*) FROM sharing_sessions WHERE id='$sharing' AND revoked_at IS NULL"))
        assertEquals(1,count("SELECT count(*) FROM live_updates"))
    }
    @Test fun actualConfiguredFactoryPublishesNewRoutesAndRejectsLegacyWriters()=testApplication {
        prepareBackendSchema(source,NearbyCompatibility.VOICE_SPACES)
        val config=AppConfig.fromEnvironment(mapOf(
            "VOLTY_JWT_SECRET" to "test-secret-that-is-long-enough-for-hmac",
            "VOLTY_NEARBY_MODE" to "voice_spaces", "VOLTY_VOICE_PROVIDER" to "livekit",
            "LIVEKIT_URL" to "wss://media.invalid", "LIVEKIT_API_KEY" to "test-key", "LIVEKIT_API_SECRET" to "test-secret",
            "VOLTY_PUBLIC_IP" to "192.0.2.1", "VOLTY_NEARBY_SFU_CONTROL_ORIGIN" to "http://127.0.0.1:1",
        ))
        val dependencies=AppDependencies.fromPreparedDatabase(config,source)
        application {module(dependencies)}
        val token=dependencies.tokenService.issueAccessToken(owner)
        val community=id()
        val created=client.put("/v2/nearby/communities/$community") {
            bearerAuth(token);contentType(ContentType.Application.Json)
            setBody("""{"name":"Factory riders","visibility":"PUBLIC"}""")
        }
        assertEquals(HttpStatusCode.OK,created.status)
        val catalog=client.get("/v2/nearby/communities/catalog") {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK,catalog.status);assertTrue(catalog.bodyAsText().contains(community))
        val old=client.post("/v1/sharing/start") {bearerAuth(token);setBody("malformed")}
        assertEquals(HttpStatusCode.Conflict,old.status);assertTrue(old.bodyAsText().contains("client_upgrade_required"))
        assertEquals(0,count("SELECT count(*) FROM sharing_sessions WHERE revoked_at IS NULL"))
        assertEquals(1,count("SELECT count(*) FROM nearby_communities WHERE state='ACTIVE'"))
    }
}

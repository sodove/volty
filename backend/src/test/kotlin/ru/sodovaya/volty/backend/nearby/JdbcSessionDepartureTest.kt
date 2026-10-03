package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionDepartureTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val community = id(); private val departure = id()
    private val communities = JdbcCommunityRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val membership = JdbcCommunityMembershipRepository(source)
    private lateinit var channel: String

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c -> c.createStatement().use { s ->
            for (actor in listOf(owner, member)) s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
        } }
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })).snapshot.channels.single().id
        communities.joinPublic(member, community, { 100 })
    }

    @Test fun memberLeaveAtomicallyClosesExactVoiceAndRevokesLegacySharing() {
        legacy()
        val voice = voice(member)
        val result = assertIs<CommunityDepartureResult.Departed>(membership.leave(member, departure, community, now = { 101 }))
        assertEquals(listOf(voice), result.cleanupSessions.map { it.operation.sessionId })
        assertTrue(result.cleanupSessions.single().cleanupPending)
        assertNull(sessions.currentClaim(member)); assertNull(communities.detail(member, community))
        assertEquals(OperationPhase.CANCELLED, sessions.findSession(member, voice)!!.operation.phase)
        assertEquals(1, count("nearby_community_departures")); assertEquals(1, count("nearby_departure_targets"))
        assertEquals(0, count("live_updates")); assertEquals(1, count("group_members"))
        source.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT revoked_at FROM sharing_sessions").use {
            assertTrue(it.next()); assertEquals(101, it.getLong(1)); assertFalse(it.wasNull())
        } } }
        assertEquals(owner, communities.detail(owner, community)!!.community.ownerId)
    }

    @Test fun leavingMembershipDoesNotStopVoiceInAnotherCommunity() {
        val other = id()
        val otherChannel = assertIs<CommunityWriteResult.Accepted>(communities.create(member, other, "Other", now = { 100 })).snapshot.channels.single().id
        val voice = voice(member, otherChannel)
        val result = assertIs<CommunityDepartureResult.Departed>(membership.leave(member, departure, community, now = { 101 }))
        assertTrue(result.cleanupSessions.isEmpty()); assertEquals(voice, sessions.currentClaim(member)!!.sessionId)
        assertNotNull(communities.detail(member, other)); assertNull(communities.detail(member, community))
    }

    @Test fun ownerMustChooseSuccessorAndTransferPlusDepartureCommitsTogether() {
        legacy()
        val successorVoice = voice(member)
        val before = JdbcRosterJournal(source).revision()
        assertIs<CommunityDepartureResult.SuccessorRequired>(membership.leave(owner, departure, community, now = { 101 }))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_community_departures"))
        val result = assertIs<CommunityDepartureResult.Departed>(membership.leave(owner, departure, community, successor = member, now = { 102 }))
        assertTrue(result.cleanupSessions.isEmpty()); assertEquals(successorVoice, sessions.currentClaim(member)!!.sessionId)
        assertNull(communities.detail(owner, community)); assertEquals(member, communities.detail(member, community)!!.community.ownerId)
        source.connection.use { c -> c.createStatement().use { s ->
            s.executeQuery("SELECT owner_id,invite_code FROM groups").use { assertTrue(it.next()); assertEquals(member, it.getString(1)); assertEquals("KEEPME", it.getString(2)) }
            s.executeQuery("SELECT user_id,role FROM group_members").use { assertTrue(it.next()); assertEquals(member, it.getString(1)); assertEquals("OWNER", it.getString(2)); assertFalse(it.next()) }
        } }
    }

    @Test fun lastOwnerRequiresExplicitDeleteAndBannedSuccessorIsRejected() {
        source.connection.use { c -> c.createStatement().use { it.execute("INSERT INTO nearby_community_bans VALUES ('$community','$member',100)") } }
        assertIs<CommunityDepartureResult.InvalidSuccessor>(membership.leave(owner, departure, community, successor = member, now = { 101 }))
        source.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM nearby_community_members WHERE community_id='$community' AND user_id='$member'") } }
        val before = JdbcRosterJournal(source).revision()
        assertIs<CommunityDepartureResult.DeleteRequired>(membership.leave(owner, departure, community, now = { 102 }))
        assertNotNull(communities.detail(owner, community)); assertEquals(before, JdbcRosterJournal(source).revision())
        assertEquals(0, count("nearby_community_departures"))
    }

    @Test fun lostResponseReplayAfterRejoinCannotRetargetNewVoiceOrRemoveNewMembership() {
        val old = voice(member)
        val first = assertIs<CommunityDepartureResult.Departed>(membership.leave(member, departure, community, now = { 101 }))
        communities.joinPublic(member, community, { 102 })
        val current = voice(member)
        val before = JdbcRosterJournal(source).revision()
        val replay = assertIs<CommunityDepartureResult.Departed>(membership.leave(member, departure, community, now = { 103 }))
        assertEquals(first.revision, replay.revision); assertEquals(listOf(old), replay.cleanupSessions.map { it.operation.sessionId })
        assertEquals(current, sessions.currentClaim(member)!!.sessionId); assertNotNull(communities.detail(member, community))
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(1, count("nearby_community_departures"))
        assertEquals(1, count("nearby_departure_targets"))
    }

    @Test fun departureOperationConflictCannotLeaveAnotherCommunity() {
        membership.leave(member, departure, community, now = { 101 })
        val other = id()
        communities.create(member, other, "Other", now = { 102 })
        val before = JdbcRosterJournal(source).revision()
        assertIs<CommunityDepartureResult.Conflict>(membership.leave(member, departure, other, now = { 103 }))
        assertNotNull(communities.detail(member, other)); assertEquals(before, JdbcRosterJournal(source).revision())
    }

    @Test fun outboxFailureRollsBackOwnerTransferVoiceMembershipLegacyAndReceipt() {
        legacy(); val voice = voice(owner)
        val before = JdbcRosterJournal(source).revision()
        source.connection.use { c -> c.createStatement().use { it.execute("ALTER TABLE nearby_community_outbox ADD CONSTRAINT fixture_reject_leave CHECK(kind <> 'MEMBER_LEFT') NOT VALID") } }
        assertFailsWith<SQLException> { membership.leave(owner, departure, community, successor = member, now = { 101 }) }
        assertEquals(owner, communities.detail(owner, community)!!.community.ownerId)
        assertNotNull(communities.detail(member, community)); assertEquals(voice, sessions.currentClaim(owner)!!.sessionId)
        assertFalse(sessions.findSession(owner, voice)!!.cleanupPending)
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_community_departures"))
        assertEquals(0, count("nearby_departure_targets")); assertEquals(2, count("group_members"))
        source.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT owner_id FROM groups").use { assertTrue(it.next()); assertEquals(owner, it.getString(1)) } } }
    }

    @Test fun incoherentClaimEpochCannotGuessCleanupTargetOrCommitFalseDeparture() {
        val voice = voice(member)
        source.connection.use { c -> c.createStatement().use { it.execute("UPDATE nearby_actor_claims SET node_epoch='unknown-node' WHERE actor_id='$member'") } }
        val before = JdbcRosterJournal(source).revision()
        assertFailsWith<IllegalStateException> { membership.leave(member, departure, community, now = { 101 }) }
        assertNotNull(communities.detail(member, community))
        assertEquals(voice, sessions.currentClaim(member)!!.sessionId)
        assertEquals("unknown-node", sessions.currentClaim(member)!!.sfuEpoch.nodeEpoch)
        assertFalse(sessions.findSession(member, voice)!!.operation.terminal)
        assertEquals(before, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_community_departures"))
    }

    @Test fun voiceDisconnectRetainsPermanentMembershipAndOwner() {
        val voice = voice(member)
        val operation = sessions.findSession(member, voice)!!.operation.operationId
        sessions.cancel(member, operation, { 101 })
        assertNull(sessions.currentClaim(member)); assertNotNull(communities.detail(member, community))
        assertEquals(owner, communities.detail(owner, community)!!.community.ownerId)
        assertEquals(0, count("nearby_community_departures"))
    }

    private fun voice(actor: String, destination: String = channel): String = id().also { session ->
        assertNull(sessions.begin(actor, id(), destination, session, SfuEpoch("volty-channel-$destination", "node"), { 100 }).rejection)
    }
    private fun legacy() = source.connection.use { c -> c.createStatement().use { s ->
        s.execute("INSERT INTO groups VALUES ('$community','Riders','$owner','KEEPME',1000,100)")
        s.execute("INSERT INTO group_members VALUES ('$community','$owner','OWNER',100),('$community','$member','MEMBER',100)")
        s.execute("INSERT INTO sharing_sessions VALUES ('${id()}','$member','$community','LOCATION',100,1000,NULL)")
        s.execute("INSERT INTO live_updates VALUES ('$member','$community','{}',NULL,100,100)")
    } }
    private fun count(table: String) = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM $table").use { assertTrue(it.next()); it.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "session_departure_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}

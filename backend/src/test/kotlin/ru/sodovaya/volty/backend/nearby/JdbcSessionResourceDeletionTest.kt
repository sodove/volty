package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionResourceDeletionTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val community = id(); private val operation = id()
    private val communities = JdbcCommunityRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private val deletion = JdbcCommunityDeletionRepository(source)
    private lateinit var channel: String

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$owner','$owner@example.invalid','test','Owner',0),('$member','$member@example.invalid','test','Member',0)")
        channel = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })).snapshot.channels.single().id
        communities.joinPublic(member, community, { 100 })
    }

    @Test fun ownerAloneCanDeleteWithoutSuccessorAndTombstoneCannotBeReused() {
        sql("DELETE FROM nearby_community_members WHERE user_id='$member'")
        val result = assertIs<CommunityDeletionResult.Deleted>(deletion.deleteCommunity(owner, operation, community, now = { 101 }))
        assertTrue(result.cleanupSessions.isEmpty())
        assertNull(communities.detail(owner, community)); assertTrue(communities.memberships(owner).isEmpty())
        assertTrue(communities.catalog(member, "").isEmpty())
        assertIs<CommunityWriteResult.Denied>(communities.joinPublic(member, community, { 102 }))
        assertIs<CommunityWriteResult.Conflict>(communities.create(owner, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 102 }))
        assertEquals(1, count("nearby_community_members")) // historical owner FK, not active membership
        assertEquals(1, count("nearby_voice_channels", "state='DELETED'"))
        assertEquals(1, count("nearby_resource_deletions"))
    }

    @Test fun deletionCancelsEveryExactCommunityClaimAndClearsLegacyGroup() {
        legacy()
        val a = voice(owner); val b = voice(member)
        val result = assertIs<CommunityDeletionResult.Deleted>(deletion.deleteCommunity(owner, operation, community, now = { 101 }))
        assertEquals(setOf(a, b), result.cleanupSessions.map { it.operation.sessionId }.toSet())
        assertTrue(result.cleanupSessions.all { it.cleanupPending && it.operation.phase == OperationPhase.CANCELLED })
        assertNull(sessions.currentClaim(owner)); assertNull(sessions.currentClaim(member))
        assertEquals(2, count("nearby_resource_deletion_targets"))
        for (table in listOf("groups", "group_members", "sharing_sessions", "live_updates")) assertEquals(0, count(table), table)
        assertNull(communities.detail(member, community))
    }

    @Test fun voiceInAnotherCommunitySurvivesDeletion() {
        val other = id()
        val otherChannel = assertIs<CommunityWriteResult.Accepted>(communities.create(member, other, "Other", now = { 100 })).snapshot.channels.single().id
        val survivor = voice(member, otherChannel); val doomed = voice(owner)
        val result = assertIs<CommunityDeletionResult.Deleted>(deletion.deleteCommunity(owner, operation, community, now = { 101 }))
        assertEquals(listOf(doomed), result.cleanupSessions.map { it.operation.sessionId })
        assertEquals(survivor, sessions.currentClaim(member)!!.sessionId)
        assertNotNull(communities.detail(member, other))
    }

    @Test fun memberAndUnknownResourceReceiveSameDenialWithoutMutatingClaims() {
        val voice = voice(member); val revision = JdbcRosterJournal(source).revision()
        assertEquals(CommunityDeletionResult.Denied, deletion.deleteCommunity(member, operation, community, now = { 101 }))
        assertEquals(CommunityDeletionResult.Denied, deletion.deleteCommunity(member, operation, id(), now = { 101 }))
        assertEquals(voice, sessions.currentClaim(member)!!.sessionId)
        assertEquals(revision, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_resource_deletions"))
    }

    @Test fun receiptReplayAfterCleanupCannotTargetANewVoiceClaim() {
        val old = voice(owner)
        val first = assertIs<CommunityDeletionResult.Deleted>(deletion.deleteCommunity(owner, operation, community, now = { 101 }))
        val target = first.cleanupSessions.single()
        assertTrue(sessions.completeCleanup(owner, target.operation.operationId, old, target.sfuEpoch!!))
        val other = id()
        val otherChannel = assertIs<CommunityWriteResult.Accepted>(communities.create(owner, other, "New", now = { 102 })).snapshot.channels.single().id
        val fresh = voice(owner, otherChannel)
        val before = JdbcRosterJournal(source).revision()
        val replay = assertIs<CommunityDeletionResult.Deleted>(deletion.deleteCommunity(owner, operation, community, now = { 103 }))
        assertEquals(first.revision, replay.revision); assertEquals(listOf(old), replay.cleanupSessions.map { it.operation.sessionId })
        assertFalse(replay.cleanupSessions.single().cleanupPending)
        assertEquals(fresh, sessions.currentClaim(owner)!!.sessionId)
        assertEquals(before, JdbcRosterJournal(source).revision())
    }

    @Test fun deletionOperationConflictPreservesAnotherOwnedCommunity() {
        deletion.deleteCommunity(owner, operation, community, now = { 101 })
        val other = id()
        communities.create(owner, other, "Other", now = { 102 })
        assertEquals(CommunityDeletionResult.Conflict, deletion.deleteCommunity(owner, operation, other, now = { 103 }))
        assertNotNull(communities.detail(owner, other)); assertEquals(1, count("nearby_resource_deletions"))
    }

    @Test fun forcedFinalOutboxFailureRollsBackAllClaimsAndLegacyDeletion() {
        legacy(); val a = voice(owner); val b = voice(member); val revision = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_delete CHECK (kind <> 'DELETED')")
        assertFailsWith<SQLException> { deletion.deleteCommunity(owner, operation, community, now = { 101 }) }
        assertEquals(a, sessions.currentClaim(owner)!!.sessionId); assertEquals(b, sessions.currentClaim(member)!!.sessionId)
        assertNotNull(communities.detail(member, community))
        assertEquals(1, count("groups")); assertEquals(1, count("sharing_sessions")); assertEquals(1, count("live_updates"))
        assertEquals(0, count("nearby_resource_deletions")); assertEquals(0, count("nearby_resource_deletion_targets"))
        assertEquals(revision, JdbcRosterJournal(source).revision())
    }

    @Test fun incoherentClaimRollsBackDeletionRatherThanGuessingTarget() {
        val a = voice(owner); voice(member); val revision = JdbcRosterJournal(source).revision()
        sql("UPDATE nearby_actor_claims SET node_epoch='foreign' WHERE actor_id='$member'")
        assertFailsWith<IllegalStateException> { deletion.deleteCommunity(owner, operation, community, now = { 101 }) }
        assertEquals(a, sessions.currentClaim(owner)!!.sessionId); assertNotNull(communities.detail(owner, community))
        assertEquals(revision, JdbcRosterJournal(source).revision()); assertEquals(0, count("nearby_resource_deletions"))
    }

    private fun voice(actor: String, destination: String = channel) = id().also {
        assertNull(sessions.begin(actor, id(), destination, it, SfuEpoch("volty-channel-$destination", "node"), { 100 }).rejection)
    }
    private fun legacy() {
        sql("INSERT INTO groups VALUES ('$community','Riders','$owner','DELETE_ME',1000,100)")
        sql("INSERT INTO group_members VALUES ('$community','$owner','OWNER',100),('$community','$member','MEMBER',100)")
        sql("INSERT INTO sharing_sessions VALUES ('${id()}','$member','$community','LOCATION',100,1000,NULL)")
        sql("INSERT INTO live_updates VALUES ('$member','$community','{}',NULL,100,100)")
    }
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String, where: String = "TRUE") = source.connection.use { c -> c.createStatement().use { s ->
        s.executeQuery("SELECT count(*) FROM $table WHERE $where").use { assertTrue(it.next()); it.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "session_resource_delete_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}

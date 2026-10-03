package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.JdbcStore
import ru.sodovaya.volty.backend.SchemaMigrator
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.*

@Tag("postgres")
class JdbcSessionAccountDeletionTest {
    private val source = schema()
    private val owner = id(); private val member = id(); private val survivor = id()
    private val owned = id(); private val other = id()
    private val communities = JdbcCommunityRepository(source)
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    private lateinit var ownedChannel: String; private lateinit var otherChannel: String

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (actor in listOf(owner, member, survivor)) sql("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','Rider',0)")
        ownedChannel = create(owner, owned)
        otherChannel = create(member, other)
        communities.joinPublic(member, owned, { 100 }); communities.joinPublic(survivor, owned, { 100 })
        communities.joinPublic(owner, other, { 100 }); communities.joinPublic(survivor, other, { 100 })
    }
    @Test fun existingStorePathClosesOwnedClaimsAndActorsOutsideVoiceButPreservesUnrelatedPeer() {
        val a = voice(owner, otherChannel); val b = voice(member, ownedChannel); val c = voice(survivor, otherChannel)
        legacy()
        assertEquals(OperationPhase.ACTIVE, sessions.findSession(owner, a.session)!!.operation.phase)
        assertTrue(checkNotNull(sessions.findSession(owner, a.session)!!.operation.deadlineEpochMillis) < System.currentTimeMillis())
        assertEquals(1, JdbcStore(source, Json).deleteAccount(owner))
        assertNull(sessions.currentClaim(owner)); assertNull(sessions.currentClaim(member))
        assertEquals(c.session, sessions.currentClaim(survivor)!!.sessionId)
        for (v in listOf(a, b)) {
            val row = sessions.findSession(v.actor, v.session)!!
            // Store uses the actual server clock. Earlier CANCELLED expectation
            // ignored this deliberately stale earned lease; expiry is preserved.
            assertEquals(OperationPhase.EXPIRED, row.operation.phase); assertTrue(row.cleanupPending)
        }
        assertNull(communities.detail(member, owned)); assertNotNull(communities.detail(member, other))
        assertEquals(0, count("nearby_community_members", "community_id='$other' AND user_id='$owner'"))
        assertEquals(1, count("nearby_community_members", "community_id='$owned' AND user_id='$owner'"))
        for (table in listOf("groups", "sharing_sessions", "live_updates")) assertEquals(0, count(table), table)
        assertEquals(1, count("users", "id='$owner' AND deleted_at IS NOT NULL"))
    }
    @Test fun finalOutboxFailureRollsBackUserClaimsResourcesAndPosition() {
        val a = voice(owner, otherChannel); val b = voice(member, ownedChannel); legacy()
        val before = JdbcRosterJournal(source).revision()
        sql("ALTER TABLE nearby_community_outbox ADD CONSTRAINT reject_delete CHECK (kind <> 'DELETED')")
        assertFailsWith<SQLException> { JdbcStore(source, Json).deleteAccount(owner) }
        assertEquals(0, count("users", "id='$owner' AND deleted_at IS NOT NULL"))
        assertEquals(a.session, sessions.currentClaim(owner)!!.sessionId); assertEquals(b.session, sessions.currentClaim(member)!!.sessionId)
        assertNotNull(communities.detail(owner, owned)); assertNotNull(communities.detail(owner, other))
        assertEquals(1, count("groups")); assertEquals(1, count("sharing_sessions")); assertEquals(1, count("live_updates"))
        assertEquals(before, JdbcRosterJournal(source).revision())
    }
    @Test fun repeatedDeletionCannotReissueCredentialsOrInventAnotherRevision() {
        val v = voice(owner, otherChannel)
        val store = JdbcStore(source, Json)
        assertEquals(1, store.deleteAccount(owner)); val revision = JdbcRosterJournal(source).revision()
        assertEquals(0, store.deleteAccount(owner)); assertEquals(revision, JdbcRosterJournal(source).revision())
        var issued = 0
        assertNull(sessions.withActiveSession(owner, v.operation, { 101 }) { issued++; "secret" })
        assertEquals(0, issued)
        assertEquals(OperationRejection.ACCESS_DENIED, sessions.begin(owner, id(), otherChannel, id(), epoch(otherChannel), { 102 }).rejection)
        assertNull(sessions.currentClaim(owner))
    }
    @Test fun incoherentExactClaimRollsBackRatherThanGuessingCleanupTarget() {
        voice(owner, otherChannel); val b = voice(member, ownedChannel)
        sql("UPDATE nearby_actor_claims SET node_epoch='foreign' WHERE actor_id='$member'")
        val revision = JdbcRosterJournal(source).revision()
        assertFailsWith<IllegalStateException> { JdbcStore(source, Json).deleteAccount(owner) }
        assertEquals(0, count("users", "id='$owner' AND deleted_at IS NOT NULL"))
        assertNotNull(communities.detail(owner, owned)); assertEquals(revision, JdbcRosterJournal(source).revision())
        assertFalse(sessions.findSession(member, b.session)!!.cleanupPending)
    }
    @Test fun credentialProducerFinishesBeforeDeletionCanCommitAndCannotRunAfterwards() {
        val v = voice(owner, otherChannel); val barrier = AccessFenceBarrierSource(source)
        val guarded = JdbcSessionRepository(barrier, JdbcChannelAccess()); val pool = Executors.newFixedThreadPool(2)
        try {
            var issued = 0
            val grant = pool.submit<String?> { guarded.withActiveSession(owner, v.operation, { 101 }) { issued++; "before" } }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val deletion = pool.submit<Int> { JdbcAccountDeletionRepository(barrier).deleteAccount(owner, { 101 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(deletion.isDone)
            barrier.release.countDown()
            assertEquals("before", grant.get(10, TimeUnit.SECONDS)); assertEquals(1, deletion.get(10, TimeUnit.SECONDS))
            val cancelled = guarded.findSession(owner, v.session)!!
            assertEquals(OperationPhase.CANCELLED, cancelled.operation.phase); assertTrue(cancelled.cleanupPending)
            assertNull(guarded.withActiveSession(owner, v.operation, { 102 }) { issued++; "after" }); assertEquals(1, issued)
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    @Test fun joinIssuedBehindDeletionFenceReadsCommittedAccountLoss() {
        val barrier = AccessFenceBarrierSource(source); val pool = Executors.newFixedThreadPool(2)
        val guarded = JdbcSessionRepository(barrier, JdbcChannelAccess())
        try {
            val deletion = pool.submit<Int> { JdbcAccountDeletionRepository(barrier).deleteAccount(owner, { 101 }) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val join = pool.submit<SessionJournalResult> { guarded.begin(owner, id(), otherChannel, id(), epoch(otherChannel), { 102 }) }
            assertTrue(barrier.competingIssued.await(5, TimeUnit.SECONDS)); assertFalse(join.isDone)
            barrier.release.countDown()
            assertEquals(1, deletion.get(10, TimeUnit.SECONDS)); assertEquals(OperationRejection.ACCESS_DENIED, join.get(10, TimeUnit.SECONDS).rejection)
            assertNull(guarded.currentClaim(owner))
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }
    private data class Voice(val actor: String, val operation: String, val session: String)
    private fun voice(actor: String, channel: String): Voice = Voice(actor, id(), id()).also {
        assertNull(sessions.begin(actor, it.operation, channel, it.session, epoch(channel), { 100 }).rejection)
        assertNull(sessions.admit(actor, it.operation, it.session, epoch(channel), { 100 }, 30_100).rejection)
    }
    private fun epoch(channel: String) = SfuEpoch("volty-channel-$channel", "node")
    private fun create(actor: String, community: String) = assertIs<CommunityWriteResult.Accepted>(
        communities.create(actor, community, "Riders", visibility = CommunityVisibility.PUBLIC, now = { 100 })).snapshot.channels.single().id
    private fun legacy() {
        sql("INSERT INTO groups VALUES ('$owned','Riders','$owner','ACCOUNT_DELETE',1000,100)")
        sql("INSERT INTO group_members VALUES ('$owned','$owner','OWNER',100),('$owned','$member','MEMBER',100)")
        sql("INSERT INTO sharing_sessions VALUES ('${id()}','$owner','$owned','LOCATION',100,1000,NULL)")
        sql("INSERT INTO live_updates VALUES ('$owner','$owned','{}',NULL,100,100)")
    }
    private fun sql(query: String) = source.connection.use { c -> c.createStatement().use { it.execute(query) } }
    private fun count(table: String, where: String = "TRUE") = source.connection.use { c -> c.createStatement().use {
        it.executeQuery("SELECT count(*) FROM $table WHERE $where").use { rows -> assertTrue(rows.next()); rows.getInt(1) }
    } }
    private fun schema(): DataSource {
        val name = "session_account_delete_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}

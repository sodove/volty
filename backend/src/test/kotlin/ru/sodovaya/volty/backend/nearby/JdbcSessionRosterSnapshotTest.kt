package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionRosterSnapshotTest {
    private val source = schema()
    private val actor = id()
    private val other = id()
    private val memberWithoutSession = id()
    private val destination = id()
    private val operation = id()
    private val session = id()
    private val epoch = SfuEpoch("room-generation", "node-incarnation")
    private val sessions = JdbcSessionRepository(source)
    private var now = 100L
    private val roster = JdbcRosterRepository(source) { now }

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        for (owner in listOf(actor, other, memberWithoutSession)) source.connection.use { c ->
            c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
                it.setString(1, owner); it.setString(2, "$owner@example.invalid"); it.executeUpdate()
            }
        }
    }

    @Test fun pendingAndRegisteredSessionsNeverPretendMediaIsConfirmed() {
        begin()
        sessions.admit(actor, operation, session, epoch, { 101 }, 30_100)
        assertEquals(OperationPhase.ACTIVE, sessions.get(actor, operation)!!.operation.phase)
        sessions.begin(other, id(), destination, id(), epoch, { 102 })
        val snapshot = roster.snapshot(destination)
        assertEquals(3, snapshot.revision)
        assertEquals(100, snapshot.serverTimeEpochMillis)
        assertEquals(setOf(actor, other), snapshot.peers.map { it.actorId }.toSet())
        assertEquals(RosterConnectionPhase.CONNECTING, snapshot.peers.single { it.actorId == actor }.phase)
        assertEquals(RosterConnectionPhase.JOINING, snapshot.peers.single { it.actorId == other }.phase)
        assertTrue(snapshot.peers.all { it.mediaPresence == MediaPresence.UNKNOWN })
        assertTrue(snapshot.peers.none { it.actorId == memberWithoutSession })
    }

    @Test fun replacementAndLateCleanupLeaveOnlyCurrentSessionInSnapshot() {
        begin()
        val newOperation = id(); val newSession = id()
        sessions.begin(actor, newOperation, destination, newSession, epoch, { 101 }, replace = true)
        val before = roster.snapshot(destination)
        assertEquals(3, before.revision)
        assertEquals(newSession, before.peers.single().sessionId)
        sessions.completeCleanup(actor, operation, session, epoch)
        assertEquals(before, roster.snapshot(destination))
        sessions.cancel(actor, newOperation, { 102 })
        assertTrue(roster.snapshot(destination).peers.isEmpty())
        assertEquals(4, roster.snapshot(destination).revision)
    }

    @Test fun revisionAndRowsComeFromOneSnapshotDuringConcurrentJoin() {
        begin()
        val barrier = RevisionReadBarrierSource(source)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<ChannelRosterSnapshot> { JdbcRosterRepository(barrier) { now }.snapshot(destination) }
            assertTrue(barrier.readIssued.await(5, TimeUnit.SECONDS))
            assertFalse(read.isDone)
            val joined = sessions.begin(other, id(), destination, id(), epoch, { 101 })
            assertNull(joined.rejection)
            assertFalse(read.isDone)
            barrier.release.countDown()
            val older = read.get(10, TimeUnit.SECONDS)
            assertEquals(1, older.revision)
            assertEquals(listOf(actor), older.peers.map { it.actorId })
            val latest = roster.snapshot(destination)
            assertEquals(2, latest.revision)
            assertEquals(setOf(actor, other), latest.peers.map { it.actorId }.toSet())
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    @Test fun clockPassageKeepsDeadlineExplicitUntilExpiryIsCommitted() {
        begin()
        now = 15_100
        val expiredLease = roster.snapshot(destination)
        assertEquals(1, expiredLease.revision)
        assertEquals(expiredLease.serverTimeEpochMillis, expiredLease.peers.single().deadlineEpochMillis)
        assertEquals(MediaPresence.UNKNOWN, expiredLease.peers.single().mediaPresence)
        sessions.expire(actor, operation, { now })
        val ended = roster.snapshot(destination)
        assertEquals(2, ended.revision)
        assertTrue(ended.peers.isEmpty())
    }

    @Test fun incoherentClaimEpochCannotEarnPresenceFromAnActiveOperation() {
        begin()
        sessions.admit(actor, operation, session, epoch, { 101 }, 30_100)
        assertEquals(OperationPhase.ACTIVE, sessions.get(actor, operation)!!.operation.phase)
        source.connection.use { c ->
            c.prepareStatement("UPDATE nearby_actor_claims SET node_epoch='older-node' WHERE actor_id=?::uuid").use {
                it.setString(1, actor); assertEquals(1, it.executeUpdate())
            }
        }
        assertEquals("older-node", sessions.currentClaim(actor)!!.sfuEpoch.nodeEpoch)
        assertTrue(roster.snapshot(destination).peers.isEmpty())
        assertEquals(2, roster.snapshot(destination).revision)
    }

    private fun begin() = sessions.begin(actor, operation, destination, session, epoch, { 100 })
    private fun schema(): javax.sql.DataSource {
        val name = "snapshot_test_" + id().replace("-", "")
        NearbyPostgresFixture.source().connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun id() = UUID.randomUUID().toString()
}

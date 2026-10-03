package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import javax.sql.DataSource
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionRosterJournalTest {
    private val source = schema()
    private val actor = id()
    private val operation = id()
    private val destination = id()
    private val session = id()
    private val epoch = SfuEpoch("room", "node")
    private val sessions = JdbcSessionRepository(source)
    private val journal = JdbcRosterJournal(source)

    @BeforeEach fun prepare() { SchemaMigrator(source).migrate(); user(source, actor) }

    @Test fun visibleStateChangesAdvanceRevisionWhileReplaysAndCleanupDoNot() {
        assertEquals(0, journal.revision())
        sessions.begin(actor, operation, destination, session, epoch, { 100 })
        assertEquals(1, journal.revision())
        sessions.begin(actor, operation, destination, id(), epoch, { 200 })
        assertEquals(1, journal.revision())
        sessions.admit(actor, operation, session, epoch, { 201 }, 30_100)
        assertEquals(2, journal.revision())
        sessions.admit(actor, operation, session, epoch, { 202 }, 40_000)
        assertEquals(2, journal.revision())
        sessions.heartbeat(actor, operation, session, epoch, { 1000 }, 31_000)
        assertEquals(3, journal.revision())
        sessions.cancel(actor, operation, { 1001 })
        assertEquals(4, journal.revision())
        sessions.cancel(actor, operation, { 1002 })
        sessions.completeCleanup(actor, operation, session, epoch)
        assertEquals(4, journal.revision())
        val events = journal.eventsAfter(0, 100)
        assertEquals(listOf(1L, 2L, 3L, 4L), events.map { it.revision })
        assertEquals(listOf(OperationPhase.PENDING, OperationPhase.ACTIVE, OperationPhase.ACTIVE, OperationPhase.CANCELLED), events.map { it.phase })
        assertTrue(events.all { it.actorId == actor && it.destinationId == destination && it.sessionId == session })
        assertEquals(31_000, events[2].deadlineEpochMillis)
    }

    @Test fun cancellationBeforeJoinCreatesNoFictionalRosterEntry() {
        sessions.cancel(actor, operation, { 100 })
        sessions.begin(actor, operation, destination, session, epoch, { 101 })
        assertEquals(OperationPhase.CANCELLED, sessions.get(actor, operation)!!.operation.phase)
        assertEquals(0, journal.revision())
        assertTrue(journal.eventsAfter(0, 10).isEmpty())
    }

    @Test fun failedReplacementRollsBackStateRevisionAndOutboxTogether() {
        sessions.begin(actor, operation, destination, session, epoch, { 100 })
        assertFails { sessions.begin(actor, id(), "invalid-uuid", id(), epoch, { 101 }, replace = true) }
        assertEquals(OperationPhase.PENDING, sessions.get(actor, operation)!!.operation.phase)
        assertEquals(session, sessions.currentClaim(actor)!!.sessionId)
        assertEquals(1, journal.revision())
        assertEquals(listOf(OperationPhase.PENDING), journal.eventsAfter(0, 100).map { it.phase })
    }

    @Test fun competingActorsCommitUniqueOrderedRevisions() {
        val other = id(); user(source, other)
        val actors = listOf(actor, other)
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = actors.map { owner -> pool.submit<SessionJournalResult> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS))
                sessions.begin(owner, id(), destination, id(), epoch, { 100 })
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            futures.forEach { assertNull(it.get(15, TimeUnit.SECONDS).rejection) }
            assertEquals(2, journal.revision())
            val events = journal.eventsAfter(0, 10)
            assertEquals(listOf(1L, 2L), events.map { it.revision })
            assertEquals(actors.toSet(), events.map { it.actorId }.toSet())
        } finally { pool.shutdownNow() }
    }

    @Test fun restartAndRepeatedCursorReadPreserveUndeliveredEvents() {
        sessions.begin(actor, operation, destination, session, epoch, { 100 })
        sessions.cancel(actor, operation, { 101 })
        val restarted = JdbcRosterJournal(source)
        val first = restarted.eventsAfter(0, 1).single()
        assertEquals(first, restarted.eventsAfter(0, 1).single())
        val second = restarted.eventsAfter(first.revision, 1).single()
        assertEquals(2, second.revision)
        assertEquals(2, restarted.revision())
        assertTrue(restarted.eventsAfter(second.revision, 1).isEmpty())
    }

    @Test fun versionTwoUpgradePreservesExistingPendingLeaseAndClaim() {
        val old = schema()
        SchemaMigrator(old, listOf(1 to "/db/migration/V1__initial.sql", 2 to "/db/migration/V2__nearby_session_journal.sql")).migrate()
        user(old, actor)
        old.connection.use { c ->
            c.prepareStatement("INSERT INTO nearby_session_operations(actor_id,operation_id,destination_id,session_id,room_epoch,node_epoch,phase,deadline_epoch_millis,admission_pending) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,'room','node','PENDING',15100,TRUE)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, destination); it.setString(4, session); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id,operation_id,session_id,room_epoch,node_epoch) VALUES (?::uuid,?::uuid,?::uuid,'room','node')").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, session); it.executeUpdate()
            }
        }
        SchemaMigrator(old).migrate(); SchemaMigrator(old).migrate()
        val upgraded = JdbcSessionRepository(old)
        assertEquals(session, upgraded.currentClaim(actor)!!.sessionId)
        assertTrue(upgraded.get(actor, operation)!!.admissionPending)
        assertEquals(15_100, upgraded.get(actor, operation)!!.operation.deadlineEpochMillis)
        assertEquals(0, JdbcRosterJournal(old).revision())
        upgraded.cancel(actor, operation, { 101 })
        assertEquals(1, JdbcRosterJournal(old).revision())
        assertEquals(session, JdbcRosterJournal(old).eventsAfter(0, 1).single().sessionId)
    }

    private fun schema(): DataSource {
        val name = "roster_test_" + id().replace("-", "")
        val base = NearbyPostgresFixture.source()
        base.connection.use { it.createStatement().use { statement -> statement.execute("CREATE SCHEMA $name") } }
        return NearbyPostgresFixture.source(name)
    }
    private fun user(target: DataSource, owner: String) = target.connection.use { c ->
        c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
            it.setString(1, owner); it.setString(2, "$owner@example.invalid"); it.executeUpdate()
        }
    }
    private fun id() = UUID.randomUUID().toString()
}

package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import javax.sql.DataSource
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.test.*

@Tag("postgres")
class JdbcSessionRepositoryTest {
    private val source = NearbyPostgresFixture.source()
    private val actor = id()
    private val operation = id()
    private val destination = id()
    private val session = id()
    private val repository = JdbcSessionRepository(source)
    private val epoch = SfuEpoch("room-epoch-1", "node-epoch-1")
    private fun begin(op: String = operation, sid: String = session, time: Long = 100, replace: Boolean = false) =
        repository.begin(actor, op, destination, sid, epoch, { time }, replace)

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { connection ->
            connection.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid, ?, 'test', 'test', 0)").use {
                it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
            }
        }
    }

    @Test fun cancelBeforeJoinSurvivesRepositoryRestart() {
        repository.cancel(actor, operation, { 100 })
        val restarted = JdbcSessionRepository(source)
        assertEquals(OperationRejection.TERMINAL, restarted.begin(actor, operation, destination, session, epoch, { 101 }).rejection)
        assertEquals(OperationPhase.CANCELLED, restarted.get(actor, operation)!!.operation.phase)
        assertNull(restarted.currentClaim(actor))
    }

    @Test fun lostResponseReplayDoesNotReplaceSessionOrExtendDeadline() {
        val first = begin().state!!
        val replay = begin(sid = id(), time = 200).state!!
        assertEquals(first, replay)
        assertEquals(session, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun cancellationAfterJoinRetainsDurableCleanupIntent() {
        begin()
        repository.cancel(actor, operation, { 101 })
        val cancelled = repository.get(actor, operation)!!
        assertEquals(session, cancelled.operation.sessionId)
        assertTrue(cancelled.cleanupPending)
        assertFalse(cancelled.admissionPending)
        assertNull(repository.currentClaim(actor))
        assertEquals(OperationRejection.TERMINAL, repository.admit(actor, operation, session, epoch, { 102 }, 30_100).rejection)
    }

    @Test fun secondOperationWithoutReplacementCannotTakeLiveClaim() {
        begin()
        val next = id()
        val result = begin(next, id(), 101)
        assertNull(result.state)
        assertNotNull(result.rejection)
        assertNull(repository.get(actor, next))
        assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
        assertEquals(session, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun competingOperationsWithoutReplacementHaveExactlyOneWinner() {
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val ops = listOf(id(), id())
        try {
            val futures = ops.map { op -> pool.submit<SessionJournalResult> {
                ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); begin(op, id(), 100)
            } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            val results = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.state != null && it.rejection == null })
            assertEquals(1, results.count { it.state == null && it.rejection != null })
            assertEquals(1, ops.count { repository.get(actor, it) != null })
            assertTrue(repository.currentClaim(actor)!!.operationId in ops)
        } finally { pool.shutdownNow() }
    }

    @Test fun expiredClaimAllowsNewOperationWithoutReplacementAndKeepsOldCleanup() {
        begin()
        val nextOp = id(); val nextSession = id()
        val result = begin(nextOp, nextSession, 15_100)
        assertNull(result.rejection)
        assertEquals(nextSession, result.state!!.operation.sessionId)
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(nextSession, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun replacementAndOldCleanupNeverReleaseNewClaim() {
        begin()
        val nextOp = id(); val nextSession = id()
        begin(nextOp, nextSession, 101, replace = true)
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        assertFalse(repository.completeCleanup(actor, operation, id(), epoch))
        assertFalse(repository.completeCleanup(actor, operation, session, epoch.copy(nodeEpoch = "wrong-node")))
        assertTrue(repository.completeCleanup(actor, operation, session, epoch))
        assertEquals(nextSession, repository.currentClaim(actor)!!.sessionId)
        assertEquals(OperationRejection.TERMINAL, repository.admit(actor, operation, session, epoch, { 102 }, 30_100).rejection)
        repository.cancel(actor, operation, { 103 })
        assertFalse(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(nextSession, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun admissionUsesMatchingClaimAndNodeEpoch() {
        begin()
        assertEquals(OperationRejection.EPOCH_MISMATCH, repository.admit(actor, operation, session, epoch.copy(nodeEpoch = "old-node"), { 101 }, 30_100).rejection)
        assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
        val active = repository.admit(actor, operation, session, epoch, { 101 }, 30_100).state!!
        assertEquals(OperationPhase.ACTIVE, active.operation.phase)
        assertFalse(active.admissionPending)
        assertEquals(30_100, active.operation.deadlineEpochMillis)
    }

    @Test fun exactDeadlineHeartbeatPersistsExpiryAndCleanup() {
        begin()
        repository.admit(actor, operation, session, epoch, { 101 }, 30_100)
        val result = repository.heartbeat(actor, operation, session, epoch, { 30_100 }, 60_100)
        assertEquals(OperationRejection.TERMINAL, result.rejection)
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        assertNull(repository.currentClaim(actor))
    }

    @Test fun rollbackRestoresPreviousClaimAndOperation() {
        begin()
        assertFails { repository.begin(actor, id(), "invalid-uuid", id(), epoch, { 101 }, replace = true) }
        assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
        assertFalse(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(session, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun concurrentCancelAndJoinNeverLeaveAnActiveOperation() {
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val join = pool.submit { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); begin() }
            val cancel = pool.submit { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); repository.cancel(actor, operation, { 100 }) }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            join.get(10, TimeUnit.SECONDS); cancel.get(10, TimeUnit.SECONDS)
            assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
            assertNull(repository.currentClaim(actor))
        } finally { pool.shutdownNow() }
    }

    @Test fun pendingExpirySweeperPersistsTerminalIntent() {
        begin()
        repository.expire(actor, operation, { 15_100 })
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(OperationRejection.TERMINAL, begin(time = 15_101).rejection)
    }

    @Test fun cancelAndJoinSerializeInBothMeasuredLockOrders() {
        source.connection.use { c ->
            c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id) VALUES (?::uuid)").use { it.setString(1, actor); it.executeUpdate() }
        }
        for (cancelFirst in listOf(true, false)) {
            val op = id(); val sid = id()
            val barrier = ActorLockBarrierSource(source)
            val measured = JdbcSessionRepository(barrier)
            val join = { measured.begin(actor, op, destination, sid, epoch, { 100 }) }
            val cancel = { measured.cancel(actor, op, { 100 }) }
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<SessionJournalResult> { if (cancelFirst) cancel() else join() }
                assertTrue(barrier.held.await(5, TimeUnit.SECONDS), "First transaction acquired actual actor lock")
                val second = pool.submit<SessionJournalResult> { if (cancelFirst) join() else cancel() }
                assertTrue(barrier.competingQueryIssued.await(5, TimeUnit.SECONDS), "Competing lock query issued")
                assertFalse(first.isDone)
                assertFalse(second.isDone)
                barrier.release.countDown()
                val firstResult = first.get(10, TimeUnit.SECONDS)
                val secondResult = second.get(10, TimeUnit.SECONDS)
                if (cancelFirst) assertEquals(OperationRejection.TERMINAL, secondResult.rejection)
                else assertEquals(OperationPhase.PENDING, firstResult.state!!.operation.phase)
                val terminal = repository.get(actor, op)!!
                assertEquals(OperationPhase.CANCELLED, terminal.operation.phase)
                assertEquals(!cancelFirst, terminal.cleanupPending)
                assertNull(repository.currentClaim(actor))
            } finally { barrier.release.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun actorLockWaitFailsWithDatabaseTimeoutAndRollsBack() {
        source.connection.use { c ->
            c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id) VALUES (?::uuid)").use { it.setString(1, actor); it.executeUpdate() }
        }
        val barrier = ActorLockBarrierSource(source)
        val measured = JdbcSessionRepository(barrier)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val join = pool.submit<SessionJournalResult> { measured.begin(actor, operation, destination, session, epoch, { 100 }) }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val cancel = pool.submit<SessionJournalResult> { measured.cancel(actor, operation, { 101 }) }
            assertTrue(barrier.competingQueryIssued.await(5, TimeUnit.SECONDS))
            val failure = assertFailsWith<ExecutionException> { cancel.get(8, TimeUnit.SECONDS) }
            assertEquals("55P03", (failure.cause as SQLException).sqlState)
            barrier.release.countDown()
            join.get(10, TimeUnit.SECONDS)
            assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
            assertEquals(session, repository.currentClaim(actor)!!.sessionId)
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    @Test fun authorizationMustUseTimeAfterWaitingForActorLock() {
        begin()
        repository.admit(actor, operation, session, epoch, { 101 }, 30_100)
        val clock = AtomicLong(100)
        val issued = AtomicInteger()
        val barrier = ActorLockBarrierSource(source)
        val measured = JdbcSessionRepository(barrier)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val holder = pool.submit {
                barrier.connection.use { c ->
                    c.autoCommit = false
                    c.prepareStatement("SELECT actor_id FROM nearby_actor_claims WHERE actor_id=?::uuid FOR UPDATE").use {
                        it.setString(1, actor); it.executeQuery().use { rows -> check(rows.next()) }
                    }
                    c.commit()
                }
            }
            assertTrue(barrier.held.await(5, TimeUnit.SECONDS))
            val authorization = pool.submit<String?> {
                measured.withActiveSession(actor, operation, { clock.get() }) { issued.incrementAndGet(); "credential" }
            }
            assertTrue(barrier.competingQueryIssued.await(5, TimeUnit.SECONDS))
            clock.set(30_100)
            barrier.release.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertNull(authorization.get(10, TimeUnit.SECONDS))
            assertEquals(0, issued.get())
            assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        } finally { barrier.release.countDown(); pool.shutdownNow() }
    }

    @Test fun migrationReplayPreservesLegacyAccountsAndMembership() {
        val upgradeSource = freshSchema()
        SchemaMigrator(upgradeSource, listOf(1 to "/db/migration/V1__initial.sql")).migrate()
        val group = id()
        upgradeSource.connection.use { c ->
            c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','legacy',0)").use {
                it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO groups(id,name,owner_id,invite_code,created_at) VALUES (?::uuid,'legacy',?::uuid,?,0)").use {
                it.setString(1, group); it.setString(2, actor); it.setString(3, group.replace("-", "")); it.executeUpdate()
            }
            c.prepareStatement("INSERT INTO group_members(group_id,user_id,role,joined_at) VALUES (?::uuid,?::uuid,'OWNER',0)").use {
                it.setString(1, group); it.setString(2, actor); it.executeUpdate()
            }
        }
        SchemaMigrator(upgradeSource).migrate()
        SchemaMigrator(upgradeSource).migrate()
        upgradeSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM group_members WHERE group_id=?::uuid AND user_id=?::uuid").use {
                it.setString(1, group); it.setString(2, actor); it.executeQuery().use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) }
            }
            c.createStatement().use { it.executeQuery("SELECT count(*) FROM schema_migrations WHERE version=2").use { rows -> rows.next(); assertEquals(1, rows.getInt(1)) } }
        }
    }

    @Test fun failedMigrationRollsBackDdlAndVersionRecord() {
        assertFails { SchemaMigrator(source, listOf(999 to "/db/migration/V999__rollback_test.sql")).migrate() }
        source.connection.use { c ->
            c.createStatement().use { it.executeQuery("SELECT to_regclass('nearby_failed_migration_probe')").use { rows -> rows.next(); assertNull(rows.getString(1)) } }
            c.createStatement().use { it.executeQuery("SELECT count(*) FROM schema_migrations WHERE version=999").use { rows -> rows.next(); assertEquals(0, rows.getInt(1)) } }
        }
    }

    @Test fun concurrentMigrationStartsApplyEachVersionOnce() {
        val fresh = freshSchema()
        val ready = CountDownLatch(2); val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map { pool.submit { ready.countDown(); check(start.await(5, TimeUnit.SECONDS)); SchemaMigrator(fresh).migrate() } }
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown()
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
            fresh.connection.use { c ->
                c.createStatement().use { it.executeQuery("SELECT count(*) FROM schema_migrations").use { rows -> rows.next(); assertEquals(9, rows.getInt(1)) } }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun admissionWithoutCurrentClaimIsRejected() {
        begin()
        source.connection.use { c ->
            c.prepareStatement("UPDATE nearby_actor_claims SET operation_id=NULL,session_id=NULL,room_epoch=NULL,node_epoch=NULL WHERE actor_id=?::uuid").use {
                it.setString(1, actor); it.executeUpdate()
            }
        }
        assertEquals(OperationRejection.CLAIM_MISMATCH, repository.admit(actor, operation, session, epoch, { 101 }, 30_100).rejection)
        assertEquals(OperationPhase.PENDING, repository.get(actor, operation)!!.operation.phase)
    }

    @Test fun roomAndNodeEpochsArePersistedAndFencedIndependently() {
        val key = SfuEpoch("room-generation-3", "node-incarnation-8")
        val started = repository.begin(actor, operation, destination, session, key, { 100 }).state!!
        assertEquals(key, started.sfuEpoch)
        assertEquals(key, JdbcSessionRepository(source).get(actor, operation)!!.sfuEpoch)
        assertEquals(OperationRejection.EPOCH_MISMATCH, repository.admit(actor, operation, session, key.copy(roomEpoch = "room-generation-2"), { 101 }, 30_100).rejection)
        assertEquals(OperationRejection.EPOCH_MISMATCH, repository.admit(actor, operation, session, key.copy(nodeEpoch = "node-incarnation-7"), { 101 }, 30_100).rejection)
        repository.cancel(actor, operation, { 102 })
        assertFalse(repository.completeCleanup(actor, operation, session, key.copy(roomEpoch = "room-generation-2")))
        assertFalse(repository.completeCleanup(actor, operation, session, key.copy(nodeEpoch = "node-incarnation-7")))
        assertTrue(repository.completeCleanup(actor, operation, session, key))
    }

    @Test fun durableWorkAndDueQueriesReflectStateWithoutNetworkEffects() {
        begin()
        assertTrue(repository.pendingWork(1000).any { it.operation.sessionId == session && it.admissionPending })
        assertFalse(repository.dueOperations(101, 1000).any { it.operation.sessionId == session })
        assertTrue(repository.dueOperations(15_100, 1000).any { it.operation.sessionId == session })
        repository.cancel(actor, operation, { 102 })
        assertTrue(JdbcSessionRepository(source).pendingWork(1000).any { it.operation.sessionId == session && it.cleanupPending && !it.admissionPending })
    }

    private fun freshSchema(): DataSource {
        val schema = "nearby_test_" + id().replace("-", "")
        source.connection.use { c -> c.createStatement().use { it.execute("CREATE SCHEMA $schema") } }
        return NearbyPostgresFixture.source(schema)
    }

    private fun id() = UUID.randomUUID().toString()
}

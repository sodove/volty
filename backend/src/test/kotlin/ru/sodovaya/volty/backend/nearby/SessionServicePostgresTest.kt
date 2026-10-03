package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.postgresql.ds.PGSimpleDataSource
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@Tag("postgres")
class SessionServicePostgresTest {
    private val source = PGSimpleDataSource().apply {
        setURL(checkNotNull(System.getenv("VOLTY_NEARBY_TEST_JDBC_URL")))
        user = "nearby_test"; password = "nearby-disposable-test-only"
    }
    private val actor = UUID.randomUUID().toString()
    private val operation = UUID.randomUUID().toString()
    private val destination = UUID.randomUUID().toString()
    private var now = 100L
    private var allowed = true
    private var tokensIssued = 0
    private val repository = JdbcSessionRepository(source)
    private val sfu = FakeSfu { now }
    private val service = SessionService(repository, sfu, { _, _ -> "room-generation".takeIf { allowed } },
        SessionCredentialIssuer { row ->
            tokensIssued++
            SessionCredentials("ws://test", "opaque-test-token", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
        }, { now })

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use { c ->
            c.prepareStatement("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES (?::uuid,?,'test','test',0)").use {
                it.setString(1, actor); it.setString(2, "$actor@example.invalid"); it.executeUpdate()
            }
        }
    }

    @Test fun cancelBeforeJoinNeverIssuesSfuRegistrationOrCredentials() {
        service.stop(actor, operation)
        assertIs<SessionAction.Ended>(service.join(actor, operation, destination))
        assertTrue(sfu.calls.isEmpty())
        assertEquals(0, tokensIssued)
    }

    @Test fun lostRegistrationAckReplaysExistingSessionWithOriginalDeadline() {
        sfu.loseRegisterAck = true
        assertIs<SessionAction.Pending>(service.join(actor, operation, destination))
        val session = repository.get(actor, operation)!!.operation.sessionId
        now = 5100
        val ready = assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
        assertEquals(session, ready.credentials.sessionId)
        assertEquals(30_100, ready.credentials.deadlineEpochMillis)
        assertEquals(1, tokensIssued)
    }

    @Test fun cancellationInsideDelayedRegistrationAckCannotIssueCredentials() {
        sfu.beforeRegisterAck = { service.stop(actor, operation) }
        assertIs<SessionAction.Ended>(service.join(actor, operation, destination))
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertNull(repository.currentClaim(actor))
        assertEquals(0, tokensIssued)
        assertTrue(sfu.calls.any { it.startsWith("register:") })
        assertTrue(sfu.calls.any { it.startsWith("revoke:") })
    }

    @Test fun successfulRegistrationAckAfterPendingDeadlineStillCannotIssueCredentials() {
        sfu.beforeRegisterAck = { now = 15_100 }
        assertIs<SessionAction.Ended>(service.join(actor, operation, destination))
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        assertEquals(0, tokensIssued)
        assertTrue(sfu.calls.any { it.startsWith("register:") })
        assertTrue(sfu.calls.any { it.startsWith("revoke:") })
    }

    @Test fun failedOldCleanupBlocksReplacementRegistrationUntilRetry() {
        service.join(actor, operation, destination)
        val next = UUID.randomUUID().toString()
        sfu.failCleanup = true
        assertIs<SessionAction.Pending>(service.join(actor, next, destination))
        val nextSession = repository.get(actor, next)!!.operation.sessionId
        assertFalse(sfu.calls.contains("register:$nextSession"))
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        sfu.failCleanup = false
        assertIs<SessionAction.Ready>(service.join(actor, next, destination))
        assertFalse(repository.get(actor, operation)!!.cleanupPending)
        assertTrue(sfu.calls.indexOfLast { it.startsWith("revoke:") } < sfu.calls.indexOf("register:$nextSession"))
        assertEquals(nextSession, repository.currentClaim(actor)!!.sessionId)
    }

    @Test fun cancellationDuringRenewAckCannotExtendLease() {
        service.join(actor, operation, destination)
        sfu.beforeRenewAck = { service.stop(actor, operation) }
        now = 200
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertNull(repository.currentClaim(actor))
        assertEquals(1, tokensIssued)
    }

    @Test fun expiredHeartbeatNeverIssuesRenewal() {
        service.join(actor, operation, destination)
        now = 30_100
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertFalse(sfu.calls.any { it.startsWith("renew:") })
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
    }

    @Test fun backendRestartReconcilesPendingOperationWithoutNewIdentity() {
        sfu.loseRegisterAck = true
        service.join(actor, operation, destination)
        val pending = repository.get(actor, operation)!!
        val restarted = SessionService(JdbcSessionRepository(source), sfu, { _, _ -> "room-generation" },
            SessionCredentialIssuer { error("Reconciler must not create participant credentials") }, { now })
        restarted.reconcileNext(actor)
        val active = repository.get(actor, operation)!!
        assertEquals(pending.operation.sessionId, active.operation.sessionId)
        assertEquals(OperationPhase.ACTIVE, active.operation.phase)
        assertEquals(0, tokensIssued)
    }

    @Test fun pendingDeadlineExpiresWithoutRegistrationRetry() {
        sfu.loseRegisterAck = true
        service.join(actor, operation, destination)
        now = 15_100
        service.reconcileNext(actor)
        assertEquals(OperationPhase.EXPIRED, repository.get(actor, operation)!!.operation.phase)
        assertEquals(1, sfu.calls.count { it.startsWith("register:") })
        assertEquals(0, tokensIssued)
    }

    @Test fun unconfiguredNodeCannotIssueSessionAndMembershipDenialCannotRegister() {
        sfu.available = false
        assertIs<SessionAction.Unavailable>(service.join(actor, operation, destination))
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertFalse(sfu.calls.any { it.startsWith("register:") })
        allowed = false
        assertIs<SessionAction.Denied>(service.join(actor, UUID.randomUUID().toString(), destination))
        assertEquals(0, tokensIssued)
    }

    @Test fun failedCredentialCreationCancelsAndCleansUpWithoutExposingFailureText() {
        val failing = SessionService(repository, sfu, { _, _ -> "room-generation" },
            SessionCredentialIssuer { error("Bearer private-creation-details") }, { now })
        val result = assertIs<SessionAction.Unavailable>(failing.join(actor, operation, destination))
        assertEquals(SfuFailure.UNAVAILABLE, result.reason)
        assertFalse(result.toString().contains("private-creation-details"))
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertFalse(repository.get(actor, operation)!!.cleanupPending)
        assertNull(repository.currentClaim(actor))
    }

    @Test fun changedNodeRejectsRenewalAndKeepsCleanupUnconfirmed() {
        service.join(actor, operation, destination)
        sfu.restart()
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertTrue(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(1, tokensIssued)
    }

    @Test fun managedSingletonRestartAllowsOnlyNewExplicitSession() {
        val managed = SessionService(repository, sfu, { _, _ -> "room-generation" }, SessionCredentialIssuer { row ->
            tokensIssued++
            SessionCredentials("ws://test", "opaque-test-token", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
        }, { now }, singletonOrigin = true)
        val old = assertIs<SessionAction.Ready>(managed.join(actor, operation, destination)).credentials.sessionId
        sfu.restart()
        assertIs<SessionAction.Ended>(managed.heartbeat(actor, operation))
        assertFalse(repository.get(actor, operation)!!.cleanupPending)
        assertEquals(1, sfu.calls.count { it.startsWith("register:") })
        assertEquals(1, tokensIssued)
        val next = UUID.randomUUID().toString()
        val fresh = assertIs<SessionAction.Ready>(managed.join(actor, next, destination))
        assertNotEquals(old, fresh.credentials.sessionId)
        assertEquals(2, tokensIssued)
        assertIs<SessionAction.Ended>(managed.join(actor, operation, destination))
        assertEquals(2, tokensIssued)
    }

    private class FakeSfu(private val now: () -> Long) : SfuLeaseClient {
        var node = "a".repeat(32)
        var available = true
        var failCleanup = false
        var loseRegisterAck = false
        var beforeRegisterAck: () -> Unit = {}
        var beforeRenewAck: () -> Unit = {}
        val calls = CopyOnWriteArrayList<String>()
        private val deadlines = ConcurrentHashMap<String, Long>()
        private val terminal = ConcurrentHashMap.newKeySet<String>()
        fun restart() { node = "b".repeat(32); deadlines.clear(); terminal.clear() }
        override fun inspect(room: String): SfuReply<SfuNodeStatus> {
            calls += "inspect:$room"
            return if (available) SfuReply.Accepted(SfuNodeStatus(node, true, true, singleNode = true)) else SfuReply.Rejected(SfuFailure.UNSUPPORTED)
        }
        override fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {
            calls += "register:$session"
            if (node != expectedNodeEpoch || terminal.contains(session)) return SfuReply.Rejected(SfuFailure.REJECTED)
            val receipt = LeaseReceipt(deadlines.computeIfAbsent(session) { now() + 30_000 })
            beforeRegisterAck()
            if (loseRegisterAck) { loseRegisterAck = false; return SfuReply.Rejected(SfuFailure.UNAVAILABLE) }
            return SfuReply.Accepted(receipt)
        }
        override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {
            calls += "renew:$session"
            if (node != expectedNodeEpoch || terminal.contains(session)) return SfuReply.Rejected(SfuFailure.REJECTED)
            val receipt = LeaseReceipt(now() + 30_000)
            deadlines[session] = receipt.conservativeDeadlineEpochMillis
            beforeRenewAck()
            return SfuReply.Accepted(receipt)
        }
        override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> {
            calls += "revoke:$session"
            if (node != expectedNodeEpoch) return SfuReply.Rejected(SfuFailure.REJECTED)
            if (failCleanup) return SfuReply.Rejected(SfuFailure.UNAVAILABLE)
            terminal += session
            return SfuReply.Accepted(Unit)
        }
    }
}

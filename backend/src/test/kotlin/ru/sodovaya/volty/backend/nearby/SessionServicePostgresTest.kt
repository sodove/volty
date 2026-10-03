package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

@Tag("postgres")
class SessionServicePostgresTest {
    private val source = NearbyPostgresFixture.source()
    private val actor = UUID.randomUUID().toString()
    private val operation = UUID.randomUUID().toString()
    private val destination = UUID.randomUUID().toString()
    private var now = 100L
    private var allowed = true
    private var tokensIssued = 0
    private var issuerDelayMillis = 0L
    private val repository = JdbcSessionRepository(source)
    private val sfu = FakeSfu { now }
    private val service = SessionService(repository, sfu, { _, _ -> "room-generation".takeIf { allowed } },
        SessionCredentialIssuer { row ->
            tokensIssued++
            now += issuerDelayMillis
            SessionCredentials("ws://test", "opaque-test-token", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
        }, { now })

    @Test fun replayStatusAndUnconfirmedHeartbeatExposeOnlyRemainingCredit() {
        val initial = assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
        assertEquals(30_000, initial.remainingMillis)
        now = 5_100
        val replay = assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
        assertEquals(initial.credentials.deadlineEpochMillis, replay.credentials.deadlineEpochMillis)
        assertEquals(25_000, replay.remainingMillis)
        assertEquals(25_000, assertIs<SessionAction.Confirmed>(service.status(actor, operation)).remainingMillis)
        assertEquals(25_000, assertIs<SessionAction.Confirmed>(service.heartbeat(actor, operation)).remainingMillis)
        assertEquals(2, tokensIssued)
        assertTrue(sfu.calls.none { it.startsWith("renew-connected:") })
    }

    @Test fun credentialIssuanceConsumesCreditBeforeAuthorizationResponse() {
        issuerDelayMillis = 5_000
        val result = assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
        assertEquals(25_000, result.remainingMillis)
        assertEquals(30_100, result.credentials.deadlineEpochMillis)
        assertEquals(1, tokensIssued)
    }

    @Test fun expiryDuringCredentialIssuanceNeverReturnsBearerOrCredit() {
        issuerDelayMillis = 30_000
        assertIs<SessionAction.Unavailable>(service.join(actor, operation, destination))
        assertEquals(1, tokensIssued)
        assertTrue(repository.get(actor, operation)!!.operation.terminal)
        assertNull(repository.currentClaim(actor))
        assertTrue(sfu.calls.any { it.startsWith("revoke:") })
    }

    @Test fun endpointSessionOwnershipAndOldDisconnectCannotAffectReplacement() {
        val endpoints = SessionEndpointService(service, repository)
        val oldSession = assertIs<SessionAction.Ready>(endpoints.join(actor, operation, destination)).credentials.sessionId
        val replacementOperation = UUID.randomUUID().toString()
        val replacementSession = assertIs<SessionAction.Ready>(endpoints.join(actor, replacementOperation, destination, replace = true)).credentials.sessionId
        val before = sfu.calls.toList()
        assertNull(endpoints.heartbeat(UUID.randomUUID().toString(), replacementSession))
        assertNull(endpoints.disconnect(UUID.randomUUID().toString(), replacementSession))
        assertEquals(before, sfu.calls)
        assertIs<SessionAction.Ended>(endpoints.disconnect(actor, oldSession))
        assertEquals(replacementSession, repository.currentClaim(actor)!!.sessionId)
        val renews = sfu.calls.count { it.startsWith("renew:") }
        assertIs<SessionAction.Ended>(endpoints.heartbeat(actor, oldSession))
        assertEquals(renews, sfu.calls.count { it.startsWith("renew:") })
    }

    @Test fun endpointStatusExpiresAndCleansSessionWithoutIssuingCredentials() {
        val endpoints = SessionEndpointService(service, repository)
        assertNull(endpoints.status(actor, operation))
        service.join(actor, operation, destination)
        assertIs<SessionAction.Confirmed>(endpoints.status(actor, operation))
        assertEquals(1, tokensIssued)
        now = repository.get(actor, operation)!!.operation.deadlineEpochMillis!!
        val ended = assertIs<SessionAction.Ended>(endpoints.status(actor, operation))
        assertEquals(OperationPhase.EXPIRED, ended.state.operation.phase)
        assertFalse(ended.state.cleanupPending)
        assertNull(repository.currentClaim(actor))
        assertEquals(1, tokensIssued)
        assertTrue(sfu.calls.any { it.startsWith("revoke:") })
    }

    @Test fun newOperationWithoutExplicitReplacementDoesNotRegisterOrRevoke() {
        val first = assertIs<SessionAction.Ready>(service.join(actor, operation, destination))
        val next = UUID.randomUUID().toString()
        val registers = sfu.calls.count { it.startsWith("register:") }
        val revokes = sfu.calls.count { it.startsWith("revoke:") }
        assertEquals(SessionDenial.BUSY, assertIs<SessionAction.Denied>(service.join(actor, next, destination)).reason)
        assertEquals(registers, sfu.calls.count { it.startsWith("register:") })
        assertEquals(revokes, sfu.calls.count { it.startsWith("revoke:") })
        assertEquals(1, tokensIssued)
        assertNull(repository.get(actor, next))
        assertEquals(first.credentials.sessionId, repository.currentClaim(actor)!!.sessionId)
        assertIs<SessionAction.Ready>(service.join(actor, next, destination, replace = true))
        assertEquals(2, tokensIssued)
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
    }

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
        assertIs<SessionAction.Pending>(service.join(actor, next, destination, replace = true))
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
        sfu.media = SfuParticipantObservation("PA_current", SfuParticipantState.ACTIVE)
        sfu.beforeRenewAck = { service.stop(actor, operation) }
        now = 200
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertEquals(OperationPhase.CANCELLED, repository.get(actor, operation)!!.operation.phase)
        assertNull(repository.currentClaim(actor))
        assertEquals(1, tokensIssued)
        assertTrue(sfu.calls.any { it.startsWith("renew-connected:") }, "The conditional renewal path was issued")
    }

    @Test fun registeredOnlyHeartbeatCannotExtendInitialAdmissionWindow() {
        service.join(actor, operation, destination)
        now = 200
        val result = assertIs<SessionAction.Confirmed>(service.heartbeat(actor, operation))
        assertEquals(30_100, result.deadlineEpochMillis)
        assertTrue(sfu.calls.any { it.startsWith("observe:") })
        assertFalse(sfu.calls.any { it.startsWith("renew:") || it.startsWith("renew-connected:") })
        assertEquals(1, tokensIssued)
    }

    @Test fun joinedSignallingCannotKeepNeverConnectedMediaAliveForever() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_joined", SfuParticipantState.JOINED)
        for (at in listOf(1000L, 15000L, 25000L)) {
            now = at
            assertEquals(30_100, assertIs<SessionAction.Confirmed>(service.heartbeat(actor, operation)).deadlineEpochMillis)
        }
        assertEquals(3, sfu.calls.count { it.startsWith("observe:") })
        assertFalse(sfu.calls.any { it.startsWith("renew:") || it.startsWith("renew-connected:") })
        now = 30_100
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertNull(repository.currentClaim(actor))
    }

    @Test fun missingJoinedHookIsRecoveredFromCurrentActiveTransportBeforeConditionalRenewal() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_live", SfuParticipantState.ACTIVE)
        now = 200
        assertEquals(30_200, assertIs<SessionAction.Confirmed>(service.heartbeat(actor, operation)).deadlineEpochMillis)
        assertEquals("PA_live", repository.get(actor, operation)!!.media.participantSid)
        assertEquals(MediaPresence.CONNECTED, JdbcRosterRepository(source).snapshot(destination).peers.single().mediaPresence)
        assertEquals(1, sfu.calls.count { it.startsWith("renew-connected:") })
        assertFalse(sfu.calls.any { it.startsWith("renew:") })
        assertEquals(1, tokensIssued)
    }

    @Test fun oldObservedSidRejectedDuringRenewalDoesNotCancelReplacementTransport() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_old", SfuParticipantState.ACTIVE)
        sfu.beforeConnectedRenewValidation = { sfu.media = SfuParticipantObservation("PA_new", SfuParticipantState.JOINED) }
        now = 200
        assertIs<SessionAction.Unavailable>(service.heartbeat(actor, operation))
        assertEquals(OperationPhase.ACTIVE, repository.get(actor, operation)!!.operation.phase)
        assertEquals(30_100, repository.get(actor, operation)!!.operation.deadlineEpochMillis)
        assertEquals(repository.get(actor, operation)!!.operation.sessionId, repository.currentClaim(actor)!!.sessionId)
        assertFalse(sfu.calls.any { it.startsWith("revoke:") })
        sfu.beforeConnectedRenewValidation = {}
        sfu.media = SfuParticipantObservation("PA_new", SfuParticipantState.ACTIVE)
        assertIs<SessionAction.Confirmed>(service.heartbeat(actor, operation))
        assertEquals("PA_new", repository.get(actor, operation)!!.media.participantSid)
    }

    @Test fun cancellationInsideObservationAckCannotPublishMediaOrIssueRenewal() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_live", SfuParticipantState.ACTIVE)
        sfu.beforeObserveAck = { service.stop(actor, operation) }
        assertIs<SessionAction.Ended>(service.heartbeat(actor, operation))
        assertTrue(sfu.calls.any { it.startsWith("observe:") })
        assertFalse(sfu.calls.any { it.startsWith("renew-connected:") })
        assertNull(repository.get(actor, operation)!!.media.participantSid)
        assertNull(repository.currentClaim(actor))
    }

    @Test fun competingObservationWinsOverOldNetworkReplyWithoutRenewingItsOldSid() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_old", SfuParticipantState.ACTIVE)
        sfu.beforeObserveAck = {
            val row = repository.get(actor, operation)!!
            assertNull(repository.observeMedia(actor, operation, row.operation.sessionId!!, row.sfuEpoch!!,
                row.media.observationVersion, SfuParticipantObservation("PA_new", SfuParticipantState.ACTIVE), { now }).rejection)
        }
        assertIs<SessionAction.Unavailable>(service.heartbeat(actor, operation))
        assertEquals("PA_new", repository.get(actor, operation)!!.media.participantSid)
        assertFalse(sfu.calls.any { it.startsWith("renew-connected:") })
    }

    @Test fun failedObservationCannotRenewUsingPreviouslyStoredActiveMedia() {
        service.join(actor, operation, destination)
        sfu.media = SfuParticipantObservation("PA_live", SfuParticipantState.ACTIVE)
        now = 200
        service.heartbeat(actor, operation)
        assertEquals(SfuParticipantState.ACTIVE, repository.get(actor, operation)!!.media.state)
        val issued = sfu.calls.count { it.startsWith("renew-connected:") }
        sfu.observationFailure = SfuFailure.TIMEOUT
        now = 300
        assertEquals(SfuFailure.TIMEOUT, assertIs<SessionAction.Unavailable>(service.heartbeat(actor, operation)).reason)
        assertEquals(issued, sfu.calls.count { it.startsWith("renew-connected:") })
        assertEquals(30_200, repository.get(actor, operation)!!.operation.deadlineEpochMillis)
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

    private class FakeSfu(private val now: () -> Long) : SfuLeaseClient, SfuConnectedLeaseClient {
        var node = "a".repeat(32)
        var available = true
        var failCleanup = false
        var loseRegisterAck = false
        var beforeRegisterAck: () -> Unit = {}
        var beforeRenewAck: () -> Unit = {}
        var beforeObserveAck: () -> Unit = {}
        var beforeConnectedRenewValidation: () -> Unit = {}
        var media = SfuParticipantObservation(null, null)
        var observationFailure: SfuFailure? = null
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
        override fun observeParticipant(room: String, session: String, expectedNodeEpoch: String): SfuReply<SfuParticipantObservation> {
            calls += "observe:$session"
            if (node != expectedNodeEpoch || terminal.contains(session)) return SfuReply.Rejected(SfuFailure.REJECTED)
            observationFailure?.let { return SfuReply.Rejected(it) }
            val captured = media
            beforeObserveAck()
            return SfuReply.Accepted(captured)
        }
        override fun renewConnected(room: String, session: String, expectedNodeEpoch: String, expectedParticipantSid: String): SfuReply<LeaseReceipt> {
            calls += "renew-connected:$session"
            beforeConnectedRenewValidation()
            if (node != expectedNodeEpoch || terminal.contains(session) || media.participantSid != expectedParticipantSid || media.state != SfuParticipantState.ACTIVE) {
                return SfuReply.Rejected(SfuFailure.REJECTED)
            }
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

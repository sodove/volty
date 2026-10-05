package ru.sodovaya.volty.backend.nearby

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import ru.sodovaya.volty.backend.SchemaMigrator
import java.util.UUID
import kotlin.test.*

@Tag("postgres")
class JdbcDirectCallServiceTest {
    private fun id() = UUID.randomUUID().toString()
    private val caller = id(); private val callee = id(); private val outsider = id()
    private val call = id(); private val key = id(); private val callerOperation = id(); private val calleeOperation = id()
    private val schema = "call_orchestration_" + id().replace("-", "")
    private val source = NearbyPostgresFixture.source().let {base ->
        base.connection.use {c -> c.createStatement().use {it.execute("CREATE SCHEMA $schema")}}
        NearbyPostgresFixture.source(schema)
    }
    private var at = 100L; private var tokens = 0
    private val rings = JdbcDirectCallRingingRepository(source)
    private val media = JdbcDirectCallMediaRepository(source)
    private val repository = JdbcSessionRepository(source, JdbcVoiceAccess {at})
    private val sfu = RecordingSfu()
    private val issuer = SessionCredentialIssuer {row ->
        tokens++
        SessionCredentials("ws://test", "opaque", row.operation.sessionId!!, row.sfuEpoch!!.roomEpoch, row.operation.deadlineEpochMillis!!)
    }
    private fun voiceFor(sessions: SessionRepository) = SessionService(sessions, sfu, {_, _ -> error("unexpected channel resolver")},
        issuer, {at}, resolveTypedRoom = {_, destination, kind ->
            "volty-call-$destination".takeIf {kind == VoiceDestinationKind.DIRECT_CALL}
        })
    private val voice = voiceFor(repository)
    private val service = DirectCallService(rings, media, repository, voice, sfu, {at})

    @BeforeEach fun prepare() {
        SchemaMigrator(source).migrate()
        source.connection.use {c -> c.createStatement().use {s ->
            for (actor in listOf(caller, callee, outsider))
                s.execute("INSERT INTO users(id,email,password_hash,display_name,created_at) VALUES ('$actor','$actor@example.invalid','test','test',0)")
            s.execute("INSERT INTO friendships VALUES ('${id()}','$caller','$callee','ACCEPTED',0)")
        }}
    }
    private fun ring() {
        assertEquals(DirectCallPhase.RINGING, service.create(caller, callee, call, key, callerOperation).call?.phase)
        assertTrue(sfu.calls.isEmpty()); assertEquals(0, tokens)
    }

    @Test fun ringingStatusEarnsOnlyRemainingOriginalServerDeadlineCredit() {
        ring(); at = 20_100
        val own = service.status(caller, call); val incoming = service.status(callee, call)
        assertEquals(DirectCallPhase.RINGING, own.call?.phase)
        assertEquals(25_000, own.remainingRingMillis)
        assertEquals(own.remainingRingMillis, incoming.remainingRingMillis)
        assertEquals(45_100, incoming.call?.ringDeadlineMillis)
        assertNull(service.status(outsider, call).remainingRingMillis)
        assertTrue(sfu.calls.isEmpty()); assertEquals(0, tokens)
    }

    @Test fun elapsedAndRevokedRingsNeverKeepNotificationCredit() {
        ring(); at = 45_100
        val expired = service.status(callee, call)
        assertEquals(DirectCallPhase.MISSED, expired.call?.phase); assertNull(expired.remainingRingMillis)
        assertNull(repository.currentClaim(caller)); assertTrue(sfu.calls.isEmpty())
    }

    @Test fun acceptedAndFriendshipRevokedStatusHaveNoRingingCredit() {
        ring(); at = 101
        service.accept(callee, call, calleeOperation, 0)
        assertEquals(DirectCallPhase.ACCEPTED, service.status(callee, call).call?.phase)
        assertNull(service.status(callee, call).remainingRingMillis)
        source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships")}}
        val revoked = service.status(callee, call)
        assertTrue(assertNotNull(revoked.call).terminal); assertNull(revoked.remainingRingMillis)
    }

    @Test fun acceptedOriginalPairSupportsCalleeThenCallerAdmissionWithShortestPeerCredit() {
        ring(); at = 101
        val accepted = service.accept(callee, call, calleeOperation, 0)
        assertEquals(DirectCallPhase.ACCEPTED, accepted.decision.call?.phase)
        val calleeReady = assertIs<SessionAction.Ready>(accepted.session)
        assertEquals(15_101, calleeReady.credentials.deadlineEpochMillis)
        assertEquals(repository.get(callee, calleeOperation)!!.operation.sessionId, calleeReady.credentials.sessionId)
        at = 102
        val callerReady = assertIs<SessionAction.Ready>(service.resume(caller, call, callerOperation).session)
        assertEquals(repository.get(caller, callerOperation)!!.operation.sessionId, callerReady.credentials.sessionId)
        assertEquals("volty-call-$call", callerReady.credentials.roomEpoch)
        assertEquals(30_101, callerReady.credentials.deadlineEpochMillis)
        assertEquals(2, tokens)
    }

    @Test fun cancellationDuringInspectCannotPrepareOrRegisterLateAcceptedPair() {
        ring()
        sfu.beforeInspectAck = {media.stop(caller, call, {at})}
        val result = service.accept(callee, call, calleeOperation, 0)
        assertTrue(assertNotNull(result.decision.call).terminal); assertNull(result.session)
        assertTrue(sfu.calls.none {it.startsWith("register:")}); assertEquals(0, tokens)
        assertNull(repository.get(caller, callerOperation)); assertNull(repository.get(callee, calleeOperation))
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
    }

    @Test fun outsiderAndCallerAcceptanceCannotInspectOrPrepareMedia() {
        ring()
        assertEquals(DirectCallRejection.ACCESS_DENIED, service.accept(outsider, call, id(), 0).decision.rejection)
        assertEquals(DirectCallRejection.ACCESS_DENIED, service.accept(caller, call, id(), 0).decision.rejection)
        assertTrue(sfu.calls.isEmpty()); assertEquals(0, tokens)
        assertEquals(DirectCallPhase.RINGING, service.status(caller, call).call?.phase)
        assertNull(repository.get(caller, callerOperation))
    }

    @Test fun fullStopAttemptsBothOriginalMediaAndKeepsUnknownCleanupPending() {
        ring(); at = 101
        assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        assertIs<SessionAction.Ready>(service.resume(caller, call, callerOperation).session)
        val originals = listOf(repository.get(caller, callerOperation)!!, repository.get(callee, calleeOperation)!!)
        sfu.failCleanup = true
        val stopped = service.stop(caller, call)
        assertEquals(DirectCallOutcome.CALLER_ENDED, stopped.call?.outcome)
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
        assertEquals(originals.map {"revoke:${it.operation.sessionId}"}.toSet(), sfu.calls.filter {it.startsWith("revoke:")}.toSet())
        assertTrue(repository.get(caller, callerOperation)!!.cleanupPending)
        assertTrue(repository.get(callee, calleeOperation)!!.cleanupPending)
        sfu.failCleanup = false
        assertEquals(DirectCallOutcome.CALLER_ENDED, service.stop(callee, call).call?.outcome)
        assertFalse(repository.get(caller, callerOperation)!!.cleanupPending)
        assertFalse(repository.get(callee, calleeOperation)!!.cleanupPending)
        assertEquals(2, tokens)
    }

    @Test fun acceptedReplayKeepsOriginalEpochAndSessionWithoutAnotherInspect() {
        ring(); at = 101
        val first = assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        sfu.node = "replacement-node"
        val replay = assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        assertEquals(first.credentials.sessionId, replay.credentials.sessionId)
        assertEquals(first.credentials.deadlineEpochMillis, replay.credentials.deadlineEpochMillis)
        assertEquals(1, sfu.calls.count {it == "inspect"})
        assertEquals(1, sfu.calls.count {it.startsWith("register:")})
    }

    @Test fun failedInspectReplyStillReportsCancellationCommittedWhileRequestWasInFlight() {
        ring()
        sfu.beforeInspectAck = {media.stop(caller, call, {at})}
        sfu.inspectFailure = SfuFailure.UNAVAILABLE
        val result = service.accept(callee, call, calleeOperation, 0)
        assertTrue(assertNotNull(result.decision.call).terminal)
        assertEquals(DirectCallOutcome.CALLER_CANCELLED, result.decision.call?.outcome)
        assertNull(result.session); assertEquals(0, tokens)
        assertTrue(sfu.calls.none {it.startsWith("register:")})
    }

    @Test fun acceptedReplayCannotChangeOriginalCalleeOperationOrIssueAnotherBearer() {
        ring(); at = 101
        assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        val original = repository.currentClaim(callee); val before = sfu.calls.toList()
        val replay = service.accept(callee, call, id(), 0)
        assertEquals(DirectCallRejection.IDENTITY_MISMATCH, replay.decision.rejection)
        assertNull(replay.session); assertEquals(1, tokens)
        assertEquals(original, repository.currentClaim(callee)); assertEquals(before, sfu.calls)
    }

    @Test fun committedStopAfterActualSigningSuppressesReadyBeforeReturningAction() {
        ring(); at = 101
        val stoppedAfterIssuance = object : SessionRepository by repository {
            override fun <T : Any> withActiveSession(actor: String, operation: String, now: () -> Long, producer: (SessionJournalEntry) -> T): T? {
                val issued = repository.withActiveSession(actor, operation, now, producer)
                // The actual CPU producer/SQL transaction finished; no nested pair lock.
                if (issued != null) media.stop(caller, call, {at})
                return issued
            }
        }
        val racing = DirectCallService(rings, media, stoppedAfterIssuance, voiceFor(stoppedAfterIssuance), sfu, {at})
        val result = racing.accept(callee, call, calleeOperation, 0)
        assertEquals(1, tokens) // Signing path really ran before stop, not a precondition denial.
        assertEquals(DirectCallOutcome.CALLER_ENDED, result.decision.call?.outcome)
        assertIs<SessionAction.Ended>(result.session)
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
    }

    @Test fun oldCallCleanupCannotRemoveNewerOriginalParticipantsChannelClaim() {
        ring(); at = 101
        assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        assertIs<SessionAction.Ready>(service.resume(caller, call, callerOperation).session)
        media.stop(caller, call, {at}) // SQL stop first, pending old descriptors still need SFU ACK.
        val channel = assertIs<CommunityWriteResult.Accepted>(JdbcCommunityRepository(source).create(callee, id(), "New ride", now = {102})).snapshot.channels.single().id
        val operation = id(); val session = id(); val epoch = SfuEpoch("volty-channel-$channel", "node")
        assertNull(repository.begin(callee, operation, channel, session, epoch, {102}).rejection)
        assertNull(repository.admit(callee, operation, session, epoch, {102}, 30_102).rejection)
        service.stop(caller, call)
        assertEquals(SessionClaim(operation, session, epoch), repository.currentClaim(callee))
        assertEquals(OperationPhase.ACTIVE, repository.get(callee, operation)!!.operation.phase)
        assertTrue(sfu.calls.none {it == "revoke:$session"})
    }

    @Test fun transientInspectFailureLeavesOriginalRingForExplicitRetry() {
        ring(); sfu.inspectFailure = SfuFailure.UNAVAILABLE
        val failed = service.accept(callee, call, calleeOperation, 0)
        assertEquals(SfuFailure.UNAVAILABLE, failed.failure)
        assertEquals(DirectCallPhase.RINGING, failed.decision.call?.phase)
        assertNull(repository.get(caller, callerOperation)); assertEquals(0, tokens)
        sfu.inspectFailure = null; at = 101
        assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        assertEquals(2, sfu.calls.count {it == "inspect"})
    }

    @Test fun friendshipRevokedDuringInspectCannotPrepareOrRegisterAcceptedMedia() {
        ring()
        sfu.beforeInspectAck = {source.connection.use {c -> c.createStatement().use {it.execute("DELETE FROM friendships")}}}
        val result = service.accept(callee, call, calleeOperation, 0)
        assertEquals(DirectCallOutcome.FRIENDSHIP_REVOKED, result.decision.call?.outcome)
        assertNull(result.session); assertEquals(0, tokens)
        assertTrue(sfu.calls.none {it.startsWith("register:")})
        assertNull(repository.get(caller, callerOperation)); assertNull(repository.get(callee, calleeOperation))
    }

    @Test fun oneUnexpectedCleanupFailureDoesNotBlockOtherParticipantOrHideCommittedStop() {
        ring(); at = 101
        assertIs<SessionAction.Ready>(service.accept(callee, call, calleeOperation, 0).session)
        assertIs<SessionAction.Ready>(service.resume(caller, call, callerOperation).session)
        val originals = listOf(repository.get(caller, callerOperation)!!, repository.get(callee, calleeOperation)!!)
        sfu.throwNextCleanup = true
        val result = runCatching {service.stop(caller, call)}
        assertTrue(result.isSuccess, "Committed pair stop must remain observable when one transport cleanup throws")
        assertEquals(DirectCallOutcome.CALLER_ENDED, result.getOrThrow().call?.outcome)
        assertEquals(originals.map {"revoke:${it.operation.sessionId}"}.toSet(), sfu.calls.filter {it.startsWith("revoke:")}.toSet())
        val remaining = listOf(repository.get(caller, callerOperation)!!, repository.get(callee, calleeOperation)!!)
        assertEquals(1, remaining.count {it.cleanupPending})
        assertNull(repository.currentClaim(caller)); assertNull(repository.currentClaim(callee))
    }

    private inner class RecordingSfu : SfuLeaseClient {
        var node = "node"; var beforeInspectAck: () -> Unit = {}; var failCleanup = false
        var inspectFailure: SfuFailure? = null
        var throwNextCleanup = false
        val calls = mutableListOf<String>()
        override fun inspect(room: String): SfuReply<SfuNodeStatus> {
            assertEquals("volty-call-$call", room); calls += "inspect"
            val status = SfuNodeStatus(node, true, true, true); beforeInspectAck()
            inspectFailure?.let {return SfuReply.Rejected(it)}
            return SfuReply.Accepted(status)
        }
        override fun register(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> {
            assertEquals("volty-call-$call", room); assertEquals(node, expectedNodeEpoch)
            calls += "register:$session"; return SfuReply.Accepted(LeaseReceipt(at + 30_000))
        }
        override fun renew(room: String, session: String, expectedNodeEpoch: String): SfuReply<LeaseReceipt> = error("unobserved renewal")
        override fun revoke(room: String, session: String, expectedNodeEpoch: String): SfuReply<Unit> {
            assertEquals("volty-call-$call", room); calls += "revoke:$session"
            if (throwNextCleanup) {throwNextCleanup = false; error("deliberate transport cleanup failure")}
            return if (failCleanup) SfuReply.Rejected(SfuFailure.UNAVAILABLE) else SfuReply.Accepted(Unit)
        }
    }
}

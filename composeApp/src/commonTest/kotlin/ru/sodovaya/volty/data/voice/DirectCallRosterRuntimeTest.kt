package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallRosterRuntimeTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val tag = DirectCallRosterTag(call, peer, 7)
    private fun reply(clock: Long, ordinal: Long) = DirectCallRosterReply.Received(DirectCallRosterObservation(tag, ordinal, listOf(
        DirectCallRosterPeer(actor, VoiceSessionId("40000000-0000-0000-0000-000000000004"), VoiceRosterPhase.CONNECTING, null, false),
        DirectCallRosterPeer(peer, VoiceSessionId("50000000-0000-0000-0000-000000000005"), VoiceRosterPhase.CONNECTED, "actual-sid", true)),
        VoiceLeaseRequestId(ordinal), clock, clock, 100000 + clock, clock + 1000))

    @Test fun pulsesExpirePairBeforeNextReadAndActualFreshReadRestoresPresence() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val pulses = MutableSharedFlow<Unit>()
        var clock = 100L; var reads = 0
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {assertEquals(tag, it); reply(clock, (++reads).toLong())}, pulses, {clock})
        try {
            runCurrent(); assertEquals(1, reads); assertEquals(2, assertNotNull(runtime.state.value.peers).size)
            assertEquals(1, runtime.state.value.connectedCount)
            clock = 1100; pulses.emit(Unit); runCurrent()
            assertEquals(1, reads); assertNull(runtime.state.value.peers); assertNull(runtime.state.value.connectedCount)
            clock = 5100; pulses.emit(Unit); runCurrent()
            assertEquals(2, reads); assertEquals(2, assertNotNull(runtime.state.value.peers).size)
            assertEquals(1, runtime.state.value.connectedCount)
        } finally {runtime.close(); root.cancel(); runCurrent()}
    }

    @Test fun heldProducerCoalescesReadsAndCannotRestoreAfterCloseWhileStillCounted() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val pulses = MutableSharedFlow<Unit>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {reads++; withContext(NonCancellable) {entered.complete(Unit); release.await(); reply(100, 1)}}, pulses, {100})
        try {
            runCurrent(); assertTrue(entered.isCompleted); assertEquals(1, runtime.readInFlight.value)
            assertFalse(runtime.refresh()); assertEquals(1, reads)
            runtime.close(); runCurrent()
            assertTrue(runtime.state.value.retired); assertNull(runtime.state.value.peers)
            assertEquals(1, runtime.readInFlight.value); assertTrue(root.isActive)
            release.complete(Unit); runCurrent()
            assertEquals(0, runtime.readInFlight.value); assertTrue(runtime.state.value.retired); assertNull(runtime.state.value.connectedCount)
        } finally {release.complete(Unit); runtime.close(); root.cancel(); runCurrent()}
    }

    @Test fun accessDenialPermanentlyClosesWithoutCancellingBorrowedParent() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); var reads = 0
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {reads++; DirectCallRosterReply.Failed(VoiceRosterFailure.ACCESS_DENIED)}, MutableSharedFlow(), {100})
        try {
            runCurrent(); assertEquals(1, reads); assertTrue(runtime.state.value.retired)
            assertEquals(VoiceRosterUnknown.ACCESS_DENIED, runtime.state.value.unknown)
            assertNull(runtime.state.value.peers); assertFalse(runtime.refresh()); assertTrue(root.isActive)
        } finally {runtime.close(); root.cancel(); runCurrent()}
    }

    @Test fun backwardClockWithdrawsExistingPresenceAndNeverIssuesAnotherRead() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val pulses = MutableSharedFlow<Unit>()
        var clock = 100L; var reads = 0
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {reply(clock, (++reads).toLong())}, pulses, {clock})
        try {
            runCurrent(); assertEquals(2, assertNotNull(runtime.state.value.peers).size)
            clock = 99; pulses.emit(Unit); runCurrent()
            assertTrue(runtime.state.value.retired); assertEquals(VoiceRosterUnknown.CLOCK_RESET, runtime.state.value.unknown)
            assertNull(runtime.state.value.connectedCount); assertEquals(1, reads)
        } finally {runtime.close(); root.cancel(); runCurrent()}
    }

    @Test fun parentCancellationClearsEarnedRowsWhileIgnoredCancellationReadRemainsCounted() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val pulses = MutableSharedFlow<Unit>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0; var clock = 100L
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {
                if (++reads == 2) withContext(NonCancellable) {entered.complete(Unit); release.await()}
                reply(clock, reads.toLong())
            }, pulses, {clock})
        try {
            runCurrent(); assertEquals(2, assertNotNull(runtime.state.value.peers).size)
            clock = 5100; pulses.emit(Unit); runCurrent(); assertTrue(entered.isCompleted)
            root.cancel(); runCurrent()
            assertTrue(runtime.state.value.retired); assertNull(runtime.state.value.peers); assertEquals(1, runtime.readInFlight.value)
            release.complete(Unit); runCurrent()
            assertEquals(0, runtime.readInFlight.value); assertNull(runtime.state.value.connectedCount); assertEquals(2, reads)
        } finally {release.complete(Unit); runtime.close(); root.cancel(); runCurrent()}
    }

    @Test fun completedPulseSourceWithdrawsAlreadyPublishedPresence() = runTest {
        val root = Job(); val dispatcher = StandardTestDispatcher(testScheduler); val finish = CompletableDeferred<Unit>()
        var reads = 0
        val runtime = DirectCallRosterRuntime(CoroutineScope(root + dispatcher), dispatcher, tag, actor,
            DirectCallRosterPort {reply(100, (++reads).toLong())}, flow<Unit> {finish.await()}, {100})
        try {
            runCurrent(); assertEquals(2, assertNotNull(runtime.state.value.peers).size)
            finish.complete(Unit); runCurrent()
            assertTrue(runtime.state.value.retired); assertNull(runtime.state.value.connectedCount)
            assertEquals(VoiceRosterUnknown.CLOSED, runtime.state.value.unknown); assertFalse(runtime.refresh())
            assertEquals(1, reads); assertTrue(root.isActive)
        } finally {finish.complete(Unit); runtime.close(); root.cancel(); runCurrent()}
    }
}

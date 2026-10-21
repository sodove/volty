package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingGpsDemandTest {
    @Test fun routeObservationDoesNotAllocateAndReportsActualCaptureRatherThanSubmittedLaunch() = runTest {
        val f = Fixture()
        try {
            assertNull(f.demand.locationStates.first())
            assertTrue(f.sessions.isEmpty()); assertTrue(f.services.isEmpty())
            f.demand.setEnabled(true)
            val session = f.sessions.single()
            val published = assertNotNull(f.demand.locationStates.first())
            assertSame(assertNotNull(f.recording).identity, published.recording.identity)
            val starting = published.state
            assertTrue(starting.wanted); assertFalse(starting.capturing)
            session.state.value = session.state.value.copy(capturing = true)
            assertTrue(assertNotNull(f.demand.locationStates.first()).state.capturing)
            f.demand.setEnabled(false); assertNull(f.demand.locationStates.first())
        } finally { f.demand.setEnabled(false) }
    }
    @Test fun failedRetirementKeepsOriginalRouteObservationUntilActualCleanupAcknowledges() = runTest {
        val f = Fixture()
        try {
            f.demand.setEnabled(true); val session = f.sessions.single(); session.release = false
            assertFailsWith<IllegalStateException> { f.demand.setEnabled(false) }
            assertEquals(session.state.value, assertNotNull(f.demand.locationStates.first()).state)
            assertFalse(assertNotNull(f.demand.locationStates.first()).state.wanted)
            assertEquals(1, session.closes); assertEquals(1, f.sessions.size)
            session.release = true; f.demand.setEnabled(false)
            assertEquals(2, session.closes); assertNull(f.demand.locationStates.first())
        } finally { f.sessions.forEach { it.release = true }; f.demand.setEnabled(false) }
    }
    @Test fun retainedRouteStatusCannotBeRelabeledAsAReplacementRecordingEvenWithSameRideId() = runTest {
        val f = Fixture(); val original = assertNotNull(f.recording)
        try {
            f.demand.setEnabled(true); val session = f.sessions.single(); session.release = false
            f.recording = CapturedHistoryGpsRecording(original.rideId, original.generation + 1, Any())
            assertFailsWith<IllegalStateException> { f.demand.setEnabled(true) }
            val retained = assertNotNull(f.demand.locationStates.first())
            assertSame(original.identity, retained.recording.identity)
            assertEquals(original.generation, retained.recording.generation)
            assertEquals(1, f.sessions.size)
            session.release = true; f.demand.setEnabled(true)
            val replacement = assertNotNull(f.demand.locationStates.first())
            assertSame(assertNotNull(f.recording).identity, replacement.recording.identity)
            assertEquals(2, f.sessions.size)
        } finally { f.sessions.forEach { it.release = true }; f.demand.setEnabled(false) }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begun = mutableListOf<String>()
        val ended = mutableListOf<String?>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begun += ride.id }
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { ended += rideId }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Session : HistoryRecordingLocationSession {
        override val state = MutableStateFlow(HistoryLocationCaptureState())
        var starts = 0
        var closes = 0
        var release = true
        var gate: CompletableDeferred<Unit>? = null
        var startFailure = false
        val startError = IllegalStateException("launch submission")
        override fun start(): Deferred<Boolean> {
            starts++
            if (startFailure) throw startError
            return CompletableDeferred<Boolean>() // Startup ACK intentionally never arrives.
        }
        override suspend fun close(): Boolean {
            closes++; state.value = state.value.copy(wanted = false, capturing = false)
            gate?.await()
            return release
        }
    }
    private class Fixture {
        var recording: CapturedHistoryGpsRecording? = CapturedHistoryGpsRecording("ride-1", 1, Any())
        val services = mutableListOf<Boolean>()
        val sessions = mutableListOf<Session>()
        val keys = mutableListOf<CapturedHistoryGpsRecording>()
        var serviceFailure = false
        var onService: () -> Unit = {}
        var factory: () -> Session = { Session() }
        val demand = HistoryRecordingGpsDemand({ recording }, {
            services += it
            if (it) onService()
            if (it && serviceFailure) error("service submission")
        }, { captured -> keys += captured; factory().also { sessions += it } })
    }

    @Test fun asynchronousStartupDoesNotBlockAndSameRecordingDoesNotAllocateTwice() = runTest {
        val f = Fixture()
        f.demand.setEnabled(true)
        assertEquals(listOf(true), f.services)
        assertEquals(1, f.sessions.size); assertEquals(1, f.sessions.single().starts)
        val first = assertNotNull(f.recording)
        // Public capture wrappers can be fresh objects around the same opaque owner.
        f.recording = CapturedHistoryGpsRecording(first.rideId, first.generation, first.identity)
        f.demand.setEnabled(true)
        assertEquals(listOf(true, true), f.services)
        assertEquals(1, f.sessions.size); assertEquals(1, f.sessions.single().starts)
        f.demand.setEnabled(false)
        assertEquals(1, f.sessions.single().closes)
        assertEquals(listOf(true, true, false), f.services)
    }

    @Test fun newRecordingWaitsForOriginalCleanupBeforeAllocatingItsOwnSession() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single(); val gate = CompletableDeferred<Unit>(); old.gate = gate
        f.recording = CapturedHistoryGpsRecording("ride-2", 2, Any())
        val changed = backgroundScope.async { f.demand.setEnabled(true) }
        try {
            runCurrent(); assertEquals(1, old.closes)
            assertEquals(1, f.sessions.size); assertFalse(changed.isCompleted)
            gate.complete(Unit); changed.await()
            assertEquals(listOf("ride-1", "ride-2"), f.keys.map { it.rideId })
            assertEquals(1, f.sessions.last().starts)
        } finally { gate.complete(Unit); changed.cancelAndJoin(); f.demand.setEnabled(false) }
    }

    @Test fun failedCleanupRetainsOriginalAndBlocksReplacementUntilRetryAcknowledgement() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single(); old.release = false
        f.recording = CapturedHistoryGpsRecording("ride-2", 2, Any())
        assertFailsWith<IllegalStateException> { f.demand.setEnabled(true) }
        assertEquals(1, f.sessions.size); assertEquals(1, old.closes)
        old.release = true
        f.demand.retry()
        assertEquals(2, old.closes); assertEquals(2, f.sessions.size)
        assertEquals("ride-2", f.keys.last().rideId)
        f.demand.setEnabled(false)
    }

    @Test fun disableReleasesServiceEvenWhenNativeCleanupFailsAndRetryKeepsExactOwner() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single(); old.release = false
        assertFailsWith<IllegalStateException> { f.demand.setEnabled(false) }
        assertEquals(listOf(true, false), f.services)
        assertEquals(1, old.closes)
        old.release = true; f.demand.retry()
        assertEquals(2, old.closes)
        assertEquals(listOf(true, false, false), f.services)
        assertEquals(1, f.sessions.size)
    }

    @Test fun permissionFailureCanRetrySameRecordingWithANewSessionOnlyAfterCleanup() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single()
        old.state.value = old.state.value.copy(wanted = false, failure = HistoryLocationCaptureFailure.FOREGROUND)
        f.demand.retry()
        assertEquals(1, old.closes); assertEquals(2, f.sessions.size)
        assertEquals(f.keys.first().identity, f.keys.last().identity)
        assertEquals(1, f.sessions.last().starts)
        f.demand.setEnabled(false)
    }

    @Test fun failedPositiveServiceSubmissionStillHasAReleaseAndCanRetryWithoutANewRide() = runTest {
        val f = Fixture(); f.serviceFailure = true
        assertFailsWith<IllegalStateException> { f.demand.setEnabled(true) }
        assertTrue(f.sessions.isEmpty())
        f.serviceFailure = false; f.demand.retry()
        assertEquals(1, f.sessions.size)
        f.demand.setEnabled(false)
        assertEquals(listOf(true, true, false), f.services)
    }

    @Test fun sessionIsReservedBeforeStartCanThrowSoItsOriginalCleanupIsNotLost() = runTest {
        val f = Fixture(); val session = Session().also { it.startFailure = true }; f.factory = { session }
        assertFailsWith<IllegalStateException> { f.demand.setEnabled(true) }
        assertEquals(1, session.starts)
        assertEquals(1, session.closes, "Failed launch must release its reserved session before retry")
        f.factory = { Session() }
        f.demand.retry()
        assertEquals(2, f.sessions.size)
        assertEquals(1, f.sessions.last().starts)
        f.demand.setEnabled(false)
        assertEquals(1, session.closes)
        assertEquals(1, f.sessions.last().closes)
        assertEquals(listOf(true, true, false), f.services)
    }

    @Test fun acceptedDisableCompletesOriginalCleanupWhenRequestingCallerIsCancelled() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single(); val gate = CompletableDeferred<Unit>(); old.gate = gate
        val disabled = backgroundScope.launch { f.demand.setEnabled(false) }
        try {
            runCurrent(); assertEquals(1, old.closes)
            disabled.cancel(); runCurrent(); assertFalse(disabled.isCompleted)
        } finally { gate.complete(Unit); disabled.cancelAndJoin() }
        assertEquals(listOf(true, false), f.services)
        assertEquals(1, old.closes)
    }

    @Test fun targetReplacedDuringOriginalCleanupCannotAllocateFromItsExpiredRetry() = runTest {
        val f = Fixture(); f.demand.setEnabled(true)
        val old = f.sessions.single(); val gate = CompletableDeferred<Unit>(); old.gate = gate
        f.recording = CapturedHistoryGpsRecording("ride-2", 2, Any())
        val retry = backgroundScope.async { f.demand.retry() }
        try {
            runCurrent(); assertEquals(1, old.closes)
            f.recording = CapturedHistoryGpsRecording("ride-3", 3, Any())
            gate.complete(Unit); retry.await()
            assertEquals(listOf("ride-1"), f.keys.map { it.rideId })
            f.demand.retry()
            assertEquals(listOf("ride-1", "ride-3"), f.keys.map { it.rideId })
        } finally { gate.complete(Unit); retry.cancelAndJoin(); f.demand.setEnabled(false) }
    }

    @Test fun synchronousServicePublicationCannotAllocateForRetiredTarget() = runTest {
        val f = Fixture()
        f.onService = { f.recording = CapturedHistoryGpsRecording("replacement", 2, Any()) }
        f.demand.setEnabled(true)
        assertTrue(f.sessions.isEmpty())
        f.onService = {}
        f.demand.retry()
        assertEquals(listOf("replacement"), f.keys.map { it.rideId })
        f.demand.setEnabled(false)
    }

    @Test fun factoryReentryRetiresReservedSessionWithoutStartingIt() = runTest {
        val f = Fixture()
        f.factory = {
            f.recording = CapturedHistoryGpsRecording("replacement", 2, Any())
            Session()
        }
        f.demand.setEnabled(true)
        val expired = f.sessions.single()
        assertEquals(0, expired.starts)
        assertEquals(1, expired.closes)
        f.factory = { Session() }
        f.demand.retry()
        assertEquals(listOf("ride-1", "replacement"), f.keys.map { it.rideId })
        assertEquals(1, f.sessions.last().starts)
        f.demand.setEnabled(false)
    }

    @Test fun actualControllerPublishesGpsOwnerBeforeDemandAndKeepsSessionAcrossBleGap() = runTest {
        val writer = Writer(); val services = mutableListOf<Boolean>(); val sessions = mutableListOf<Session>()
        lateinit var controller: RideRecordingController
        val demand = HistoryRecordingGpsDemand({ controller.captureGpsRecording() }, { services += it }, {
            assertEquals("ride", it.rideId)
            Session().also { session -> sessions += session }
        })
        controller = RideRecordingController(writer, demand, { "ride" }, { 0 })
        fun tick(delta: Long) = HistoryTick(10000 + delta, 100 + delta, 1)
        controller.execute(RecordingInput.Connected("bike", 7, tick(0)), listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack")))
        assertEquals(listOf("ride"), writer.begun)
        assertEquals(1, sessions.single().starts)
        assertNull(controller.state.value.demandFailure)
        val identity = assertNotNull(controller.captureGpsRecording()).identity
        controller.execute(RecordingInput.LinkLost(7, tick(1)))
        assertSame(identity, assertNotNull(controller.captureGpsRecording()).identity)
        demand.retry()
        assertEquals(1, sessions.size); assertEquals(0, sessions.single().closes)
        controller.execute(RecordingInput.Stop(tick(2), 7, "ride"))
        assertEquals(listOf<String?>("ride"), writer.ended)
        assertEquals(1, sessions.single().closes)
        assertEquals(listOf(true, true, false), services)
        assertNull(controller.captureGpsRecording())
    }

    @Test fun failedLaunchAndCleanupPreserveOriginalErrorAndRetainExactSessionForRetry() = runTest {
        val f = Fixture()
        val old = Session().also { it.startFailure = true; it.release = false }
        f.factory = { old }
        val error = assertFailsWith<IllegalStateException> { f.demand.setEnabled(true) }
        assertEquals("launch submission", error.message)
        // Coroutine stack recovery may copy the surfaced wrapper. Verify the original error.
        assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it === old.startError })
        assertEquals(1, old.startError.suppressedExceptions.size)
        assertEquals(1, old.closes)
        assertFailsWith<IllegalStateException> { f.demand.retry() }
        assertEquals(2, old.closes); assertEquals(1, f.sessions.size)
        old.release = true; f.factory = { Session() }
        f.demand.retry()
        assertEquals(3, old.closes); assertEquals(2, f.sessions.size)
        assertEquals(1, f.sessions.last().starts)
        f.demand.setEnabled(false)
    }

    @Test fun actualControllerKeepsTelemetryWhenServiceSubmissionFailsAndStopStillReleasesIt() = runTest {
        val writer = Writer(); val services = mutableListOf<Boolean>()
        lateinit var controller: RideRecordingController
        val demand = HistoryRecordingGpsDemand({ controller.captureGpsRecording() }, {
            services += it
            if (it) error("service unavailable")
        }, { error("GPS cannot allocate before service submission") })
        controller = RideRecordingController(writer, demand, { "ride" }, { 0 })
        controller.execute(RecordingInput.Connected("bike", 7, HistoryTick(10000, 100, 1)),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack")))
        assertNotNull(controller.state.value.demandFailure)
        assertNotNull(controller.captureTelemetryRecording())
        assertEquals(listOf("ride"), writer.begun)
        controller.execute(RecordingInput.Stop(HistoryTick(10001, 101, 1), 7, "ride"))
        assertEquals(listOf<String?>("ride"), writer.ended)
        assertEquals(listOf(true, false), services)
        assertNull(controller.captureTelemetryRecording())
    }
}

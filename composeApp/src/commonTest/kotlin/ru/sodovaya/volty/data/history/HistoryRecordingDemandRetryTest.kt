package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingDemandRetryTest {
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        var begins = 0
        var finishes = 0
        var failFinish = false
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins++ }
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            finishes++; finishGate?.await()
            if (failFinish) error("finish not acknowledged")
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Fixture {
        val writer = Writer()
        val calls = mutableListOf<Boolean>()
        var denyPositive = false
        var denyNegative = false
        var ids = 0
        var recoveries = 0
        val controller = RideRecordingController(writer, HistoryRecordingDemand { enabled ->
            calls += enabled
            if ((enabled && denyPositive) || (!enabled && denyNegative)) error("service unavailable")
        }, { "ride-${++ids}" }, { recoveries++; 0 })
        fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
        suspend fun connect() = controller.execute(RecordingInput.Connected("bike", 7, tick()),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Pack")))
    }

    @Test fun successfulRetryClearsFailureWithoutRotatingRideOrGpsIdentityEvenDuringBleGap() = runTest {
        val f = Fixture(); f.denyPositive = true; f.connect()
        val gps = assertNotNull(f.controller.captureGpsRecording())
        assertNotNull(f.controller.state.value.demandFailure)
        f.denyPositive = false
        f.controller.retryDemand()
        assertNull(f.controller.state.value.demandFailure)
        assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
        assertEquals(listOf(true, true), f.calls)
        f.controller.execute(RecordingInput.LinkLost(7, f.tick(1)))
        f.controller.retryDemand()
        assertEquals(listOf(true, true, true), f.calls)
        assertEquals(1, f.writer.begins); assertEquals(1, f.recoveries)
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
        f.controller.execute(RecordingInput.Stop(f.tick(2), 7, "ride-1"))
    }

    @Test fun stoppedRideRetriesOnlyFailedReleaseAndNeverRestarts() = runTest {
        val f = Fixture(); f.connect(); f.denyNegative = true
        f.controller.execute(RecordingInput.Stop(f.tick(1), 7, "ride-1"))
        assertNull(f.controller.state.value.intent.ride)
        assertNotNull(f.controller.state.value.demandFailure)
        f.denyNegative = false; f.controller.retryDemand()
        assertNull(f.controller.state.value.demandFailure)
        assertEquals(listOf(true, false, false), f.calls)
        assertNull(f.controller.captureGpsRecording())
        assertEquals(1, f.writer.begins); assertEquals(1, f.writer.finishes)
    }

    @Test fun queuedRetryUsesDesiredStateAfterStopAcknowledgement() = runTest {
        val f = Fixture(); f.connect()
        val release = CompletableDeferred<Unit>(); f.writer.finishGate = release
        val stop = backgroundScope.async { f.controller.execute(RecordingInput.Stop(f.tick(1), 7, "ride-1")) }
        var retry: Deferred<Unit>? = null
        try {
            runCurrent(); assertEquals(1, f.writer.finishes)
            retry = backgroundScope.async { f.controller.retryDemand() }
            runCurrent(); assertFalse(retry.isCompleted)
            release.complete(Unit); stop.await(); retry.await()
            assertEquals(listOf(true, false, false), f.calls)
            assertNull(f.controller.state.value.intent.ride)
            assertEquals(1, f.writer.begins)
        } finally { release.complete(Unit); stop.cancelAndJoin(); retry?.cancelAndJoin() }
    }

    @Test fun cancelledBeforeAdmissionCannotIssueAnyDemand() = runTest {
        val f = Fixture(); f.connect()
        val retry = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            f.controller.retryDemand()
        }
        retry.join()
        assertEquals(listOf(true), f.calls)
        assertEquals(1, f.writer.begins)
        f.controller.execute(RecordingInput.Stop(f.tick(1), 7, "ride-1"))
    }

    @Test fun failedDurableFinishCannotBeBypassedByPositiveRetryOrClearedDiagnostics() = runTest {
        val f = Fixture(); f.denyPositive = true; f.connect()
        val failure = assertNotNull(f.controller.state.value.demandFailure)
        f.denyPositive = false; f.writer.failFinish = true
        assertFailsWith<IllegalStateException> { f.controller.execute(RecordingInput.Stop(f.tick(1), 7, "ride-1")) }
        assertNull(f.controller.captureGpsRecording())
        f.controller.retryDemand()
        assertEquals(listOf(true), f.calls)
        assertEquals(failure, f.controller.state.value.demandFailure)
        assertEquals(1, f.writer.begins)
        f.writer.failFinish = false
        f.controller.execute(RecordingInput.Stop(f.tick(2), 7, "ride-1"))
        assertNull(f.controller.state.value.intent.ride)
        assertEquals(listOf(true, false), f.calls)
    }

    @Test fun repeatedRetryFailureStillLeavesTelemetryAdmittedAndReportsFailure() = runTest {
        val f = Fixture(); f.denyPositive = true; f.connect()
        val telemetry = assertNotNull(f.controller.captureTelemetryRecording())
        f.controller.retryDemand()
        assertEquals(listOf(true, true), f.calls)
        assertNotNull(f.controller.state.value.demandFailure)
        assertSame(telemetry.gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
        assertEquals(1, f.writer.begins)
        f.controller.execute(RecordingInput.Stop(f.tick(1), 7, "ride-1"))
    }
}

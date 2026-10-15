package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class QueuedHistoryTelemetryTest {
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun batch(delta: Long = 20, source: String = "pack") = HistoryBatch("ride-1",
        listOf(HistorySample(tick(delta), HistorySeriesKey(source, "power_w"), -100.0)), emptyList(), emptyList())
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val samples = mutableListOf<HistoryBatch>()
        val statuses = mutableListOf<HistoryRideStatus>()
        val ends = mutableListOf<HistoryTick>()
        var fail = false
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) = Unit
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            if (batch.samples.isNotEmpty()) { if(fail) error("writer failure"); samples += batch }
            return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            statuses += status; ends += tick
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Fixture {
        val writer = Writer(); private var ids = 0
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++ids}" }, { 0 })
        suspend fun begin() { controller.execute(RecordingInput.Connected("bike", 7, HistoryTick(10000, 100, 1)),
            listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A"))) }
    }

    @Test fun stopWaitsForQueuedPrefixAndAcceptedFactCanCommitAfterAdmissionFence() = runTest {
        val f = Fixture(); f.begin(); val c = f.controller
        val queued = assertNotNull(c.reserveQueuedTelemetry(assertNotNull(c.captureTelemetryRecording()), tick(20), "pack"))
        val stop = backgroundScope.async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
        try {
            runCurrent(); assertFalse(stop.isCompleted); assertNull(c.captureTelemetryRecording())
            assertEquals(HistoryOffer.ACCEPTED, queued.offer(batch()))
            stop.await()
            assertEquals(listOf(batch()), f.writer.samples)
            assertEquals(listOf(tick(20)), f.writer.ends)
            assertEquals(listOf(HistoryRideStatus.COMPLETED), f.writer.statuses)
            assertEquals(HistoryOffer.RETIRED, queued.offer(batch()))
            queued.discard(); assertEquals(1, f.writer.samples.size)
        } finally { queued.discard(); stop.cancelAndJoin() }
    }

    @Test fun queuedLossQualifiesOldRideBeforeFinishAndCannotPoisonExplicitRestart() = runTest {
        val f = Fixture(); f.begin(); val c = f.controller
        val queued = assertNotNull(c.reserveQueuedTelemetry(assertNotNull(c.captureTelemetryRecording()), tick(20), "pack"))
        val stop = backgroundScope.async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
        try {
            runCurrent(); assertFalse(stop.isCompleted)
            queued.discard(); stop.await()
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            c.execute(RecordingInput.Start("bike", 7, tick(30)), listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A")))
            queued.discard(); assertEquals(HistoryOffer.RETIRED, queued.offer(batch()))
            assertNull(c.telemetryFailure.value)
            c.execute(RecordingInput.Stop(tick(40), 7, "ride-2"))
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED, HistoryRideStatus.COMPLETED), f.writer.statuses)
            assertTrue(f.writer.samples.isEmpty())
        } finally { queued.discard(); stop.cancelAndJoin() }
    }

    @Test fun reservationRejectsForeignRetiredUnqualifiedSourceAndClockBeforeOwningQueueSpace() = runTest {
        val f = Fixture(); val other = Fixture(); f.begin(); other.begin(); val c = f.controller
        val context = assertNotNull(c.captureTelemetryRecording())
        assertNull(c.reserveQueuedTelemetry(assertNotNull(other.controller.captureTelemetryRecording()), tick(), "pack"))
        assertNull(c.reserveQueuedTelemetry(context, tick(), "foreign"))
        assertNull(c.reserveQueuedTelemetry(context, tick().copy(elapsedMillis = 99), "pack"))
        assertNull(c.reserveQueuedTelemetry(context, tick().copy(clockGeneration = 2), "pack"))
        c.execute(RecordingInput.LinkLost(7, tick(1)))
        assertNull(c.reserveQueuedTelemetry(context, tick(2), "pack"))
        c.execute(RecordingInput.Stop(tick(3), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.COMPLETED), f.writer.statuses)
    }

    @Test fun queuedWriterFailureIsLatchedBeforeReleasingWaitingFinish() = runTest {
        val f = Fixture(); f.begin(); val c = f.controller
        val queued = assertNotNull(c.reserveQueuedTelemetry(assertNotNull(c.captureTelemetryRecording()), tick(20), "pack"))
        val stop = backgroundScope.async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
        try {
            runCurrent(); assertFalse(stop.isCompleted); f.writer.fail = true
            assertFailsWith<IllegalStateException> { queued.offer(batch()) }
            stop.await(); assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            assertEquals(listOf(tick(20)), f.writer.ends)
        } finally { queued.discard(); stop.cancelAndJoin() }
    }

    @Test fun reservedBatchCannotSubstituteSourceTickOrRideOrAddGps() = runTest {
        val invalid = listOf(batch(source = "foreign"), batch(delta = 21), batch().copy(rideId = "foreign"),
            batch().copy(gps = listOf(HistoryGpsFix(tick(20), 56.0, 60.0, 5.0, null))))
        for (candidate in invalid) {
            val f = Fixture(); f.begin(); val c = f.controller
            val queued = assertNotNull(c.reserveQueuedTelemetry(assertNotNull(c.captureTelemetryRecording()), tick(20), "pack"))
            try {
                assertEquals(HistoryOffer.RETIRED, queued.offer(candidate))
                c.execute(RecordingInput.Stop(tick(30), 7, "ride-1"))
                assertTrue(f.writer.samples.isEmpty()); assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            } finally { queued.discard() }
        }
    }
}

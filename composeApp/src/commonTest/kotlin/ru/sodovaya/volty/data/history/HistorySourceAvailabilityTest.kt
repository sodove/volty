package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistorySourceAvailabilityTest {
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        var begins = 0
        val offers = mutableListOf<HistoryBatch>()
        val finishes = mutableListOf<HistoryTick>()
        var beforeOffer: (HistoryBatch) -> Unit = {}
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins++ }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            beforeOffer(batch); offers += batch; return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { finishes += tick }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private inner class Fixture {
        val writer = Writer()
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { 0 })
        suspend fun start() = controller.execute(RecordingInput.Connected("bike", 7, tick()),
            listOf("a", "b").map { HistorySource(it, HistorySourceKind.BATTERY, it) })
        fun batch(source: String, delta: Long, metric: String = "current_a") = HistoryBatch("ride",
            listOf(HistorySample(tick(delta), HistorySeriesKey(source, metric), 7.0)), emptyList(), emptyList())
        fun offer(source: String, delta: Long = 30, metric: String = "current_a"): HistoryOffer {
            val context = controller.captureTelemetryRecording() ?: return HistoryOffer.RETIRED
            return controller.offerTelemetry(context, batch(source, delta, metric))
        }
        fun scoped() = writer.offers.flatMap { it.markers }.filter { it.sourceId != null }
    }

    @Test fun partial_source_loss_keeps_the_recording_global_gate_peers_and_gps() = runTest {
        val f = Fixture(); f.start()
        val before = assertNotNull(f.controller.captureTelemetryRecording())
        val gps = assertNotNull(f.controller.captureGpsRecording())
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        assertEquals(listOf(HistoryMarker(tick(20), HistoryMarkerKind.BLE_GAP, "a")), f.scoped())
        assertSame(before.gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
        assertSame(gps.identity, assertNotNull(f.controller.captureGpsRecording()).identity)
        assertEquals(HistoryOffer.RETIRED, f.offer("a"))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("b"))
        assertEquals(HistoryOffer.ACCEPTED, f.controller.offerGps(gps.rideId,
            HistoryGpsFix(tick(40), 56.0, 60.0, 5.0, null)))
        assertEquals(1, f.writer.begins)
        assertTrue(f.writer.finishes.isEmpty())
    }

    @Test fun stale_generation_foreign_sources_and_wrong_clock_cannot_close_current_sources() = runTest {
        val f = Fixture(); f.start()
        assertFalse(f.controller.sourceUnavailable(6, setOf("a"), tick(20)))
        assertFalse(f.controller.sourceUnavailable(7, setOf("b", "foreign"), tick(20)))
        assertFalse(f.controller.sourceUnavailable(7, setOf("a"), tick(20).copy(clockGeneration = 2)))
        assertTrue(f.scoped().isEmpty())
        assertEquals(HistoryOffer.ACCEPTED, f.offer("a"))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("b"))
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(40)))
        assertEquals(HistoryOffer.RETIRED, f.offer("a", 50))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("b", 50))
    }

    @Test fun exact_source_reopen_emits_once_and_cannot_revive_a_finished_recording() = runTest {
        val f = Fixture(); f.start()
        val gate = assertNotNull(f.controller.captureTelemetryRecording()).gate
        assertFalse(f.controller.sourceReady(7, setOf("a"), tick(10)))
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        assertFalse(f.controller.sourceUnavailable(7, setOf("a"), tick(25)))
        assertTrue(f.controller.sourceReady(7, setOf("a"), tick(40)))
        assertFalse(f.controller.sourceReady(7, setOf("a"), tick(45)))
        assertEquals(listOf(HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CONNECTED), f.scoped().map { it.kind })
        assertSame(gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
        assertEquals(HistoryOffer.ACCEPTED, f.offer("a", 60))
        f.controller.execute(RecordingInput.ExplicitDisconnect(tick(100), 7))
        assertFalse(f.controller.sourceReady(7, setOf("a"), tick(110)))
        assertNull(f.controller.captureTelemetryRecording())
        assertEquals(1, f.writer.begins)
        assertEquals(listOf(tick(100)), f.writer.finishes)
    }

    @Test fun source_boundary_is_clamped_only_to_its_own_prefix_but_finish_owns_all_facts() = runTest {
        val f = Fixture(); f.start()
        assertEquals(HistoryOffer.ACCEPTED, f.offer("a", 50))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("b", 100))
        assertEquals(HistoryOffer.ACCEPTED, f.controller.offerGps("ride", HistoryGpsFix(tick(150), 56.0, 60.0, 5.0, null)))
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        assertEquals(tick(50), f.scoped().single().tick)
        f.controller.execute(RecordingInput.ExplicitDisconnect(tick(100), 7))
        assertEquals(listOf(tick(150)), f.writer.finishes)
    }

    @Test fun delayed_source_control_cannot_move_behind_its_prior_reopen_without_numeric_receipts() = runTest {
        val f = Fixture(); f.start()
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        assertTrue(f.controller.sourceReady(7, setOf("a"), tick(40)))
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(30)))
        assertEquals(listOf(tick(20), tick(40), tick(40)), f.scoped().map { it.tick })
        f.controller.execute(RecordingInput.ExplicitDisconnect(tick(35), 7))
        assertEquals(listOf(tick(40)), f.writer.finishes)
    }

    @Test fun partial_loss_waits_its_native_queue_prefix_but_not_healthy_queued_receipts() = runTest {
        val f = Fixture(); f.start()
        val context = assertNotNull(f.controller.captureTelemetryRecording())
        val a = assertNotNull(f.controller.reserveQueuedTelemetry(context, tick(10), "a"))
        val loss = async { f.controller.sourceUnavailable(7, setOf("a"), tick(20)) }
        var b: QueuedHistoryTelemetry? = null
        try {
            runCurrent(); assertFalse(loss.isCompleted, "Driver holds an accepted source A receipt")
            assertSame(context.gate, assertNotNull(f.controller.captureTelemetryRecording()).gate)
            b = assertNotNull(f.controller.reserveQueuedTelemetry(context, tick(30), "b"))
            assertEquals(HistoryOffer.ACCEPTED, f.offer("b", 40, "voltage_v"))
            assertEquals(HistoryOffer.ACCEPTED, f.controller.offerGps("ride", HistoryGpsFix(tick(50), 56.0, 60.0, 5.0, null)))
            assertTrue(f.scoped().isEmpty())
            assertEquals(HistoryOffer.ACCEPTED, a.offer(f.batch("a", 10)))
            runCurrent(); assertTrue(loss.isCompleted, "Healthy source B still holds its queued receipt")
            assertTrue(loss.await())
            assertEquals(listOf(HistoryMarker(tick(20), HistoryMarkerKind.BLE_GAP, "a")), f.scoped())
            val sampleIndex = f.writer.offers.indexOfFirst { it.samples.any { sample -> sample.key.sourceId == "a" } }
            val markerIndex = f.writer.offers.indexOfFirst { it.markers.any { marker -> marker.sourceId == "a" } }
            assertTrue(sampleIndex >= 0 && markerIndex > sampleIndex)
            assertEquals(HistoryOffer.ACCEPTED, b.offer(f.batch("b", 30)))
        } finally { a.discard(); b?.discard(); loss.cancelAndJoin() }
    }

    @Test fun cancelling_an_accepted_source_negative_cannot_abandon_its_prefix_or_marker() = runTest {
        val f = Fixture(); f.start()
        val context = assertNotNull(f.controller.captureTelemetryRecording())
        val a = assertNotNull(f.controller.reserveQueuedTelemetry(context, tick(10), "a"))
        val loss = async { f.controller.sourceUnavailable(7, setOf("a"), tick(20)) }
        try {
            runCurrent(); assertFalse(loss.isCompleted)
            loss.cancel(); runCurrent()
            assertFalse(loss.isCompleted, "Accepted source cleanup still owns the queued prefix")
            assertTrue(a.discard())
            loss.join()
            assertTrue(loss.isCancelled)
            assertEquals(listOf(HistoryMarker(tick(20), HistoryMarkerKind.BLE_GAP, "a")), f.scoped())
            assertEquals(HistoryOffer.RETIRED, f.offer("a", 30))
            assertEquals(HistoryOffer.ACCEPTED, f.offer("b", 30))
        } finally { a.discard(); loss.cancelAndJoin() }
    }

    @Test fun closed_source_cannot_reserve_new_native_queue_work_until_its_qualified_reopen() = runTest {
        val f = Fixture(); f.start()
        val context = assertNotNull(f.controller.captureTelemetryRecording())
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        val rejected = f.controller.reserveQueuedTelemetry(context, tick(30), "a")
        var healthy: QueuedHistoryTelemetry? = null
        var fresh: QueuedHistoryTelemetry? = null
        try {
            assertNull(rejected)
            healthy = assertNotNull(f.controller.reserveQueuedTelemetry(context, tick(30), "b"))
            assertTrue(f.controller.sourceReady(7, setOf("a"), tick(40)))
            fresh = assertNotNull(f.controller.reserveQueuedTelemetry(context, tick(50), "a"))
        } finally { rejected?.discard(); healthy?.discard(); fresh?.discard() }
    }

    @Test fun source_numeric_intake_remains_fenced_until_its_reopen_marker_is_submitted() = runTest {
        val f = Fixture(); f.start()
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        var reentered = false
        var premature: HistoryOffer? = null
        f.writer.beforeOffer = { batch ->
            if (batch.markers.any { it.sourceId == "a" && it.kind == HistoryMarkerKind.CONNECTED }) {
                reentered = true
                premature = f.offer("a", 50)
            }
        }
        assertTrue(f.controller.sourceReady(7, setOf("a"), tick(40)))
        assertTrue(reentered)
        assertEquals(HistoryOffer.RETIRED, premature)
        assertEquals(HistoryOffer.ACCEPTED, f.offer("a", 60))
    }

    @Test fun failed_reopen_marker_keeps_source_fenced_and_allows_a_later_positive_retry() = runTest {
        val f = Fixture(); f.start()
        assertTrue(f.controller.sourceUnavailable(7, setOf("a"), tick(20)))
        f.writer.beforeOffer = { batch ->
            if (batch.markers.any { it.sourceId == "a" && it.kind == HistoryMarkerKind.CONNECTED }) error("reopen rejected")
        }
        assertFailsWith<IllegalStateException> { f.controller.sourceReady(7, setOf("a"), tick(40)) }
        assertEquals(HistoryOffer.RETIRED, f.offer("a", 50))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("b", 50))
        f.writer.beforeOffer = {}
        assertTrue(f.controller.sourceReady(7, setOf("a"), tick(60)))
        assertEquals(HistoryOffer.ACCEPTED, f.offer("a", 70))
        assertNotNull(f.controller.telemetryFailure.value)
    }
}

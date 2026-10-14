package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RideRecordingControllerTest {
    private val sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "A"))
    private fun tick(delta: Long = 0) = HistoryTick(10000 + delta, 100 + delta, 1)
    private fun batch(id: String = "ride-1", delta: Long = 1) = HistoryBatch(id,
        listOf(HistorySample(tick(delta), HistorySeriesKey("pack", "power_w"), -100.0)), emptyList(), emptyList())
    private class Fixture {
        val writer = Writer()
        val demands = mutableListOf<Boolean>()
        var nextId = 0
        var recoveries = 0
        var denyDemand = false
        val controller = RideRecordingController(writer, HistoryRecordingDemand {
            demands += it
            if (denyDemand) error("GPS unavailable")
        }, { "ride-${++nextId}" }, { recoveries++; 2 })
    }

    @Test fun reconnectKeepsRideButChangesBleAdmissionWithoutChangingWriterGeneration() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(7, batch()))
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch(delta = 3)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerGps("ride-1", HistoryGpsFix(tick(3), 56.0, 60.0, 5.0, null)))
        c.execute(RecordingInput.Connected("bike", 8, tick(4)), sources)
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch(delta = 5)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(8, batch(delta = 5)))
        assertEquals("ride-1", c.state.value.intent.ride?.id)
        assertEquals(listOf("begin:ride-1"), f.writer.calls)
        assertEquals(1, f.writer.offers.map { it.first }.distinct().size)
        assertEquals(listOf(true), f.demands)
    }

    @Test fun manualStopBlocksAutoRestartAndStaleStopCannotEndNewRideOnSameBleGeneration() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.Stop(tick(10), 7, "ride-1"))
        c.execute(RecordingInput.Connected("bike", 8, tick(11)), sources)
        assertNull(c.state.value.intent.ride)
        c.execute(RecordingInput.Start("bike", 8, tick(12)), sources)
        c.execute(RecordingInput.Stop(tick(13), 8, "ride-1"))
        assertEquals("ride-2", c.state.value.intent.ride?.id)
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(8, batch()))
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(8, batch("ride-2", 14)))
        assertEquals(listOf("begin:ride-1", "finish:ride-1", "begin:ride-2"), f.writer.calls)
        assertEquals(listOf(true, false, true), f.demands)
    }

    @Test fun vehicleSwitchWritesTerminalMarkerToOldRideAndFinishesBeforeBeginningNew() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.Connected("wheel", 8, tick(10)), sources)
        assertEquals(listOf("begin:ride-1", "finish:ride-1", "begin:ride-2"), f.writer.calls)
        val markerBatch = f.writer.offers.map { it.second }.single { it.markers.any { m -> m.kind == HistoryMarkerKind.VEHICLE_SWITCH } }
        assertEquals("ride-1", markerBatch.rideId)
        assertEquals("wheel", c.state.value.intent.ride?.vehicleId)
        assertEquals(1, f.recoveries)
    }

    @Test fun missingGpsDemandDoesNotDisableTelemetryOrLoseOwnedRide() = runTest {
        val f = Fixture(); f.denyDemand = true
        f.controller.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
        assertEquals("GPS unavailable", f.controller.state.value.demandFailure)
        assertEquals(HistoryOffer.ACCEPTED, f.controller.offerTelemetry(7, batch()))
        f.controller.execute(RecordingInput.ExplicitDisconnect(tick(10), 7))
        assertNull(f.controller.state.value.intent.ride)
        assertEquals(listOf("begin:ride-1", "finish:ride-1"), f.writer.calls)
    }

    @Test fun stopFencesTelemetryAndGpsBeforeWaitingForDurableFinish() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val gate = CompletableDeferred<Unit>(); f.writer.finishGate = gate
        val stop = async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
        runCurrent()
        assertTrue("finish:ride-1" in f.writer.calls)
        assertFalse(stop.isCompleted)
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch(delta = 11)))
        assertEquals(HistoryOffer.RETIRED, c.offerGps("ride-1", HistoryGpsFix(tick(11), 56.0, 60.0, 5.0, null)))
        gate.complete(Unit); stop.await()
        assertNull(c.state.value.intent.ride)
    }

    @Test fun staleClockUnknownSourceAndWrongPayloadCannotReachWriter() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val count = f.writer.offers.size
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch().copy(samples = listOf(batch().samples.single().copy(tick = tick().copy(elapsedMillis = 99))))))
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch().copy(samples = listOf(batch().samples.single().copy(tick = tick().copy(clockGeneration = 2))))))
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(7, batch().copy(samples = listOf(batch().samples.single().copy(key = HistorySeriesKey("other", "power_w"))))))
        assertEquals(count, f.writer.offers.size)
    }

    @Test fun delayedStopCannotEndBeforeTelemetryOrGpsAlreadyAdmitted() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(7, batch(delta = 20)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerGps("ride-1", HistoryGpsFix(tick(25), 56.0, 60.0, 5.0, null)))
        // UI captured this stop timestamp before its command could acquire the control owner.
        c.execute(RecordingInput.Stop(tick(10), 7, "ride-1"))
        assertEquals(tick(25), f.writer.ends.single())
        assertNull(c.state.value.intent.ride)
    }

    @Test fun stopWaitsForReservedOfferToReachWriterBeforeFinishing() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        var stop: Deferred<Unit>? = null
        f.writer.beforeOffer = { batch ->
            if (batch.samples.isNotEmpty()) {
                stop = async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
                runCurrent()
                assertFalse("finish:ride-1" in f.writer.calls, "Reserved point has not reached writer yet")
                assertFalse(assertNotNull(stop).isCompleted)
            }
        }
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(7, batch(delta = 20)))
        assertNotNull(stop).await()
        assertEquals(tick(20), f.writer.ends.single())
    }

    @Test fun delayedStopCannotEndBeforeCommittedReconnectMarkerEither() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.LinkLost(7, tick(20)))
        c.execute(RecordingInput.Connected("bike", 8, tick(40)), sources)
        c.execute(RecordingInput.Stop(tick(30), 8, "ride-1"))
        assertEquals(tick(40), f.writer.ends.single())
    }

    @Test fun failedWriterEndsInterruptedAndThrowingOfferReleasesItsReservation() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        f.writer.beforeOffer = { if (it.samples.isNotEmpty()) error("offer rejected") }
        assertFailsWith<IllegalStateException> { c.offerTelemetry(7, batch(delta = 20)) }
        f.writer.diagnostics.value = HistoryWriterDiagnostics(failure = "disk failed")
        c.execute(RecordingInput.Stop(tick(30), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
        assertNull(c.state.value.intent.ride)
    }

    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val calls = mutableListOf<String>()
        val offers = mutableListOf<Pair<Long, HistoryBatch>>()
        val ends = mutableListOf<HistoryTick>()
        val statuses = mutableListOf<HistoryRideStatus>()
        var finishGate: CompletableDeferred<Unit>? = null
        var beforeOffer: ((HistoryBatch) -> Unit)? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { calls += "begin:${ride.id}" }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { beforeOffer?.invoke(batch); offers += generation to batch; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { calls += "finish:$rideId"; ends += tick; statuses += status; finishGate?.await() }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
}

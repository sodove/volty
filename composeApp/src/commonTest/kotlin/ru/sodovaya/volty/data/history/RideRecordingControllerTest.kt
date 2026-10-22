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

    @Test fun capturedRecordingCannotBeReattributedToNewRide() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val old = assertNotNull(c.captureTelemetryRecording())
        c.execute(RecordingInput.Stop(tick(2), 7, "ride-1"))
        c.execute(RecordingInput.Start("bike", 7, tick(3)), sources)
        val fresh = assertNotNull(c.captureTelemetryRecording())
        val count = f.writer.offers.size
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(old, batch("ride-2", 4)))
        assertEquals(count, f.writer.offers.size)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(fresh, batch("ride-2", 4)))
    }

    @Test fun reconnectOnSameConnectionNumberRetiresCapturedAdmissionSegment() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val old = assertNotNull(c.captureTelemetryRecording())
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        assertNull(c.captureTelemetryRecording())
        c.execute(RecordingInput.Connected("bike", 7, tick(3)), sources)
        val fresh = assertNotNull(c.captureTelemetryRecording())
        val count = f.writer.offers.size
        assertEquals(old.rideId, fresh.rideId)
        assertEquals(old.connectionGeneration, fresh.connectionGeneration)
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(old, batch(delta = 4)))
        assertEquals(count, f.writer.offers.size)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(fresh, batch(delta = 4)))
    }

    @Test fun capturedRecordingFromAnotherControllerCannotUseMatchingRideAndGeneration() = runTest {
        val a = Fixture(); val b = Fixture()
        a.controller.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        b.controller.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val foreign = assertNotNull(a.controller.captureTelemetryRecording())
        assertEquals(HistoryOffer.RETIRED, b.controller.offerTelemetry(foreign, batch()))
        assertTrue(b.writer.offers.all { it.second.samples.isEmpty() })
    }

    @Test fun gpsDuringAShortDropoutIsWrittenWhenTheVehicleReturns() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        val before = f.writer.offers.size
        assertEquals(HistoryOffer.ACCEPTED, c.offerGps("ride-1", HistoryGpsFix(tick(3), 56.0, 60.0, 5.0, null)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerGpsGap("ride-1", tick(4)))
        assertEquals(before, f.writer.offers.size, "held while the vehicle is away")
        c.execute(RecordingInput.Connected("bike", 8, tick(5)), sources)
        val written = f.writer.offers.drop(before).map { it.second }
        assertEquals(listOf(tick(3)), written.flatMap { it.gps }.map { it.tick }, "the dropout keeps its track")
        val gap = written.indexOfFirst { b -> b.markers.any { it.kind == HistoryMarkerKind.GPS_GAP } }
        val connected = written.indexOfFirst { b -> b.markers.any { it.kind == HistoryMarkerKind.CONNECTED } }
        assertTrue(gap in 0 until connected, "held facts are written before the reconnect marker")
    }

    @Test fun aRideEndedForAbsenceEndsWhenTheVehicleLeftNotWhenTheWaitEnded() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        val before = f.writer.offers.size
        (3L..300L step 10).forEach { c.offerGps("ride-1", HistoryGpsFix(tick(it), 56.0, 60.0, 5.0, null)) }
        c.execute(RecordingInput.Absent(7, tick(2)))
        assertTrue(f.writer.offers.drop(before).none { it.second.gps.isNotEmpty() }, "the wait for a switched-off wheel is not riding")
        assertEquals(tick(2), f.writer.ends.single())
        // A new ride does not inherit the dropped fixes either.
        c.execute(RecordingInput.Connected("bike", 7, tick(400)), sources)
        assertTrue(f.writer.offers.none { it.second.rideId == "ride-2" && it.second.gps.isNotEmpty() })
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

    @Test fun replacementSourceFinishesOldRideBeforeBeginningNewAndRetiresCapturedPackets() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val captured = assertNotNull(c.captureTelemetryRecording())
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(captured, batch(delta = 30)))
        val replacement = listOf(HistorySource("replacement", HistorySourceKind.BATTERY, "B"))
        // A delayed profile change cannot move the terminal boundary behind an admitted fact.
        c.execute(RecordingInput.Connected("bike", 8, tick(10)), replacement)
        assertEquals(listOf("begin:ride-1", "finish:ride-1", "begin:ride-2"), f.writer.calls)
        assertEquals(tick(30), f.writer.ends.single())
        assertEquals(tick(30), f.writer.begins.last().first.started)
        assertEquals(replacement, f.writer.begins.last().second)
        val terminal = f.writer.offers.map { it.second }.single { b -> b.markers.any { it.kind.name == "SOURCE_CHANGE" } }
        assertEquals("ride-1", terminal.rideId)
        assertEquals(tick(30), terminal.markers.single().tick)
        val newBatch = batch("ride-2", 31).let { it.copy(samples = it.samples.map { s -> s.copy(key = HistorySeriesKey("replacement", "power_w")) }) }
        val offered = f.writer.offers.size
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(captured, newBatch))
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(8, batch("ride-2", 31)))
        assertEquals(offered, f.writer.offers.size)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(assertNotNull(c.captureTelemetryRecording()), newBatch))
        assertEquals(listOf(true, false, true), f.demands)
    }

    @Test fun changedKindOnSameIdAndSameConnectionStartsDistinctRide() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val changed = sources.map { it.copy(kind = HistorySourceKind.DERIVED_BATTERY) }
        c.execute(RecordingInput.Start("bike", 7, tick(10)), changed)
        assertEquals(listOf("begin:ride-1", "finish:ride-1", "begin:ride-2"), f.writer.calls)
        assertEquals(changed, f.writer.begins.last().second)
        assertEquals(HistoryRideStatus.COMPLETED, f.writer.statuses.single())
        assertEquals(7L, c.state.value.intent.connectionGeneration)
    }

    @Test fun sourceLabelsAndOrderingDoNotSplitRideOrRewriteItsRecordedMetadata() = runTest {
        val f = Fixture(); val c = f.controller
        val original = sources + HistorySource("motor", HistorySourceKind.CONTROLLER, "Rear")
        c.execute(RecordingInput.Connected("bike", 7, tick()), original)
        c.execute(RecordingInput.LinkLost(7, tick(1)))
        c.execute(RecordingInput.Connected("bike", 8, tick(2)), original.reversed().map { it.copy(label = "Renamed") })
        assertEquals("ride-1", c.state.value.intent.ride?.id)
        assertEquals(listOf("begin:ride-1"), f.writer.calls)
        assertEquals(original, f.writer.begins.single().second)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(8, batch(delta = 3)))
    }

    @Test fun explicitlyEmptyRegistryRotatesToGpsOnlyWhileOmittedRegistryKeepsCurrentSources() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        c.execute(RecordingInput.LinkLost(7, tick(1)))
        c.execute(RecordingInput.Connected("bike", 8, tick(2)))
        assertEquals("ride-1", c.state.value.intent.ride?.id)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(8, batch(delta = 3)))
        c.execute(RecordingInput.Connected("bike", 9, tick(4)), emptyList())
        assertEquals("ride-2", c.state.value.intent.ride?.id)
        assertTrue(f.writer.begins.last().second.isEmpty())
        assertEquals(HistoryOffer.RETIRED, c.offerTelemetry(9, batch("ride-2", 5)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerGps("ride-2", HistoryGpsFix(tick(5), 56.0, 60.0, 5.0, null)))
    }

    @Test fun staleRegistryCannotRotateCurrentRideAndManualStopIsNotRearmedByNewRegistry() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val changed = listOf(HistorySource("new", HistorySourceKind.BATTERY, "B"))
        c.execute(RecordingInput.Connected("bike", 6, tick(1)), changed)
        assertEquals(listOf("begin:ride-1"), f.writer.calls)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(7, batch(delta = 2)))
        c.execute(RecordingInput.Stop(tick(3), 7, "ride-1"))
        c.execute(RecordingInput.Connected("bike", 8, tick(4)), changed)
        assertNull(c.state.value.intent.ride)
        c.execute(RecordingInput.Start("bike", 8, tick(5)))
        assertTrue(f.writer.begins.last().second.isEmpty(), "Omission without an active ride must not reuse retired metadata")
    }

    @Test fun duplicateRegistryIsRejectedBeforeClosingCurrentAdmission() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val current = assertNotNull(c.captureTelemetryRecording())
        assertFailsWith<IllegalArgumentException> {
            c.execute(RecordingInput.Connected("bike", 8, tick(1)), sources + sources.single().copy(label = "Duplicate"))
        }
        assertEquals(listOf("begin:ride-1"), f.writer.calls)
        assertEquals(HistoryOffer.ACCEPTED, c.offerTelemetry(current, batch(delta = 2)))
    }

    @Test fun gpsGapUsesRideClockFenceAndParticipatesInDelayedStopBound() = runTest {
        val f = Fixture(); val c = f.controller
        c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val count = f.writer.offers.size
        assertEquals(HistoryOffer.RETIRED, c.offerGpsGap("foreign", tick(1)))
        assertEquals(HistoryOffer.RETIRED, c.offerGpsGap("ride-1", tick().copy(elapsedMillis = 99)))
        assertEquals(HistoryOffer.RETIRED, c.offerGpsGap("ride-1", tick(1).copy(clockGeneration = 2)))
        assertEquals(count, f.writer.offers.size)
        c.execute(RecordingInput.LinkLost(7, tick(2)))
        assertEquals(HistoryOffer.ACCEPTED, c.offerGpsGap("ride-1", tick(20)))
        c.execute(RecordingInput.Stop(tick(10), 7, "ride-1"))
        assertEquals(tick(20), f.writer.ends.single())
        val stopped = f.writer.offers.size
        assertEquals(HistoryOffer.RETIRED, c.offerGpsGap("ride-1", tick(21)))
        assertEquals(stopped, f.writer.offers.size)
    }

    @Test fun throwingGpsOfferLatchesFailureBeforeConcurrentStopCanFinish() = runTest {
        for (isGap in listOf(false, true)) {
            val f = Fixture(); val c = f.controller
            c.execute(RecordingInput.Connected("bike", 7, tick()), sources)
            var stop: Deferred<Unit>? = null
            f.writer.beforeOffer = { b ->
                if (b.gps.isNotEmpty() || b.markers.any { it.kind == HistoryMarkerKind.GPS_GAP }) {
                    stop = async { c.execute(RecordingInput.Stop(tick(10), 7, "ride-1")) }
                    runCurrent()
                    assertFalse(assertNotNull(stop).isCompleted)
                    error("GPS offer failed")
                }
            }
            assertFailsWith<IllegalStateException> {
                if (isGap) c.offerGpsGap("ride-1", tick(20))
                else c.offerGps("ride-1", HistoryGpsFix(tick(20), 56.0, 60.0, 5.0, null))
            }
            assertNotNull(stop).await()
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            assertEquals(tick(20), f.writer.ends.single())
        }
    }

    @Test fun gpsFailureCapabilitySpansBleReconnectButCannotCrossControllerOrRideOwnership() = runTest {
        val a = Fixture(); val b = Fixture()
        a.controller.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        b.controller.execute(RecordingInput.Connected("bike", 7, tick()), sources)
        val captured = assertNotNull(a.controller.captureGpsRecording())
        assertFalse(b.controller.reportGpsObservationFailure(captured))
        assertNull(b.controller.telemetryFailure.value)
        a.controller.execute(RecordingInput.LinkLost(7, tick(1)))
        a.controller.execute(RecordingInput.Connected("bike", 8, tick(2)), sources)
        assertTrue(a.controller.reportGpsObservationFailure(captured))
        a.controller.execute(RecordingInput.Stop(tick(3), 8, "ride-1"))
        a.controller.execute(RecordingInput.Start("bike", 8, tick(4)), sources)
        assertFalse(a.controller.reportGpsObservationFailure(captured))
        assertNull(a.controller.telemetryFailure.value)
        a.controller.execute(RecordingInput.Stop(tick(5), 8, "ride-2"))
        assertEquals(listOf(HistoryRideStatus.INTERRUPTED, HistoryRideStatus.COMPLETED), a.writer.statuses)
        b.controller.execute(RecordingInput.Stop(tick(5), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.COMPLETED), b.writer.statuses)
    }

    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val calls = mutableListOf<String>()
        val begins = mutableListOf<Pair<HistoryRide, List<HistorySource>>>()
        val offers = mutableListOf<Pair<Long, HistoryBatch>>()
        val ends = mutableListOf<HistoryTick>()
        val statuses = mutableListOf<HistoryRideStatus>()
        var finishGate: CompletableDeferred<Unit>? = null
        var beforeOffer: ((HistoryBatch) -> Unit)? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { calls += "begin:${ride.id}"; begins += ride to sources.toList() }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { beforeOffer?.invoke(batch); offers += generation to batch; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { calls += "finish:$rideId"; ends += tick; statuses += status; finishGate?.await() }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
}

package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.location.*
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryGpsRecorderTest {
    private class Fixture(val test: TestScope, scopeOverride: CoroutineScope? = null, val autoForeground: Boolean = true) {
        val writer = Writer()
        var nextId = 0
        val location = MutableStateFlow(RideLocationState())
        val foreground = HistoryLocationForegroundAuthority({ true }) { "10000000-0000-0000-0000-000000000001" }
        val nativeService = Any()
        var nativeTicket: HistoryLocationForegroundTicket? = null
        val controller = RideRecordingController(writer, HistoryRecordingDemand(::demand), { "ride-${++nextId}" }, { 0 })
        private fun demand(enabled: Boolean) {
            val state = location.value
            location.value = state.copy(demands = if (enabled) state.demands + LocationConsumer.HISTORY_RECORDING
                else state.demands - LocationConsumer.HISTORY_RECORDING)
            if (enabled) {
                val ticket = foreground.request(checkNotNull(controller.captureGpsRecording())) { }
                nativeTicket = ticket
                if (autoForeground) {
                    check(foreground.acknowledge(ticket, nativeService))
                    check(foreground.admitCapture(ticket))
                }
            } else nativeTicket?.let(foreground::stop)
        }
        val scope = scopeOverride ?: CoroutineScope(test.backgroundScope.coroutineContext + Job(test.backgroundScope.coroutineContext[Job]))
        var beforeRead: () -> Unit = { }
        fun tick() = HistoryTick(10_000 + test.testScheduler.currentTime, 100 + test.testScheduler.currentTime, 1)
        val recorder = HistoryGpsRecorder(scope, controller, location, foreground, { beforeRead(); tick() }, maxAgeMillis = 1_000)
        suspend fun start() { controller.execute(RecordingInput.Start("bike", 7, tick()), listOf(HistorySource("p", HistorySourceKind.BATTERY, "P"))) }
        fun status(value: RideLocationStatus) { location.value = location.value.copy(status = value) }
        fun fix(elapsed: Long = tick().elapsedMillis, wall: Long = 123_456, latitude: Double = 56.0) = RideLocationFix(
            GeoCoordinate(latitude, 60.0), 5.0, null, null, tick().epochMillis, elapsed, LocationSource.GPS, wall)
        fun fixes() = writer.offers.flatMap { it.gps }
        fun gaps() = writer.offers.flatMap { it.markers }.filter { it.kind == HistoryMarkerKind.GPS_GAP }
    }

    @Test fun captureRevokedBetweenQualifiedInputAndWriterOfferCannotIssueCoordinate() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Searching); f.start(); runCurrent()
        advanceTimeBy(1)
        val ticket = assertNotNull(f.nativeTicket)
        var qualifiedReadReached = false
        f.beforeRead = {
            f.beforeRead = { }
            assertTrue(f.foreground.canCapture(assertNotNull(f.controller.captureGpsRecording())))
            qualifiedReadReached = true
            assertTrue(f.foreground.fenceCapture(ticket))
        }
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertTrue(qualifiedReadReached, "Driver must revoke after input qualification, before offer")
        assertTrue(f.fixes().isEmpty(), "A cached input decision bypassed foreground revocation")
        assertTrue(f.foreground.admitCapture(ticket)); runCurrent()
        assertEquals(1, f.fixes().size)
    }

    @Test fun requestedProviderAndNativeForegroundAloneCannotBypassCaptureAcknowledgement() = runTest {
        val f = Fixture(this, autoForeground = false)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        assertTrue(f.fixes().isEmpty())
        assertEquals(1, f.gaps().size)
        val ticket = assertNotNull(f.nativeTicket)
        assertTrue(f.foreground.acknowledge(ticket, f.nativeService)); runCurrent()
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertTrue(f.fixes().isEmpty(), "Native foreground did not prove provider acquisition")
        assertTrue(f.foreground.admitCapture(ticket)); runCurrent()
        assertEquals(1, f.fixes().size)
    }

    @Test fun captureRevocationOpensGapBeforeSharedProviderOrNativeServiceStops() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        advanceTimeBy(10)
        val retained = f.location.value
        val ticket = assertNotNull(f.nativeTicket)
        assertTrue(f.foreground.fenceCapture(ticket)); runCurrent()
        assertEquals(retained, f.location.value)
        assertTrue(f.foreground.allows(ticket.recording), "Foreground must remain until original cleanup")
        assertEquals(listOf(f.tick()), f.gaps().map { it.tick })
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(1, f.fixes().size)
        assertTrue(f.foreground.admitCapture(ticket)); runCurrent()
        assertEquals(2, f.fixes().size)
    }

    @Test fun foregroundAndProviderAcknowledgedForForeignRecordingCannotFeedMatchingRideId() = runTest {
        val f = Fixture(this, autoForeground = false)
        f.status(RideLocationStatus.Searching); f.start(); runCurrent()
        val foreign = CapturedHistoryGpsRecording("ride-1", 1, Any())
        val ticket = f.foreground.request(foreign) { }
        assertTrue(f.foreground.acknowledge(ticket, f.nativeService))
        assertTrue(f.foreground.admitCapture(ticket)); assertTrue(f.foreground.canCapture(foreign))
        assertFalse(f.foreground.canCapture(assertNotNull(f.controller.captureGpsRecording())))
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertTrue(f.fixes().isEmpty(), "Same ride ID/generation borrowed another controller's foreground")
        val actual = f.foreground.request(assertNotNull(f.controller.captureGpsRecording())) { }
        assertTrue(f.foreground.acknowledge(actual, f.nativeService)); assertTrue(f.foreground.admitCapture(actual)); runCurrent()
        assertEquals(1, f.fixes().size)
    }

    @Test fun captureRevocationWhileRecordingGateWaitsIsReconciledAfterGateReopens() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        advanceTimeBy(5)
        val context = assertNotNull(f.controller.captureTelemetryRecording())
        val queued = assertNotNull(f.controller.reserveQueuedTelemetry(context, f.tick(), "p"))
        val losing = backgroundScope.async { f.controller.execute(RecordingInput.LinkLost(7, f.tick())) }
        try {
            runCurrent(); assertFalse(losing.isCompleted); assertNull(f.controller.captureGpsRecording())
            assertTrue(f.foreground.fenceCapture(assertNotNull(f.nativeTicket))); runCurrent()
            assertTrue(f.gaps().isEmpty(), "Closed controller intake cannot accept the marker yet")
            assertEquals(HistoryOffer.ACCEPTED, queued.offer(HistoryBatch("ride-1",
                listOf(HistorySample(f.tick(), HistorySeriesKey("p", "power_w"), 100.0)), emptyList(), emptyList())))
            losing.await(); runCurrent()
            // Retraction: the marker used to be written at once. While BLE is away GPS facts are held
            // and land when it returns (or are dropped if the ride ends for the vehicle's absence).
            assertTrue(f.gaps().isEmpty(), "held while BLE is away")
            f.controller.execute(RecordingInput.Connected("bike", 8, f.tick()), null); runCurrent()
            assertEquals(listOf(f.tick()), f.gaps().map { it.tick })
            assertEquals(1, f.fixes().size)
        } finally { queued.discard(); losing.cancelAndJoin() }
    }

    @Test fun availableFixFromAnotherConsumerCannotEnterHistoryWithoutItsOwnDemand() = runTest {
        val f = Fixture(this)
        f.start()
        // A live shared provider does not prove history acquired location ownership.
        for ((index, consumer) in LocationConsumer.entries.filter { it != LocationConsumer.HISTORY_RECORDING }.withIndex()) {
            f.location.value = RideLocationState(RideLocationStatus.Available(f.fix(latitude = 56.0 + index)), setOf(consumer))
            runCurrent()
            assertTrue(f.fixes().isEmpty(), "Unowned coordinate from $consumer entered history")
            assertEquals(1, f.gaps().size)
        }
        f.location.value = f.location.value.copy(demands = f.location.value.demands + LocationConsumer.HISTORY_RECORDING)
        runCurrent()
        assertTrue(f.fixes().isEmpty(), "A cached point at the gap cannot close it merely because demand appeared")
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(1, f.fixes().size)
        assertEquals(1, f.gaps().size)
    }

    @Test fun removingHistoryDemandOpensGapEvenWhenSharedAvailableStatusDoesNotChange() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        advanceTimeBy(10)
        val unchanged = f.location.value.status
        f.location.value = f.location.value.copy(demands = setOf(LocationConsumer.MAP))
        runCurrent()
        assertEquals(unchanged, f.location.value.status)
        assertEquals(listOf(f.tick()), f.gaps().map { it.tick })
        assertEquals(1, f.fixes().size)
        advanceTimeBy(10)
        f.location.value = f.location.value.copy(demands = setOf(LocationConsumer.MAP, LocationConsumer.HISTORY_RECORDING))
        runCurrent()
        assertEquals(1, f.fixes().size, "A pre-gap cached coordinate cannot resume the route")
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(2, f.fixes().size)
        assertEquals(1, f.gaps().size)
    }

    @Test fun deniedGpsProducesOneGapPerOutageWithoutDisablingTelemetryOrBleReconnect() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.PermissionDenied); runCurrent()
        assertTrue(f.writer.offers.isEmpty())
        f.start(); runCurrent()
        assertEquals(1, f.gaps().size)
        f.status(RideLocationStatus.PermissionRequired); runCurrent()
        f.status(RideLocationStatus.ProviderDisabled); runCurrent()
        assertEquals(1, f.gaps().size)
        val sample = HistorySample(f.tick(), HistorySeriesKey("p", "power_w"), -100.0)
        assertEquals(HistoryOffer.ACCEPTED, f.controller.offerTelemetry(7, HistoryBatch("ride-1", listOf(sample), emptyList(), emptyList())))
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(1, f.fixes().size)
        f.controller.execute(RecordingInput.LinkLost(7, f.tick())); runCurrent()
        advanceTimeBy(10)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        f.status(RideLocationStatus.Searching); runCurrent()
        // GPS keeps going while BLE is absent; its facts are written once the vehicle is back.
        assertEquals(1, f.fixes().size); assertEquals(1, f.gaps().size)
        f.controller.execute(RecordingInput.Connected("bike", 8, f.tick()), null); runCurrent()
        assertEquals(2, f.fixes().size, "GPS must continue while BLE is absent")
        assertEquals(2, f.gaps().size)
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
    }

    @Test fun staleFixExpiresWithoutAnotherLocationEmissionAndTimerDoesNotRepeat() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix()))
        f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        assertTrue(f.gaps().isEmpty())
        advanceTimeBy(1_000); runCurrent()
        assertTrue(f.gaps().isEmpty(), "Exact maximum age remains admissible")
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(HistoryTick(11_001, 1_101, 1)), f.gaps().map { it.tick })
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, f.gaps().size)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(2, f.fixes().size)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, f.gaps().size)
    }

    @Test fun rawAcquisitionTimeIsPreservedAndRetainedLocationCannotBecomeNewRideRoute() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix(wall = 9_000_000)))
        f.start(); runCurrent()
        assertEquals(HistoryTick(9_000_000, 100, 1), f.fixes().single().tick)
        advanceTimeBy(100)
        f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1"))
        f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        assertTrue(f.writer.offers.filter { it.rideId == "ride-2" }.all { it.gps.isEmpty() })
        assertEquals(1, f.gaps().size)
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix(wall = 1))); runCurrent()
        assertEquals(HistoryTick(1, 201, 1), f.fixes().last().tick)
        assertEquals("ride-2", f.writer.offers.last().rideId)
    }

    @Test fun identicalFixIsNotResampledByDemandChangesAndOlderFixCannotResetExpiry() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix()))
        f.start(); runCurrent()
        advanceTimeBy(100)
        f.location.value = f.location.value.copy(demands = setOf(LocationConsumer.MAP, LocationConsumer.HISTORY_RECORDING)); runCurrent()
        assertEquals(1, f.fixes().size)
        f.status(RideLocationStatus.Available(f.fix(latitude = 57.0))); runCurrent()
        assertEquals(2, f.fixes().size)
        advanceTimeBy(100)
        f.status(RideLocationStatus.Available(f.fix(elapsed = 150, latitude = 58.0))); runCurrent()
        assertEquals(2, f.fixes().size)
        advanceTimeBy(901); runCurrent()
        assertEquals(1, f.gaps().size, "Expiry belongs to the last accepted fix at elapsed 200")
    }

    @Test fun missingMeasurementMetadataAndFixAcquiredBeforeOutageCannotCloseGap() = runTest {
        val f = Fixture(this)
        f.start(); runCurrent()
        assertEquals(1, f.gaps().size)
        f.status(RideLocationStatus.Available(f.fix().copy(measurementAtEpochMillis = null))); runCurrent()
        assertTrue(f.fixes().isEmpty())
        advanceTimeBy(10)
        f.status(RideLocationStatus.PermissionDenied); runCurrent()
        f.status(RideLocationStatus.Available(f.fix(elapsed = 100))); runCurrent()
        assertTrue(f.fixes().isEmpty(), "A fix at/before the gap cannot resume the route")
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(1, f.fixes().size)
        assertEquals(1, f.gaps().size)
    }

    @Test fun stoppingAndClosingObserverCancelPendingExpiryAndPreventFurtherPublication() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix()))
        f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1")); runCurrent()
        val stoppedCount = f.writer.offers.size
        advanceTimeBy(2_000); runCurrent()
        assertEquals(stoppedCount, f.writer.offers.size)
        f.start(); runCurrent()
        f.recorder.close()
        val closedCount = f.writer.offers.size
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        advanceTimeBy(2_000); runCurrent()
        assertEquals(closedCount, f.writer.offers.size)
    }

    @Test fun parentScopeCancellationStopsObserverWithoutStoppingRecording() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix()))
        f.start(); runCurrent()
        assertEquals(1, f.fixes().size)
        f.scope.cancel()
        val count = f.writer.offers.size
        advanceTimeBy(2_000)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(count, f.writer.offers.size)
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
    }

    @Test fun clockFailureIsContainedObserverRecoversAndAffectedRideEndsInterrupted() = runTest {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> escaped += error })
        try {
            val f = Fixture(this, scope)
            f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
            f.beforeRead = { error("clock unavailable") }
            f.status(RideLocationStatus.Searching); runCurrent()
            f.beforeRead = { }
            advanceTimeBy(1)
            f.status(RideLocationStatus.Available(f.fix())); runCurrent()
            assertEquals(2, f.fixes().size, "A failed clock read must not kill the observer")
            f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1"))
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            assertTrue(escaped.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun throwingGpsOfferIsContainedAndRetriedOnlyOnANewFixWithInterruptedVerdict() = runTest {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> escaped += error })
        try {
            val f = Fixture(this, scope)
            f.start(); runCurrent()
            advanceTimeBy(1)
            f.writer.beforeOffer = { if (it.gps.isNotEmpty()) error("offer failed") }
            f.status(RideLocationStatus.Available(f.fix())); runCurrent()
            assertTrue(f.fixes().isEmpty())
            f.writer.beforeOffer = { }
            advanceTimeBy(1)
            f.status(RideLocationStatus.Available(f.fix())); runCurrent()
            assertEquals(1, f.fixes().size, "The collector must survive an optional GPS write error")
            f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1"))
            assertEquals(listOf(HistoryRideStatus.INTERRUPTED), f.writer.statuses)
            assertTrue(escaped.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun clockFailureAfterOwnerReplacementCannotPoisonTheNewRide() = runTest {
        val escaped = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error -> escaped += error })
        try {
            val f = Fixture(this, scope)
            f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
            advanceTimeBy(1)
            f.beforeRead = {
                f.beforeRead = { }
                val change = async {
                    f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1"))
                    f.start()
                }
                runCurrent()
                assertTrue(change.isCompleted)
                error("retired clock failure")
            }
            f.status(RideLocationStatus.Searching); runCurrent()
            advanceTimeBy(1)
            f.status(RideLocationStatus.Available(f.fix())); runCurrent()
            f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-2"))
            assertEquals(listOf(HistoryRideStatus.COMPLETED, HistoryRideStatus.COMPLETED), f.writer.statuses)
            assertTrue(f.writer.offers.any { it.rideId == "ride-2" && it.gps.isNotEmpty() })
            assertTrue(escaped.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun delayedStaleProviderFixCannotCreateAnOutageWhileANewerFixIsFresh() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, f.gaps().size)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        assertEquals(2, f.fixes().size)
        advanceTimeBy(1)
        f.status(RideLocationStatus.Available(f.fix(elapsed = 100, latitude = 58.0))); runCurrent()
        assertEquals(1, f.gaps().size, "Stale out-of-order provider state must not invalidate the newer accepted fix")
        assertEquals(2, f.fixes().size)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.gaps().size)
        assertEquals(HistoryTick(16_001, 6_101, 1), f.gaps().last().tick)
    }

    @Test fun clockCancellationIsNotConvertedIntoRecordingFailureOrParentScopeFailure() = runTest {
        val f = Fixture(this)
        f.status(RideLocationStatus.Available(f.fix())); f.start(); runCurrent()
        f.beforeRead = { throw CancellationException("observer cancelled") }
        f.status(RideLocationStatus.Searching); runCurrent()
        assertTrue(f.scope.isActive)
        f.beforeRead = { }
        advanceTimeBy(2_000)
        f.status(RideLocationStatus.Available(f.fix())); runCurrent()
        // collectLatest cancels the individual action; its parent collector remains usable.
        assertEquals(2, f.fixes().size)
        f.controller.execute(RecordingInput.Stop(f.tick(), 7, "ride-1"))
        assertEquals(listOf(HistoryRideStatus.COMPLETED), f.writer.statuses)
        assertNull(f.controller.telemetryFailure.value)
    }

    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val offers = mutableListOf<HistoryBatch>()
        val statuses = mutableListOf<HistoryRideStatus>()
        var beforeOffer: (HistoryBatch) -> Unit = { }
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) = Unit
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { beforeOffer(batch); offers += batch; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { statuses += status }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
}

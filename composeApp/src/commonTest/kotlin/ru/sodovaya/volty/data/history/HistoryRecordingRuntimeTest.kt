package ru.sodovaya.volty.data.history

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.ble.*
import ru.sodovaya.volty.data.bms.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.location.RideLocationState
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class HistoryRecordingRuntimeTest {
    @Test fun locationRetryUsesExistingDemandWithoutAllocatingAnotherWriterOrStartingAnUnpreparedRuntime() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.UNAVAILABLE), repository.retryLocation())
            assertEquals(0, f.allocations); assertEquals(0, f.preparations)
            f.failService = true
            val binding = f.runtime.prepare(); f.start(binding, channel)
            assertTrue(f.natives.isEmpty()); assertEquals(listOf("ride"), f.writer.begins)
            f.failService = false
            assertEquals(HistoryRecordingActionResult.Applied, repository.retryLocation())
            assertEquals(1, f.natives.size); assertEquals(1, f.natives.single().starts)
            assertEquals(1, f.allocations); assertEquals(1, f.preparations); assertEquals(listOf("ride"), f.writer.begins)
            assertEquals(HistoryRecordingRouteStatus.STARTING, repository.states.first().route)
        } finally { f.failService = false; f.runtime.close(); channel.close() }
    }
    @Test fun routeObserverSeesAdmissionFenceWhileDurableStopStillAwaitsItsReceipt() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val gate = CompletableDeferred<Unit>()
        var collector: Job? = null
        var stopping: Deferred<HistoryRecordingActionResult>? = null
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel)
            f.writer.diagnostics.value = HistoryWriterDiagnostics(accepting = true)
            f.natives.single().state.value = HistoryLocationCaptureState(capturing = true)
            val observed = mutableListOf<HistoryRecordingStatus>()
            collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.states.collect { observed += it } }
            runCurrent(); assertEquals(HistoryRecordingRouteStatus.RECORDING, observed.last().route)
            val controls = assertNotNull(repository.captureControls(observed.last()))
            f.writer.finishGate = gate
            stopping = backgroundScope.async { controls.stop("ride") }
            runCurrent()
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
            assertFalse(stopping.isCompleted); assertNull(binding.controller.captureGpsRecording())
            assertEquals(HistoryRecordingRouteStatus.STOPPING, observed.last().route)
            assertNotNull(observed.last().ride, "Stop has not received its durable finish acknowledgement")
            f.writer.diagnostics.value = HistoryWriterDiagnostics(accepting = false)
            runCurrent()
            assertEquals(HistoryRecordingRouteStatus.STOPPING, observed.last().route)
            assertFalse(observed.last().storageFailure,
                "The writer fences intake while draining; that alone is not a storage failure")
            gate.complete(Unit); assertEquals(HistoryRecordingActionResult.Applied, stopping.await())
            runCurrent(); assertEquals(HistoryRecordingRouteStatus.IDLE, observed.last().route)
        } finally {
            gate.complete(Unit); stopping?.cancelAndJoin(); collector?.cancelAndJoin(); f.runtime.close(); channel.close()
        }
    }
    @Test fun recordingRepositoryObservationNeverAllocatesAndOnlyExposesPreparedRuntime() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        try {
            assertEquals(HistoryRecordingStatus(), repository.states.first())
            assertNull(repository.captureControls(repository.states.first())); assertEquals(0, f.preparations); assertEquals(0, f.allocations)
            f.runtime.prepare()
            val prepared = repository.states.first()
            assertTrue(prepared.available); assertNull(prepared.ride); assertFalse(prepared.canStart); assertFalse(prepared.canStop)
            assertNull(repository.captureControls(prepared)); assertTrue(f.service.isEmpty())
            f.runtime.close(); assertEquals(HistoryRecordingStatus(), repository.states.first())
        } finally { f.runtime.close() }
    }
    @Test fun recordingRepositoryUsesActualRouteAndWriterStateAndExplicitStartStopAcknowledgements() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel)
            // A temporary intake fence is not evidence that persistence failed.
            assertFalse(repository.states.first().storageFailure)
            f.writer.diagnostics.value = HistoryWriterDiagnostics(failure = "append failed")
            assertTrue(repository.states.first().storageFailure)
            f.writer.diagnostics.value = HistoryWriterDiagnostics(closed = true)
            assertTrue(repository.states.first().storageFailure)
            f.writer.diagnostics.value = HistoryWriterDiagnostics(accepting = true)
            val recording = repository.states.first()
            assertEquals("ride", recording.ride?.id); assertTrue(recording.canStop); assertFalse(recording.canStart)
            assertFalse(recording.storageFailure); assertEquals(HistoryRecordingRouteStatus.STARTING, recording.route)
            assertFalse(recording.telemetryLimited, "qualified ANT telemetry remains numerically available")
            f.natives.single().state.value = HistoryLocationCaptureState(capturing = true)
            assertEquals(HistoryRecordingRouteStatus.RECORDING, repository.states.first().route)
            val controls = assertNotNull(repository.captureControls(repository.states.first()))
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED), controls.stop("other"))
            assertTrue(f.writer.finishes.isEmpty())
            assertEquals(HistoryRecordingActionResult.Applied, controls.stop("ride"))
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
            val stopped = repository.states.first(); assertNull(stopped.ride); assertTrue(stopped.manuallyStopped); assertTrue(stopped.canStart)
            assertEquals(HistoryRecordingRouteStatus.IDLE, stopped.route)
            f.nextId = "ride-2"
            assertEquals(HistoryRecordingActionResult.Applied, assertNotNull(repository.captureControls(stopped)).start())
            assertEquals("ride-2", repository.states.first().ride?.id)
            assertEquals(listOf("ride", "ride-2"), f.writer.begins)
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED), controls.stop("ride"))
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
        } finally { f.runtime.close(); channel.close() }
    }
    // Retraction: KingSong formerly had no numeric history output. Its common
    // decoder now publishes fresh fields; GPS-only coverage uses an explicit
    // empty source plan in NativeHistoryGpsFallbackTest instead.
    @Test fun savedControllerOnlyLinkStartsTimeAndLocationRecordingWithCommonTelemetry() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(capacity = 4)
        val vehicle = Vehicle("kingsong", "KingSong", "wheel", emptyList(),
            controllers = listOf(Controller(0, "KingSong", ControllerType.KINGSONG, "KS")),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
        val spec = LinkSpec("KS", ProtocolKind.KINGSONG, listOf(OwnedSource(0), OwnedSource(1)), listOf(OwnedSource(0)))
        try {
            val binding = f.runtime.prepare()
            binding.ingress.installNativePlan(vehicle.id, listOf(spec), vehicle) { true }
            val capture = assertNotNull(binding.ingress.capture(Emitter(), "attempt", spec, vehicle,
                SampleSessionOwnership { true }, channel))
            assertTrue(capture.prepareForObservation())

            val status = repository.states.first()
            assertNotNull(status.ride, "A saved controller-only wheel starts time and GPS recording")
            assertFalse(status.telemetryLimited)
            assertEquals(listOf(true), f.service, "Location demand follows the real ride")
            assertFalse(status.storageFailure)
        } finally { f.runtime.close(); channel.close() }
    }
    @Test fun capturedRecordingControlsCannotRetargetAReplacementPhysicalSelection() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel)
            val captured = assertNotNull(repository.captureControls(repository.states.first()))
            val other = f.vehicle.copy(id = "other")
            binding.ingress.installNativePlan(other.id, listOf(f.spec), other) { true }
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED), captured.start())
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED), captured.stop("ride"))
            assertEquals(listOf("ride"), f.writer.begins); assertEquals(listOf<String?>("ride"), f.writer.finishes)
        } finally { f.runtime.close(); channel.close() }
    }
    @Test fun clickOnAStaleDisplayedStoppedStatusCannotStartTheNewlySelectedVehicle() = runTest {
        val f = Fixture(this); val repository = RuntimeHistoryRecordingRepository(f.runtime)
        val channel = kotlinx.coroutines.channels.Channel<Sample>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel)
            assertEquals(HistoryRecordingActionResult.Applied,
                assertNotNull(repository.captureControls(repository.states.first())).stop("ride"))
            val displayed = repository.states.first(); assertTrue(displayed.canStart)
            val other = f.vehicle.copy(id = "other"); f.nextId = "ride-2"
            binding.ingress.installNativePlan(other.id, listOf(f.spec), other) { true }
            val incoming = assertNotNull(binding.ingress.capture(Emitter(), "replacement", f.spec, other,
                SampleSessionOwnership { true }, channel))
            assertTrue(incoming.prepareForObservation())
            val replacement = repository.states.first(); assertTrue(replacement.canStart)
            val result = assertNotNull(repository.captureControls(displayed)).start()
            assertEquals(listOf("ride"), f.writer.begins, "Displayed old controls must not start the new vehicle")
            assertEquals(HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED), result)
            assertNotEquals(displayed, replacement, "A physical selection change must remain observable")
        } finally { f.runtime.close(); channel.close() }
    }
    @Test fun observingRuntimeDoesNotPrepareOrAllocateAndOnlyACompleteBindingIsPublished() = runTest {
        val f = Fixture(this)
        try {
            assertNull(f.runtime.status.value.binding)
            assertFalse(f.runtime.status.value.closing)
            assertEquals(0, f.preparations); assertEquals(0, f.allocations)
            val binding = f.runtime.prepare()
            assertSame(binding, f.runtime.status.value.binding)
            assertSame(binding, f.runtime.prepare()); assertEquals(1, f.allocations)
            f.runtime.close()
            assertNull(f.runtime.status.value.binding); assertTrue(f.runtime.status.value.closing)
        } finally { f.runtime.close() }
    }
    @Test fun blockedOrFailedPreparationNeverPublishesPartialUiControls() = runTest {
        val f = Fixture(this); val gate = CompletableDeferred<Unit>(); f.prepareGate = gate
        supervisorScope {
            val preparation = async { f.runtime.prepare() }
            try {
                runCurrent(); assertEquals(1, f.preparations); assertEquals(0, f.allocations)
                assertNull(f.runtime.status.value.binding)
                f.failPrepare = true; gate.complete(Unit)
                assertFailsWith<IllegalStateException> { preparation.await() }
                assertNull(f.runtime.status.value.binding)
                f.failPrepare = false
                val ready = f.runtime.prepare(); assertSame(ready, f.runtime.status.value.binding)
            } finally { gate.complete(Unit); preparation.cancel(); f.runtime.close() }
        }
    }
    @Test fun failedShutdownRetiresUiControlsWhileKeepingOriginalResourcesForRetry() = runTest {
        val f = Fixture(this)
        try {
            f.runtime.prepare(); f.writer.failClose = true
            assertFailsWith<IllegalStateException> { f.runtime.close() }
            assertNull(f.runtime.status.value.binding); assertTrue(f.runtime.status.value.closing)
            assertEquals(1, f.allocations); assertEquals(0, f.writer.closed)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            assertNull(f.runtime.status.value.binding)
            f.writer.failClose = false; f.runtime.close()
            assertEquals(1, f.allocations); assertEquals(1, f.writer.closed)
        } finally { f.writer.failClose = false; f.runtime.close() }
    }
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<String>()
        val finishes = mutableListOf<String?>()
        val values = mutableListOf<Double?>()
        var closed = 0
        var closeAttempts = 0
        var failClose = false
        var failFinish = false
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += ride.id }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer {
            values += batch.samples.map { it.value }; return HistoryOffer.ACCEPTED
        }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            finishes += rideId; finishGate?.await(); check(!failFinish) { "finish unavailable" }
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() { closeAttempts++; check(!failClose) { "close unavailable" }; closed++ }
    }
    private class Native(val scope: CoroutineScope) : HistoryRecordingLocationSession {
        override val state = MutableStateFlow(HistoryLocationCaptureState())
        var starts = 0
        var closes = 0
        var cleanup = true
        override fun start(): Deferred<Boolean> { starts++; return CompletableDeferred() }
        override suspend fun close(): Boolean { closes++; return cleanup }
    }
    private class Emitter : BmsProtocol() {
        override val uuids = BmsUuids("s", "n", "w")
        override fun handshakeCommands() = emptyList<ByteArray>()
        override fun pollCommands() = emptyList<ByteArray>()
        override fun latestData(packIndex: Int): BmsData? = null
        override fun reset() = Unit
        override fun onNotification(data: ByteArray) {
            observeTelemetry(TelemetryOrigin.BATTERY, 0) {
                listOf(TelemetryReading.measured(TelemetryMetric.CURRENT, data.single().toDouble()))
            }
        }
    }
    private class Fixture(test: TestScope) {
        val writer = Writer()
        var preparations = 0
        var failPrepare = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var allocations = 0
        var failAllocation = false
        var onAllocate: (() -> Unit)? = null
        var elapsed = 10L
        var nextId = "ride"
        var failService = false
        val service = mutableListOf<Boolean>()
        val natives = mutableListOf<Native>()
        val opportunities = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val clock = HistoryRecordingClock({
            preparations++; prepareGate?.await(); check(!failPrepare) { "recovery unavailable" }; 7
        }, { 10000 + elapsed }, { elapsed })
        val runtime = HistoryRecordingRuntime(clock, StandardTestDispatcher(test.testScheduler),
            { check(!failAllocation) { "writer unavailable" }; allocations++; onAllocate?.invoke(); writer }, MutableStateFlow(RideLocationState()),
            HistoryLocationForegroundAuthority({ true }) { "10000000-0000-0000-0000-000000000001" },
            { service += it; check(!it || !failService) { "service unavailable" } },
            { scope, _ -> Native(scope).also { natives += it } }, { nextId }, opportunities)
        val vehicle = Vehicle("bike", "Bike", "bicycle", listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")), emptyList(),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
        val spec = LinkSpec("AA", ProtocolKind.ANT, listOf(OwnedSource(0)))
        suspend fun start(binding: HistoryRecordingRuntime.Binding, channel: kotlinx.coroutines.channels.SendChannel<Sample>): SessionTelemetryCapture {
            binding.ingress.installNativePlan("bike", listOf(spec), vehicle) { true }
            return assertNotNull(binding.ingress.capture(Emitter(), "attempt", spec, vehicle,
                SampleSessionOwnership { true }, channel)).also { assertTrue(it.prepareForObservation()) }
        }
    }

    @Test fun writerFactoryFailureCanRetryWithoutRunningRecoveryAgain() = runTest {
        val f = Fixture(this); f.failAllocation = true
        try {
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            assertEquals(0, f.allocations); assertEquals(1, f.preparations)
            f.failAllocation = false; val binding = f.runtime.prepare()
            assertSame(binding, f.runtime.prepare()); assertEquals(1, f.preparations)
            assertEquals(1, f.allocations)
        } finally { f.failAllocation = false; f.runtime.close() }
    }

    @Test fun failedWriterDisposalRetainsSameInstanceForCloseRetry() = runTest {
        val f = Fixture(this)
        try {
            f.runtime.prepare(); f.writer.failClose = true
            assertFailsWith<IllegalStateException> { f.runtime.close() }
            assertEquals(1, f.writer.closeAttempts); assertEquals(0, f.writer.closed)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            f.writer.failClose = false; f.runtime.close(); f.runtime.close()
            assertEquals(2, f.writer.closeAttempts); assertEquals(1, f.writer.closed)
            assertEquals(1, f.allocations)
        } finally { f.writer.failClose = false; f.runtime.close() }
    }

    @Test fun partialAssemblyCleanupFailureRetainsWriterForTerminalRetry() = runTest {
        val f = Fixture(this); f.writer.failClose = true
        var closing: Deferred<Result<Unit>>? = null
        f.onAllocate = {
            closing = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { runCatching { f.runtime.close() } }
        }
        try {
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            assertIs<IllegalStateException>(assertNotNull(closing).await().exceptionOrNull())
            assertEquals(2, f.writer.closeAttempts); assertEquals(0, f.writer.closed)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            f.writer.failClose = false; f.runtime.close(); f.runtime.close()
            assertEquals(3, f.writer.closeAttempts); assertEquals(1, f.writer.closed)
            assertEquals(1, f.allocations)
        } finally { f.writer.failClose = false; closing?.cancelAndJoin(); f.runtime.close() }
    }

    @Test fun concurrentPreparationWaitsForRecoveryAndPublishesOneBindingAndWriter() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); f.prepareGate = release
        val first = backgroundScope.async { f.runtime.prepare() }
        val second = backgroundScope.async { f.runtime.prepare() }
        try {
            runCurrent(); assertEquals(0, f.allocations); assertFalse(first.isCompleted); assertFalse(second.isCompleted)
            release.complete(Unit)
            assertSame(first.await(), second.await())
            assertEquals(1, f.preparations); assertEquals(1, f.allocations)
        } finally { release.complete(Unit); first.cancelAndJoin(); second.cancelAndJoin(); f.runtime.close() }
        assertEquals(1, f.writer.closed)
    }

    @Test fun failedRecoveryAllocatesNothingAndCanBeRetriedBeforeFirstWriter() = runTest {
        val f = Fixture(this); f.failPrepare = true
        try {
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            assertEquals(0, f.allocations)
            f.failPrepare = false; val binding = f.runtime.prepare()
            assertSame(binding, f.runtime.prepare())
            assertEquals(2, f.preparations); assertEquals(1, f.allocations)
        } finally { f.failPrepare = false; f.runtime.close() }
    }

    @Test fun closeDuringRecoveryPreventsWriterPublicationAndAllLaterPreparation() = runTest {
        val f = Fixture(this); val release = CompletableDeferred<Unit>(); f.prepareGate = release
        val preparing = backgroundScope.async { runCatching { f.runtime.prepare() } }
        var closing: Job? = null
        try {
            runCurrent(); assertEquals(1, f.preparations)
            closing = backgroundScope.launch { f.runtime.close() }; runCurrent()
            release.complete(Unit); closing.join()
            assertIs<IllegalStateException>(preparing.await().exceptionOrNull())
            assertEquals(0, f.allocations)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
        } finally { release.complete(Unit); preparing.cancelAndJoin(); closing?.cancelAndJoin(); f.runtime.close() }
    }

    @Test fun managedRideClosesNativeDemandAndWriterWithoutWaitingForStartupAck() = runTest {
        val f = Fixture(this); val channel = sampleFunnelChannel(4)
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel)
            assertEquals(listOf("ride"), f.writer.begins); assertEquals(listOf(true), f.service)
            assertEquals(1, f.natives.single().starts)
            f.runtime.close(); f.runtime.close()
            assertNull(binding.controller.state.value.intent.ride)
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
            assertTrue(f.natives.single().closes >= 1); assertFalse(f.service.last())
            assertFalse(f.natives.single().scope.isActive); assertEquals(1, f.writer.closed)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
        } finally { channel.cancel(); f.runtime.close() }
    }

    @Test fun failedNativeCleanupRetainsRuntimeAndWriterUntilOriginalCleanupAck() = runTest {
        val f = Fixture(this); val channel = sampleFunnelChannel(4)
        try {
            f.start(f.runtime.prepare(), channel); val native = f.natives.single(); native.cleanup = false
            assertFailsWith<IllegalStateException> { f.runtime.close() }
            assertEquals(0, f.writer.closed); assertTrue(native.scope.isActive); assertFalse(f.service.last())
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
            val attempts = native.closes; assertTrue(attempts >= 1)
            native.cleanup = true; f.runtime.close()
            assertTrue(native.closes > attempts); assertEquals(1, f.natives.size)
            assertEquals(1, f.writer.closed); assertFalse(native.scope.isActive)
        } finally { f.natives.forEach { it.cleanup = true }; channel.cancel(); f.runtime.close() }
    }

    @Test fun failedDurableFinishCannotDisposeWriterOrReopenRuntime() = runTest {
        val f = Fixture(this); val channel = sampleFunnelChannel(4)
        try {
            val binding = f.runtime.prepare(); f.start(binding, channel); f.writer.failFinish = true
            assertFailsWith<IllegalStateException> { f.runtime.close() }
            assertEquals(0, f.writer.closed); assertNull(binding.controller.captureTelemetryRecording())
            assertEquals("ride", binding.controller.state.value.intent.ride?.id)
            assertFailsWith<IllegalStateException> { f.runtime.prepare() }
            f.writer.failFinish = false; f.runtime.close()
            assertEquals(listOf<String?>("ride", "ride"), f.writer.finishes)
            assertEquals(1, f.writer.closed); assertEquals(1, f.allocations)
        } finally { f.writer.failFinish = false; channel.cancel(); f.runtime.close() }
    }

    @Test fun foregroundOpportunityRetriesThroughControllerAndClearsDemandFailure() = runTest {
        val f = Fixture(this); val channel = sampleFunnelChannel(4)
        try {
            val binding = f.runtime.prepare(); runCurrent(); f.failService = true
            f.start(binding, channel); assertNotNull(binding.controller.state.value.demandFailure)
            assertTrue(f.natives.isEmpty())
            f.failService = false; assertTrue(f.opportunities.tryEmit(Unit)); runCurrent()
            assertNull(binding.controller.state.value.demandFailure)
            assertEquals(1, f.natives.single().starts); assertEquals(1, f.allocations)
            assertEquals(listOf("ride"), f.writer.begins)
        } finally { f.failService = false; channel.cancel(); f.runtime.close() }
    }

    @Test fun terminalCloseWaitsAcceptedQueueAndSurvivesRequestingCallerCancellation() = runTest {
        val f = Fixture(this); val channel = sampleFunnelChannel(4)
        val binding = f.runtime.prepare(); val capture = f.start(binding, channel)
        f.elapsed = 20; assertTrue(capture.processNotification(byteArrayOf(9)))
        val sample = assertIs<HistoryTelemetrySample>(channel.tryReceive().getOrNull())
        val release = CompletableDeferred<Unit>(); f.writer.finishGate = release
        val close = backgroundScope.launch { f.runtime.close() }
        try {
            runCurrent(); assertFalse(close.isCompleted); assertEquals(0, f.writer.closed)
            assertFalse(capture.processNotification(byteArrayOf(10)))
            assertEquals(HistoryOffer.ACCEPTED, sample.consume()); runCurrent()
            assertEquals(listOf<String?>("ride"), f.writer.finishes)
            close.cancel(); runCurrent(); assertFalse(close.isCompleted)
            release.complete(Unit); close.join()
            assertEquals(listOf<Double?>(9.0), f.writer.values); assertEquals(1, f.writer.closed)
            assertNull(binding.controller.state.value.intent.ride)
        } finally { sample.discard(); release.complete(Unit); channel.cancel(); close.cancelAndJoin(); f.runtime.close() }
    }
}

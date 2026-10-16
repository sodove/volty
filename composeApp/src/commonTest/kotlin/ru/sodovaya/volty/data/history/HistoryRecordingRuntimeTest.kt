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
            { scope, _ -> Native(scope).also { natives += it } }, { "ride" }, opportunities)
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

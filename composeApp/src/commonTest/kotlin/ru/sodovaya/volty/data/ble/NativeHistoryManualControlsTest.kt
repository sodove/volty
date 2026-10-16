package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)
class NativeHistoryManualControlsTest {
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<String>()
        val ends = mutableListOf<String?>()
        var finishGate: CompletableDeferred<Unit>? = null
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += ride.id }
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) {
            ends += rideId; finishGate?.await()
        }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private class Fixture {
        val writer = Writer()
        private var sequence = 0
        private var elapsed = 0L
        var owns = true
        val source = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
        val spec = LinkSpec("AA", ProtocolKind.ANT, listOf(OwnedSource(0)))
        val vehicle = Vehicle("bike", "Bike", "bicycle", listOf(Pack(0, "Pack", BmsType.ANT_BMS, "AA")), emptyList(),
            chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride-${++sequence}" }, { 0 })
        var failures = 0
        val lifecycle = NativeHistoryRecordingLifecycle(controller, { HistoryTick(10000 + ++elapsed, elapsed, 1) },
            { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to source) }, { failures++ })
        suspend fun install() = lifecycle.install("bike", listOf(spec), vehicle) { owns }
        suspend fun ready(protocol: Any) = assertNotNull(lifecycle.binding(spec, protocol) { owns }).prepare()
    }

    @Test fun controlsRequireOwnedQualifiedSelectionAndAReadyAttemptToStart() = runTest {
        val f = Fixture()
        assertNull(f.lifecycle.manualControls())
        f.install()
        val controls = assertNotNull(f.lifecycle.manualControls())
        assertFalse(controls.start()); assertFalse(controls.stop("missing"))
        assertTrue(f.writer.begins.isEmpty())
        f.ready(Any())
        assertEquals(listOf("ride-1"), f.writer.begins)
        assertTrue(controls.stop("ride-1"))
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertTrue(controls.start())
        assertEquals(listOf("ride-1", "ride-2"), f.writer.begins)
        assertFalse(controls.stop("ride-1"))
        assertEquals("ride-2", f.controller.state.value.intent.ride?.id)
    }

    @Test fun manualStopSurvivesReconnectUntilExplicitRestart() = runTest {
        val f = Fixture(); f.install(); val first = Any(); f.ready(first)
        val controls = assertNotNull(f.lifecycle.manualControls())
        assertTrue(controls.stop("ride-1"))
        f.lifecycle.retire(first)
        assertFalse(controls.start(), "No ready link can be fabricated by the button")
        f.ready(Any())
        assertEquals(listOf("ride-1"), f.writer.begins)
        assertNull(f.controller.state.value.intent.ride)
        assertTrue(controls.start())
        assertEquals(listOf("ride-1", "ride-2"), f.writer.begins)
        assertEquals(0, f.failures)
    }

    @Test fun oldScreenCannotStopOrRestartAfterEvenSamePlanReplacement() = runTest {
        val f = Fixture(); f.install(); f.ready(Any())
        val old = assertNotNull(f.lifecycle.manualControls())
        f.install(); f.ready(Any())
        assertEquals("ride-1", f.controller.state.value.intent.ride?.id)
        assertFalse(old.stop("ride-1")); assertFalse(old.start())
        assertTrue(f.writer.ends.isEmpty())
        val fresh = assertNotNull(f.lifecycle.manualControls())
        assertTrue(fresh.stop("ride-1")); assertTrue(fresh.start())
        assertEquals("ride-2", f.controller.state.value.intent.ride?.id)
        assertFalse(fresh.stop("ride-1"))
    }

    @Test fun revokedNativeIntentAndDisconnectInvalidateCapturedControls() = runTest {
        val f = Fixture(); f.install(); f.ready(Any())
        val controls = assertNotNull(f.lifecycle.manualControls())
        f.owns = false
        assertNull(f.lifecycle.manualControls())
        assertFalse(controls.stop("ride-1")); assertFalse(controls.start())
        assertTrue(f.writer.ends.isEmpty())
        f.owns = true
        f.lifecycle.disconnect { true }
        assertNull(f.lifecycle.manualControls())
        assertFalse(controls.start()); assertFalse(controls.stop("ride-1"))
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
    }

    @Test fun acceptedStopFinishesEvenWhenRequestingScreenIsCancelled() = runTest {
        val f = Fixture(); f.install(); f.ready(Any())
        val controls = assertNotNull(f.lifecycle.manualControls())
        val release = CompletableDeferred<Unit>(); f.writer.finishGate = release
        val stopped = backgroundScope.launch { controls.stop("ride-1") }
        try {
            runCurrent(); assertEquals(listOf<String?>("ride-1"), f.writer.ends)
            stopped.cancel(); runCurrent()
            assertFalse(stopped.isCompleted)
        } finally { release.complete(Unit); stopped.cancelAndJoin() }
        assertNull(f.controller.state.value.intent.ride)
        assertEquals(listOf<String?>("ride-1"), f.writer.ends)
        assertEquals(0, f.failures)
    }

    @Test fun unsupportedOrEmptySelectionsCannotOfferManualRecording() = runTest {
        val f = Fixture()
        f.lifecycle.install("bike", emptyList(), f.vehicle) { true }
        assertNull(f.lifecycle.manualControls())
        val failed = NativeHistoryRecordingLifecycle(f.controller, { HistoryTick(1, 1, 1) },
            { _, _ -> error("unsupported producer") }, { f.failures++ })
        failed.install("bike", listOf(f.spec), f.vehicle) { true }
        assertNull(failed.manualControls())
        assertEquals(1, f.failures)
        assertTrue(f.writer.begins.isEmpty())
    }

    @Test fun queuedStartRechecksNativeOwnershipAfterLifecycleMutexWait() = runTest {
        val f = Fixture(); f.install(); f.ready(Any())
        val controls = assertNotNull(f.lifecycle.manualControls())
        val release = CompletableDeferred<Unit>(); f.writer.finishGate = release
        val stopped = backgroundScope.async { controls.stop("ride-1") }
        var restarted: Deferred<Boolean>? = null
        try {
            runCurrent(); assertEquals(listOf<String?>("ride-1"), f.writer.ends)
            restarted = backgroundScope.async { controls.start() }
            runCurrent(); assertFalse(restarted.isCompleted)
            f.owns = false
            release.complete(Unit)
            assertTrue(stopped.await())
            assertFalse(restarted.await())
            assertEquals(listOf("ride-1"), f.writer.begins)
            assertNull(f.controller.state.value.intent.ride)
        } finally { release.complete(Unit); stopped.cancelAndJoin(); restarted?.cancelAndJoin() }
    }

    @Test fun ingressExposesControlsOnlyForItsManagedInstalledLifecycle() = runTest {
        val f = Fixture()
        val unmanaged = HistoryTelemetryIngress(f.controller, { HistoryTick(1, 1, 1) })
        assertNull(unmanaged.manualControls())
        val managed = HistoryTelemetryIngress(f.controller, { HistoryTick(1, 1, 1) },
            { _, _ -> mapOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0) to f.source) }, manageConnections = true)
        assertNull(managed.manualControls())
        managed.installNativePlan("bike", listOf(f.spec), f.vehicle) { true }
        val controls = assertNotNull(managed.manualControls())
        assertFalse(controls.start())
        managed.disconnectNative { true }
        assertNull(managed.manualControls())
        assertFalse(controls.start())
    }
}

package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class NativeHistoryPhysicalOwnershipTest {
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<List<HistorySource>>()
        val finished = mutableListOf<String?>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += sources }
        override fun offer(generation: Long, batch: HistoryBatch) = HistoryOffer.ACCEPTED
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { finished += rideId }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private val a = LinkSpec("AA", ProtocolKind.ANT, listOf(OwnedSource(0)))
    private val b = LinkSpec("BB", ProtocolKind.ANT, listOf(OwnedSource(1)))
    private fun vehicle(aliasA: String? = null, aliasB: String? = null) = Vehicle(
        "bike", "Bike", "bicycle", listOf(Pack(0, "Direct", BmsType.ANT_BMS, "AA", aliasGroup = aliasA),
            Pack(1, "Hosted", BmsType.VESC_BMS, "BB", aliasGroup = aliasB)), emptyList(),
        chemistry = Chemistry.LI_ION_NMC, createdAt = Instant.fromEpochSeconds(0))
    private class Fixture {
        val writer = Writer()
        val controller = RideRecordingController(writer, HistoryRecordingDemand {}, { "ride" }, { 0 })
        var failures = 0
        var plannerCalls = 0
        private var time = 0L
        // Deliberately unique per-path IDs: ID deduplication alone cannot detect
        // two paths claiming the same physical battery or global source slot.
        val lifecycle = NativeHistoryRecordingLifecycle(controller, { HistoryTick(1000 + ++time, time, 1) },
            { spec, _ ->
                plannerCalls++
                buildMap {
                    spec.ownedPacks.forEachIndexed { local, owned -> put(HistoryTelemetrySource(TelemetryOrigin.BATTERY, local),
                        HistorySource("${spec.address}:pack:${owned.globalIndex}:$local", HistorySourceKind.BATTERY, "Pack")) }
                    spec.ownedControllers.forEachIndexed { local, owned -> put(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, local),
                        HistorySource("${spec.address}:controller:${owned.globalIndex}:$local", HistorySourceKind.CONTROLLER, "ESC")) }
                }
            }, { failures++ })
    }

    private suspend fun rejected(specs: List<LinkSpec>, profile: Vehicle) {
        val f = Fixture()
        f.lifecycle.install("bike", specs, profile) { true }
        assertEquals(1, f.failures, "Ambiguous physical ownership must be diagnosed")
        assertEquals(0, f.plannerCalls, "Reject before invoking per-link planners")
        specs.forEach { assertNull(f.lifecycle.binding(it, Any()) { true }) }
        assertNull(f.lifecycle.manualControls())
        assertTrue(f.writer.begins.isEmpty())
        assertNull(f.controller.captureGpsRecording())
    }

    @Test fun distinctSourceIdsCannotAdmitTwoPathsOfOnePhysicalBattery() = runTest {
        val hosted = b.copy(protocolKind = ProtocolKind.VESC,
            ownedPacks = listOf(OwnedSource(1, kind = ProtocolKind.VESC_BMS)))
        rejected(listOf(a, hosted), vehicle("physical-pack", "physical-pack"))
    }

    @Test fun oneDecoderCannotBindTwoSlotsOfTheSameAliasGroup() = runTest {
        rejected(listOf(a.copy(ownedPacks = listOf(OwnedSource(0), OwnedSource(1)))), vehicle("same", "same"))
    }

    @Test fun duplicateGlobalPackOwnershipIsRejectedEvenWithoutAliases() = runTest {
        rejected(listOf(a, b.copy(ownedPacks = listOf(OwnedSource(0)))), vehicle())
    }

    @Test fun duplicateGlobalControllerOwnershipIsRejectedIndependentlyOfPacks() = runTest {
        rejected(listOf(a.copy(ownedControllers = listOf(OwnedSource(7))),
            b.copy(ownedControllers = listOf(OwnedSource(7)))), vehicle())
    }

    @Test fun onePhysicalBleEndpointCannotBeInstalledTwiceWithDifferentCase() = runTest {
        rejected(listOf(a.copy(address = "Aa:BB"), b.copy(address = "aA:bb")), vehicle())
    }

    @Test fun blankAliasIsNotAUsablePhysicalIdentity() = runTest {
        rejected(listOf(a), vehicle(" "))
    }

    @Test fun differentAliasesAndOneSelectedPathStayUsableForQualifiedPlanners() = runTest {
        for ((specs, profile) in listOf(listOf(a, b) to vehicle("first", "second"),
            listOf(a) to vehicle("same", "same"), listOf(a, b) to vehicle(),
            listOf(a.copy(ownedControllers = listOf(OwnedSource(0)))) to vehicle())) {
            val f = Fixture()
            f.lifecycle.install("bike", specs, profile) { true }
            specs.forEach { assertNotNull(f.lifecycle.binding(it, Any()) { true }).prepare() }
            assertEquals(0, f.failures)
            assertEquals(specs.size, f.plannerCalls)
            assertEquals(specs.sumOf { it.ownedPacks.size + it.ownedControllers.size }, f.writer.begins.single().size)
            assertNotNull(f.controller.captureTelemetryRecording())
            assertNotNull(f.controller.captureGpsRecording())
            f.lifecycle.close()
        }
    }

    @Test fun ambiguousReplacementRetiresOldRideAndCapturedManualControls() = runTest {
        val f = Fixture()
        f.lifecycle.install("bike", listOf(a), vehicle()) { true }
        assertNotNull(f.lifecycle.binding(a, Any()) { true }).prepare()
        val old = assertNotNull(f.lifecycle.manualControls())
        assertNotNull(f.controller.captureGpsRecording())
        f.lifecycle.install("bike", listOf(a, b), vehicle("same", "same")) { true }
        assertEquals(listOf<String?>("ride"), f.writer.finished)
        assertNull(f.controller.state.value.intent.ride)
        assertNull(f.controller.captureGpsRecording())
        assertNull(f.lifecycle.manualControls())
        assertFalse(old.start())
        assertFalse(old.stop("ride"))
        assertEquals(1, f.failures)
        assertEquals(1, f.writer.begins.size)
    }
}

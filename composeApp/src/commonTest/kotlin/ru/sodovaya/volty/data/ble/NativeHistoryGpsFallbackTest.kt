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
class NativeHistoryGpsFallbackTest {
    private class Writer : HistoryWriter {
        override val diagnostics = MutableStateFlow(HistoryWriterDiagnostics())
        val begins = mutableListOf<Pair<HistoryRide, List<HistorySource>>>()
        val batches = mutableListOf<HistoryBatch>()
        val ends = mutableListOf<Pair<String?, HistoryRideStatus>>()
        override suspend fun begin(generation: Long, ride: HistoryRide, sources: List<HistorySource>) { begins += ride to sources }
        override fun offer(generation: Long, batch: HistoryBatch): HistoryOffer { batches += batch; return HistoryOffer.ACCEPTED }
        override suspend fun finish(generation: Long, tick: HistoryTick, status: HistoryRideStatus, rideId: String?) { ends += rideId to status }
        override suspend fun flush() = Unit
        override suspend fun delete(rideId: String) = Unit
        override suspend fun close() = Unit
    }
    private fun vehicle(type: ControllerType = ControllerType.KINGSONG) = Vehicle("wheel", "Wheel", "wheel",
        emptyList(), listOf(Controller(0, "Controller", type, "AA")), chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0))
    private fun tick(offset: Long) = HistoryTick(10000 + offset, 100 + offset, 1)

    @Test fun realGuestConnectionOwnsRideTimeGpsAndItsPhysicalSource() = runTest {
        val writer = Writer(); val demand = mutableListOf<Boolean>()
        val controller = RideRecordingController(writer, HistoryRecordingDemand { demand += it }, { "ride" }, { 0 })
        val lifecycle = NativeHistoryRecordingLifecycle(controller, { tick(0) }, ::nativeHistorySourcesForSelectedLink,
            { fail("A real guest device does not need a saved profile") }, selectPhysicalPaths = true)
        val v = vehicle().copy(id = "guest:AA", controllers = emptyList(), packs = listOf(Pack(0, "Battery", BmsType.ANT_BMS, "AA")))
        val spec = LinkSpec("AA", ProtocolKind.ANT, listOf(OwnedSource(0)))
        lifecycle.install(v.id, listOf(spec), v) { true }
        assertNotNull(lifecycle.binding(spec, Any()) { true }).prepare()
        assertEquals("guest:AA", writer.begins.single().first.vehicleId)
        assertEquals(listOf(HistorySourceKind.BATTERY), writer.begins.single().second.map { it.kind })
        assertEquals(listOf(true), demand)
        assertFalse(assertNotNull(lifecycle.manualControls()).telemetryLimited)
        lifecycle.close()
    }

    @Test fun actualGuestRepositoryPlanNeedsNoSavedVehicleToPrepareRecording() = bleRepositoryTest { repo ->
        val writer = Writer(); val demand = mutableListOf<Boolean>()
        val controller = RideRecordingController(writer, HistoryRecordingDemand { demand += it }, { "ride" }, { 0 })
        repo.configureHistoryTelemetryIngress(HistoryTelemetryIngress(controller, { tick(0) }, manageConnections = true))
        val address = "AA:BB:CC:DD:EE:01"
        repo.installLinksForTest(null, address, BmsType.ANT_BMS)
        repo.prepareInstalledHistoryForTest()
        val capture = assertNotNull(repo.historyTelemetryCaptureForTest(ru.sodovaya.volty.data.bms.AntBmsProtocol(),
            address, SampleSessionOwnership { true }))
        assertTrue(capture.prepareForObservation())
        assertEquals(GUEST_VEHICLE_ID_PREFIX + address, writer.begins.single().first.vehicleId)
        assertEquals(listOf(true), demand)
        repo.disconnect()
    }

    @Test fun anEmptyNumericBindingStillOwnsRideTimeGpsAndExplicitControls() = runTest {
        val writer = Writer(); val demand = mutableListOf<Boolean>(); var next = 0; var now = tick(0)
        val controller = RideRecordingController(writer, HistoryRecordingDemand { demand += it }, { "ride-${++next}" }, { 0 })
        val lifecycle = NativeHistoryRecordingLifecycle(controller, { now }, { _, _ -> emptyMap() }, { fail("GPS-only is not a setup failure") })
        val v = vehicle(); val spec = LinkSpec("AA", ProtocolKind.KINGSONG, ownedControllers = listOf(OwnedSource(0)))
        lifecycle.install(v.id, listOf(spec), v) { true }
        val binding = assertNotNull(lifecycle.binding(spec, Any()) { true }, "Numeric coverage cannot remove the connected device")
        binding.prepare()
        val controls = assertNotNull(lifecycle.manualControls())
        assertTrue(controls.telemetryLimited)
        val ride = assertNotNull(controller.state.value.intent.ride)
        assertEquals(listOf(true), demand)
        assertTrue(writer.begins.single().second.isEmpty())
        now = tick(50)
        val gps = HistoryGpsFix(now, 56.8, 60.6, 3.0, null, HistoryGpsProvider.GPS)
        assertEquals(HistoryOffer.ACCEPTED, controller.offerGps(ride.id, gps))
        assertEquals(listOf(gps), writer.batches.flatMap { it.gps })
        assertTrue(writer.batches.flatMap { it.samples }.isEmpty(), "A GPS fallback cannot invent device telemetry")
        now = tick(100)
        assertTrue(controls.stop(ride.id))
        assertTrue(controller.state.value.intent.manuallyStopped)
        assertNull(controller.state.value.intent.ride)
        assertEquals(listOf<Pair<String?, HistoryRideStatus>>(ride.id to HistoryRideStatus.COMPLETED), writer.ends)
        now = tick(150)
        assertTrue(controls.start())
        assertNotEquals(ride.id, assertNotNull(controller.state.value.intent.ride).id)
        lifecycle.close()
    }

    @Test fun everyLiveProtocolCanStartGpsEvenBeforeItsNumericPublisherIsAvailable() = runTest {
        val cases = listOf(ControllerType.FARDRIVER, ControllerType.KELLY, ControllerType.NINEBOT,
            ControllerType.NINEBOT_LEGACY, ControllerType.KINGSONG, ControllerType.INMOTION).map { type ->
            val count = if (type == ControllerType.KINGSONG || type == ControllerType.NINEBOT) 2 else 1
            vehicle(type) to LinkSpec("AA", type.protocolKind(), (0 until count).map { OwnedSource(it) }, listOf(OwnedSource(0)))
        } + (vehicle().copy(controllers = emptyList(), packs = listOf(Pack(0, "Battery", BmsType.VESC_BMS, "AA"))) to
            LinkSpec("AA", ProtocolKind.VESC_BMS, listOf(OwnedSource(0))))
        for ((v, spec) in cases) {
            val writer = Writer(); val demand = mutableListOf<Boolean>()
            val controller = RideRecordingController(writer, HistoryRecordingDemand { demand += it }, { "ride" }, { 0 })
            var failures = 0
            val lifecycle = NativeHistoryRecordingLifecycle(controller, { tick(0) }, ::nativeHistorySourcesForSelectedLink,
                { failures++ }, selectPhysicalPaths = true)
            lifecycle.install(v.id, listOf(spec), v) { true }
            assertNotNull(lifecycle.binding(spec, Any()) { true }, spec.protocolKind.name).prepare()
            assertNotNull(controller.state.value.intent.ride, spec.protocolKind.name)
            assertEquals(listOf(true), demand, spec.protocolKind.name)
            assertNotNull(lifecycle.manualControls(), spec.protocolKind.name)
            assertEquals(0, failures, spec.protocolKind.name)
            lifecycle.close()
        }
    }

    @Test fun oneLimitedLinkCannotDiscardAnotherLinksRegisteredTelemetryOrGps() = runTest {
        val writer = Writer(); val demand = mutableListOf<Boolean>()
        val controller = RideRecordingController(writer, HistoryRecordingDemand { demand += it }, { "ride" }, { 0 })
        val lifecycle = NativeHistoryRecordingLifecycle(controller, { tick(0) }, { spec, v ->
            if (spec.protocolKind == ProtocolKind.KINGSONG) emptyMap() else nativeHistorySourcesForSelectedLink(spec, v)
        },
            { fail("A limited link cannot fail the complete recording plan") }, selectPhysicalPaths = true)
        val v = vehicle().copy(packs = listOf(Pack(0, "Battery", BmsType.ANT_BMS, "BB")))
        val wheel = LinkSpec("AA", ProtocolKind.KINGSONG, ownedControllers = listOf(OwnedSource(0)))
        val pack = LinkSpec("BB", ProtocolKind.ANT, listOf(OwnedSource(0)))
        lifecycle.install(v.id, listOf(wheel, pack), v) { true }
        assertNotNull(lifecycle.binding(wheel, Any()) { true }).prepare()
        assertNotNull(lifecycle.binding(pack, Any()) { true }).prepare()
        assertEquals(HistorySourceKind.BATTERY, writer.begins.single().second.single().kind)
        assertEquals(listOf(true), demand)
        assertTrue(assertNotNull(lifecycle.manualControls()).telemetryLimited)
        lifecycle.close()
    }
}

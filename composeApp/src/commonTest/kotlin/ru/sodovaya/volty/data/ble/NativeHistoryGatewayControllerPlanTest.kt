package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.data.history.historyTelemetrySamples
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.data.bms.vesc.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class NativeHistoryGatewayControllerPlanTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val a = Controller(7, "Front", ControllerType.VESC, address, canId = 41, motor = MotorConfig(15, 254, 1f))
    private val b = a.copy(index = 19, label = "Rear", canId = 42, motor = a.motor.copy(gearRatio = 2f))
    private val pack = Pack(3, "External", BmsType.ANT_BMS, "AA:BB:CC:DD:EE:02")
    private fun vehicle(controllers: List<Controller> = listOf(a, b)) = Vehicle(
        "bike", "Bike", "bicycle", listOf(pack), controllers, chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0))
    private fun spec(controllers: List<Controller> = listOf(a, b)) = LinkSpec(address, ProtocolKind.VESC,
        ownedControllers = controllers.map { OwnedSource(it.index, it.canId) })
    private fun key(local: Int, report: Boolean = false) = HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, local,
        if (report) TelemetryRole.REPORTED_CAN else TelemetryRole.CONTRIBUTOR)

    @Test fun nativeFactoryRepliesUseThePlannedLocalScopesAndEachControllersGeometry() = runTest {
        val link = spec()
        val plan = nativeHistorySourcesForLink(link, vehicle())
        val p = assertIs<VescGatewayProtocol>(controllerMotionProtocol(ProtocolKind.VESC, false, a.motor,
            link, motorFor = { index -> vehicle().controllers.single { it.index == index }.motor }))
        val observed = mutableListOf<HistorySample>()
        p.telemetryObserver = { observation ->
            val source = plan.getValue(HistoryTelemetrySource(observation.origin, observation.localIndex, observation.role))
            observed += historyTelemetrySamples(source, HistoryTick(1000, 100, 1), observation)
        }
        val issued = mutableListOf<Pair<Int, Int>>()
        val loop = launch {
            p.runPollLoop { bytes ->
                val request = VescFrameAccumulator().append(bytes).single()
                assertEquals(VescCan.OPCODE_FORWARD_CAN, request[0].toInt() and 255)
                val target = request[1].toInt() and 255
                val opcode = request[2].toInt() and 255
                issued += target to opcode
                val reply = when (opcode) {
                    VescValues.OPCODE_GET_VALUES_SETUP -> VescTestFrames.setupPayload(currentInRaw = target * 100,
                        trailing = byteArrayOf(target.toByte()))
                    VescValues.OPCODE_GET_VALUES -> VescTestFrames.valuesPayload(currentInRaw = (target - 40) * 1000,
                        vInRaw = 500) + byteArrayOf(0, 0, 0, 0, target.toByte())
                    else -> error("Unexpected poll $opcode")
                }
                p.onNotification(VescPacket.frame(reply))
            }
        }
        try { runCurrent(); advanceTimeBy(1); runCurrent() }
        finally { loop.cancelAndJoin() }
        assertEquals(listOf(41 to 47, 42 to 47, 41 to 4, 42 to 4), issued)
        for ((local, expectedCurrent) in listOf(0 to 10.0, 1 to 20.0)) {
            val physical = plan.getValue(key(local)).id
            val report = plan.getValue(key(local, true)).id
            assertEquals(expectedCurrent, observed.single { it.key.sourceId == physical && it.key.metricId == "battery_current_a" }.value)
            assertEquals(expectedCurrent * 50, observed.single { it.key.sourceId == physical && it.key.metricId == "controller_power_w" }.value)
            assertEquals(41.0 + local, observed.single { it.key.sourceId == report && it.key.metricId == "battery_current_a" }.value)
            assertTrue(observed.filter { it.key.sourceId == report }.all { it.key.metricId in setOf("motor_current_a", "battery_current_a") })
        }
        // SETUP reports speed directly. Only the final VALUES reply derives it from each motor's geometry.
        val frontSpeed = assertNotNull(observed.last { it.key.sourceId == plan.getValue(key(0)).id && it.key.metricId == "speed_kmh" }.value)
        val rearSpeed = assertNotNull(observed.last { it.key.sourceId == plan.getValue(key(1)).id && it.key.metricId == "speed_kmh" }.value)
        assertEquals(frontSpeed / 2, rearSpeed, 0.001)
        assertEquals(0, p.telemetryObserverFailures)
    }

    @Test fun explicitForwardedControllersHaveIndependentPhysicalAndReporterSources() {
        val sources = nativeHistorySourcesForLink(spec(), vehicle())
        assertEquals(setOf(key(0), key(0, true), key(1), key(1, true)), sources.keys)
        assertEquals(4, sources.values.map { it.id }.distinct().size)
        for (local in 0..1) {
            assertEquals(HistorySourceKind.CONTROLLER, sources.getValue(key(local)).kind)
            assertEquals(HistorySourceKind.REPORTED_CAN, sources.getValue(key(local, true)).kind)
        }
        assertEquals("Front", sources.getValue(key(0)).label)
        assertEquals("Rear", sources.getValue(key(1)).label)
        val single = nativeHistorySourcesForLink(spec(listOf(a)), vehicle(listOf(a)))
        assertEquals(setOf(key(0), key(0, true)), single.keys)
        assertEquals(sources.getValue(key(0)).id, single.getValue(key(0)).id)
    }

    @Test fun endpointIdentitySurvivesReorderingRenamingGlobalSlotsAndAddressCase() {
        val first = nativeHistorySourcesForLink(spec(), vehicle())
        val changed = listOf(b.copy(index = 91, label = "B", address = address.lowercase()),
            a.copy(index = 80, label = "A", address = address.lowercase()))
        val second = nativeHistorySourcesForLink(spec(changed).copy(address = address.lowercase()), vehicle(changed))
        for (report in listOf(false, true)) {
            assertEquals(first.getValue(key(0, report)).id, second.getValue(key(1, report)).id)
            assertEquals(first.getValue(key(1, report)).id, second.getValue(key(0, report)).id)
        }
    }

    @Test fun eachPhysicalControllerUsesItsOwnCalibrationWhileReporterDoesNot() {
        val first = nativeHistorySourcesForLink(spec(), vehicle())
        val changed = listOf(a, b.copy(motor = b.motor.copy(wheelDiameterMm = 300)))
        val second = nativeHistorySourcesForLink(spec(changed), vehicle(changed))
        assertNotEquals(first.getValue(key(1)).id, second.getValue(key(1)).id)
        for (key in listOf(key(0), key(0, true), key(1, true))) assertEquals(first.getValue(key).id, second.getValue(key).id)
    }

    @Test fun reporterEndpointChangesWhenBusAddressOrTargetNodeChanges() {
        val first = nativeHistorySourcesForLink(spec(), vehicle())
        val targetChanged = listOf(a.copy(canId = 43), b)
        val second = nativeHistorySourcesForLink(spec(targetChanged), vehicle(targetChanged))
        for (report in listOf(false, true)) {
            assertNotEquals(first.getValue(key(0, report)).id, second.getValue(key(0, report)).id)
            assertEquals(first.getValue(key(1, report)).id, second.getValue(key(1, report)).id)
        }
        val moved = listOf(a, b).map { it.copy(address = "AA:BB:CC:DD:EE:03") }
        val third = nativeHistorySourcesForLink(spec(moved).copy(address = moved.first().address), vehicle(moved))
        for (key in first.keys) assertNotEquals(first.getValue(key).id, third.getValue(key).id)
    }

    @Test fun hostedPacksDerivedSlotsUnqualifiedNativeTargetsAndInconsistentPlansStayRejected() {
        val valid = spec(); val profile = vehicle()
        // Reach the supported shape before testing deliberately inconsistent variants.
        assertEquals(4, nativeHistorySourcesForLink(valid, profile).size)
        val invalid = listOf(
            valid.copy(ownedPacks = listOf(OwnedSource(3, kind = ProtocolKind.VESC_BMS))) to profile,
            valid to profile.copy(packs = emptyList()),
            valid to vehicle(listOf(a.copy(providesDerivedBattery = true), b)),
            valid to vehicle(listOf(a.copy(canId = 40), b)),
            valid.copy(protocolKind = ProtocolKind.BEGODE) to profile,
            valid.copy(ownedControllers = listOf(OwnedSource(7, 41, ProtocolKind.KELLY), OwnedSource(19, 42))) to profile,
            spec(listOf(a, b.copy(canId = 41))) to vehicle(listOf(a, b.copy(canId = 41))),
            spec(listOf(a.copy(canId = 255), b)) to vehicle(listOf(a.copy(canId = 255), b)),
            spec(listOf(a.copy(canId = -1), b)) to vehicle(listOf(a.copy(canId = -1), b)),
            spec(listOf(a.copy(canId = null), b)) to vehicle(listOf(a.copy(canId = null), b)),
            valid to vehicle(listOf(a.copy(address = "OTHER"), b)),
            valid to vehicle(listOf(a)),
        )
        invalid.forEach { (link, vehicle) -> assertFailsWith<IllegalArgumentException> { nativeHistorySourcesForLink(link, vehicle) } }
    }
}

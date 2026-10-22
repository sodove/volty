package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.data.bms.vesc.*
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)
class NativeHistoryMixedGatewayPlanTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val controller = Controller(7, "Motor", ControllerType.VESC, address, canId = 41)
    private val hosted = Pack(19, "Hosted", BmsType.VESC_BMS, address)
    private val forwarded = hosted.copy(index = 3, label = "Forwarded", canId = 42)
    private fun vehicle(packs: List<Pack> = listOf(hosted, forwarded)) = Vehicle(
        "bike", "Bike", "bicycle", packs, listOf(controller), chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0))
    private fun spec(packs: List<Pack> = listOf(hosted, forwarded)) = LinkSpec(address, ProtocolKind.VESC,
        ownedPacks = packs.map { OwnedSource(it.index, it.canId, ProtocolKind.VESC_BMS) },
        ownedControllers = listOf(OwnedSource(controller.index, controller.canId)))
    private fun battery(local: Int) = HistoryTelemetrySource(TelemetryOrigin.BATTERY, local)

    @Test fun computedGatewayBatteryUsesTheActualFactoryVirtualSource() {
        val profile = vehicle().copy(packs = emptyList())
        val link = spec().copy(ownedPacks = listOf(OwnedSource(99)))
        val configuration = nativeDecoderPlan(link, profile)
        val plan = nativeHistorySourcesForLink(link, profile)
        val virtual = HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0, TelemetryRole.DERIVED)
        assertEquals(1, configuration.packCount)
        assertEquals(3, plan.size)
        assertEquals(HistorySourceKind.DERIVED_BATTERY, plan.getValue(virtual).kind)
        assertEquals(configuration.sources.map { HistoryTelemetrySource(it.origin, it.localIndex, it.role, it.assemblyIndex) }.toSet(), plan.keys)
    }

    @Test fun hostedOnlyGatewayNeedsNoFictitiousControllerToRecordItsBms() {
        val profile = vehicle(listOf(hosted)).copy(controllers = emptyList())
        val link = spec(listOf(hosted)).copy(ownedControllers = emptyList())
        val configuration = nativeDecoderPlan(link, profile)
        val plan = nativeHistorySourcesForLink(link, profile)
        assertEquals(0, configuration.controllerCount)
        assertEquals(1, configuration.packCount)
        assertEquals(setOf(battery(0)), plan.keys)
        assertEquals(HistorySourceKind.BATTERY, plan.getValue(battery(0)).kind)
    }

    @Test fun computedGatewayIdentityFollowsContributorsAndCalibration() {
        val other = controller.copy(index = 8, canId = 42)
        val profile = vehicle().copy(packs = emptyList(), controllers = listOf(controller, other))
        val link = spec().copy(ownedPacks = listOf(OwnedSource(99)),
            ownedControllers = listOf(OwnedSource(7, 41), OwnedSource(8, 42)))
        val key = HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0, TelemetryRole.DERIVED)
        val original = nativeHistorySourcesForLink(link, profile).getValue(key).id
        assertEquals(original, nativeHistorySourcesForLink(link.copy(
            ownedControllers = link.ownedControllers.reversed()), profile).getValue(key).id)
        val recalibrated = profile.copy(controllers = listOf(controller.copy(
            motor = controller.motor.copy(polePairs = controller.motor.polePairs + 1)), other))
        assertNotEquals(original, nativeHistorySourcesForLink(link, recalibrated).getValue(key).id)
    }

    @Test fun selectedHostedOnlyAliasUsesTheSameReceivedProducerPolicy() {
        val pack = hosted.copy(aliasGroup = "battery")
        val profile = vehicle(listOf(pack)).copy(controllers = emptyList())
        val link = spec(listOf(pack)).copy(ownedControllers = emptyList())
        val source = nativeHistorySourcesForSelectedLink(link, profile).getValue(battery(0))
        assertEquals(HistorySourceKind.BATTERY, source.kind)
        assertTrue(source.id.startsWith("native-alias-gateway-received-v1:"))
        assertFailsWith<IllegalArgumentException> { nativeHistorySourcesForLink(link, profile) }
    }

    @Test fun mixedPlanKeepsPhysicalControllersAndIndependentCacheSources() {
        val plan = nativeHistorySourcesForLink(spec(), vehicle())
        assertEquals(4, plan.size)
        assertEquals(4, plan.values.map { it.id }.distinct().size)
        assertEquals(HistorySourceKind.BATTERY, plan.getValue(battery(0)).kind)
        assertEquals(HistorySourceKind.BATTERY, plan.getValue(battery(1)).kind)
        val controllerOnly = nativeHistorySourcesForLink(spec().copy(ownedPacks = emptyList()), vehicle())
        controllerOnly.forEach { (key, source) -> assertEquals(source.id, plan.getValue(key).id) }
    }

    @Test fun cacheIdentityFollowsEndpointNotArrayPositionLabelOrGlobalSlot() {
        val first = nativeHistorySourcesForLink(spec(), vehicle())
        val reordered = listOf(forwarded.copy(index = 91, label = "B"), hosted.copy(index = 80, label = "A"))
        val second = nativeHistorySourcesForLink(spec(reordered), vehicle(reordered))
        assertEquals(first.getValue(battery(0)).id, second.getValue(battery(1)).id)
        assertEquals(first.getValue(battery(1)).id, second.getValue(battery(0)).id)
        val moved = listOf(hosted, forwarded.copy(canId = 43))
        val third = nativeHistorySourcesForLink(spec(moved), vehicle(moved))
        assertEquals(first.getValue(battery(0)).id, third.getValue(battery(0)).id)
        assertNotEquals(first.getValue(battery(1)).id, third.getValue(battery(1)).id)
    }

    @Test fun ambiguousAliasedAndMismatchedCachePlansStayRejected() {
        val valid = spec(); val profile = vehicle()
        val bad = listOf(
            valid to profile.copy(packs = listOf(hosted.copy(aliasGroup = "physical"), forwarded)),
            valid to profile.copy(packs = listOf(hosted.copy(bmsType = BmsType.ANT_BMS), forwarded)),
            valid to profile.copy(packs = listOf(hosted.copy(bmsAddress = "OTHER"), forwarded)),
            valid to profile.copy(packs = listOf(hosted, forwarded.copy(canId = 43))),
            valid.copy(ownedPacks = listOf(OwnedSource(99))) to profile,
            valid.copy(ownedPacks = listOf(OwnedSource(19, kind = ProtocolKind.ANT))) to profile,
            spec(listOf(hosted, forwarded.copy(canId = null))) to vehicle(listOf(hosted, forwarded.copy(canId = null))),
            spec(listOf(hosted.copy(canId = 255))) to vehicle(listOf(hosted.copy(canId = 255))),
            spec(listOf(hosted.copy(canId = -1))) to vehicle(listOf(hosted.copy(canId = -1)))
        )
        bad.forEach { (link, owner) -> assertFailsWith<IllegalArgumentException> { nativeHistorySourcesForLink(link, owner) } }
    }

    @Test fun realHostedSlotsDoNotBecomeVirtualWhenDerivedBatteryIsEnabled() {
        val profile = vehicle().copy(controllers = listOf(controller.copy(providesDerivedBattery = true)))
        val first = nativeHistorySourcesForLink(spec(), vehicle())
        assertEquals(first, nativeHistorySourcesForLink(spec(), profile))
        // Without a real owned slot, the same flag WOULD construct a fallback.
        assertFailsWith<IllegalArgumentException> {
            nativeHistorySourcesForLink(spec().copy(ownedPacks = emptyList()), profile)
        }
        val virtual = HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0, TelemetryRole.DERIVED)
        assertEquals(HistorySourceKind.DERIVED_BATTERY, nativeHistorySourcesForLink(
            spec().copy(ownedPacks = listOf(OwnedSource(99))), profile).getValue(virtual).kind)
    }

    // Retraction: a received BMS snapshot is recordable at app receipt time;
    // lack of sensor acquisition timestamps does not erase its reported values.
    @Test fun nativeMixedDecoderRecordsControllerAndReceivedBmsValues() = verifyNativeMixedDecoder(false)

    @Test fun nativeFactoryHonorsRealHostedSlotsEvenWithDerivedChoiceOn() = verifyNativeMixedDecoder(true)

    @Test fun selectedCacheAliasesUseReceivedBmsValuesAndKeepControllerValues() = verifyNativeMixedDecoder(false, aliases = true)

    @Test fun localHeadUnitDoesNotBlockItsHostedBmsOrForwardedController() {
        val local = controller.copy(index = 0, label = "Head unit", canId = null)
        val profile = vehicle().copy(controllers = listOf(local, controller))
        val link = spec().copy(ownedControllers = listOf(OwnedSource(0), OwnedSource(controller.index, controller.canId)))
        val plan = nativeHistorySourcesForLink(link, profile)
        assertEquals(6, plan.size)
        assertEquals(6, plan.values.map { it.id }.distinct().size)
        assertEquals(HistorySourceKind.BATTERY, plan.getValue(battery(0)).kind)
        assertEquals(HistorySourceKind.BATTERY, plan.getValue(battery(1)).kind)
    }

    private fun verifyNativeMixedDecoder(deriveBattery: Boolean, aliases: Boolean = false) = runTest {
        val link = spec()
        val profile = vehicle().let { original -> original.copy(
            packs = original.packs.mapIndexed { index, pack -> if (aliases) pack.copy(aliasGroup = "physical-$index") else pack },
            controllers = listOf(controller.copy(providesDerivedBattery = deriveBattery))) }
        val plan = if (aliases) nativeHistorySourcesForSelectedLink(link, profile) else nativeHistorySourcesForLink(link, profile)
        val protocol = assertIs<VescGatewayProtocol>(controllerMotionProtocol(ProtocolKind.VESC, deriveBattery,
            controller.motor, link))
        val samples = mutableListOf<HistorySample>()
        protocol.telemetryObserver = { event ->
            val source = plan.getValue(HistoryTelemetrySource(event.origin, event.localIndex, event.role))
            samples += historyTelemetrySamples(source, HistoryTick(1000, 100, 1), event)
        }
        val issued = mutableListOf<Pair<Int?, Int>>()
        val loop = launch {
            protocol.runPollLoop { bytes ->
                val request = VescFrameAccumulator().append(bytes).single()
                val forward = (request[0].toInt() and 255) == VescCan.OPCODE_FORWARD_CAN
                val target = if (forward) request[1].toInt() and 255 else null
                val opcode = request[if (forward) 2 else 0].toInt() and 255
                issued += target to opcode
                val reply = when (opcode) {
                    47 -> VescTestFrames.setupPayload(trailing = byteArrayOf(41))
                    4 -> VescTestFrames.valuesPayload(currentInRaw = 1000, vInRaw = 500) + byteArrayOf(0, 0, 0, 0, 41)
                    96 -> cacheReply(target ?: 10)
                    else -> error("Unexpected opcode $opcode")
                }
                protocol.onNotification(VescPacket.frame(reply))
            }
        }
        try { runCurrent(); advanceTimeBy(1); runCurrent() } finally { loop.cancelAndJoin() }
        assertTrue(41 to 4 in issued)
        assertTrue(null to 96 in issued)
        assertTrue(42 to 96 in issued)
        val physical = plan.getValue(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0)).id
        assertEquals(500.0, samples.single { it.key.sourceId == physical && it.key.metricId == "controller_power_w" }.value)
        for (local in 0..1) {
            val cached = samples.filter { it.key.sourceId == plan.getValue(battery(local)).id }
            assertTrue(cached.isNotEmpty())
            assertEquals(75.5, cached.single { it.key.metricId == "voltage_v" }.value)
            assertEquals(-12.5, cached.single { it.key.metricId == "current_a" }.value)
            assertEquals(-943.75, cached.single { it.key.metricId == "power_w" }.value)
            assertTrue(cached.all { it.value != null })
            assertEquals(setOf(0, 1), cached.mapNotNull { it.key.cellIndex }.toSet())
        }
        assertEquals(0, protocol.telemetryObserverFailures)
    }

    private fun cacheReply(id: Int): ByteArray {
        val bytes = mutableListOf<Byte>(96)
        fun i32(value: Int) { for (shift in listOf(24, 16, 8, 0)) bytes += (value shr shift).toByte() }
        fun i16(value: Int) { bytes += (value shr 8).toByte(); bytes += value.toByte() }
        listOf(75_500_000, 0, 12_500_000, 12_500_000, 18_200, 1_374_100).forEach(::i32)
        bytes += 2
        repeat(2) { i16(4000 + it) }
        repeat(2) { bytes += 0 }
        bytes += 1; i16(2350)
        listOf(3120, 2880, 0, 2410, 812, 990).forEach(::i16)
        bytes += id.toByte()
        return bytes.toByteArray()
    }
}

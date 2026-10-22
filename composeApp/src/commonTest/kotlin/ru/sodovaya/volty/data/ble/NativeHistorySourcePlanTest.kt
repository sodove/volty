package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.history.HistoryTelemetrySource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.*
import kotlin.test.*
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class NativeHistorySourcePlanTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private fun vehicle(packs: List<Pack>, controllers: List<Controller> = emptyList()) = Vehicle(
        "bike", "Bike", "bicycle", packs, controllers, chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0))
    private fun pack(index: Int = 3, type: BmsType = BmsType.ANT_BMS) = Pack(index, "A", type, address)
    private fun controller(index: Int = 7) = Controller(index, "ESC", ControllerType.VESC, address,
        motor = MotorConfig(15, 254, 1f))

    @Test fun veteranSeparatesControllerRailFromBothPhysicalSmartBmsBranches() {
        val p = pack(type = BmsType.LEAPERKIM)
        val c = controller().copy(controllerType = ControllerType.VETERAN)
        val spec = LinkSpec(address, ProtocolKind.VETERAN, listOf(OwnedSource(3), OwnedSource(4)), listOf(OwnedSource(7)))
        val sources = nativeHistorySourcesForSelectedLink(spec, vehicle(listOf(p), listOf(c)))
        assertEquals(4, sources.size)
        assertEquals(4, sources.values.map { it.id }.distinct().size)
        assertEquals(HistorySourceKind.CONTROLLER, sources.getValue(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0)).kind)
        assertEquals(HistorySourceKind.DERIVED_BATTERY,
            sources.getValue(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0, TelemetryRole.DERIVED)).kind)
        for (index in 0..1) assertEquals(HistorySourceKind.BATTERY,
            sources.getValue(HistoryTelemetrySource(TelemetryOrigin.BATTERY, index)).kind)
    }

    @Test fun plain_controller_plan_declares_distinct_reporter_scope_without_a_virtual_pack() {
        val c = controller()
        val external = pack().copy(bmsAddress = "AA:BB:CC:DD:EE:02")
        val spec = LinkSpec(address, ProtocolKind.VESC, emptyList(), listOf(OwnedSource(7)))
        val sources = nativeHistorySourcesForLink(spec, vehicle(listOf(external), listOf(c)))
        val physical = sources.getValue(HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0))
        val reported = assertNotNull(sources[HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN)])
        assertEquals(HistorySourceKind.CONTROLLER, physical.kind)
        assertEquals(HistorySourceKind.REPORTED_CAN, reported.kind)
        assertNotEquals(physical.id, reported.id)
        assertEquals(setOf(HistorySourceKind.CONTROLLER, HistorySourceKind.REPORTED_CAN), sources.values.map { it.kind }.toSet())
    }

    @Test fun reported_network_identity_tracks_reporter_address_but_not_wheel_geometry_or_profile_slots() {
        val c = controller()
        val external = pack().copy(bmsAddress = "AA:BB:CC:DD:EE:02")
        val spec = LinkSpec(address, ProtocolKind.VESC, emptyList(), listOf(OwnedSource(7)))
        val first = nativeHistorySourcesForLink(spec, vehicle(listOf(external), listOf(c)))
        val key = HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN)
        val reported = assertNotNull(first[key])
        val renamed = c.copy(index = 80, label = "Renamed", address = address.lowercase(), motor = c.motor.copy(gearRatio = 2f))
        val changed = nativeHistorySourcesForLink(spec.copy(address = address.lowercase(), ownedControllers = listOf(OwnedSource(80))),
            vehicle(listOf(external), listOf(renamed)))
        assertEquals(reported.id, changed.getValue(key).id)
        val physicalKey = HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0)
        assertNotEquals(first.getValue(physicalKey).id, changed.getValue(physicalKey).id)
        val moved = c.copy(address = "AA:BB:CC:DD:EE:03")
        val other = nativeHistorySourcesForLink(spec.copy(address = moved.address), vehicle(listOf(external), listOf(moved)))
        assertNotEquals(reported.id, other.getValue(key).id)
    }

    @Test fun directBatteryIdentityIgnoresProfileLabelsIndicesAndAddressCase() {
        val protocolIds = mutableSetOf<String>()
        for (type in listOf(BmsType.JK_BMS, BmsType.JBD_BMS, BmsType.ANT_BMS, BmsType.DALY_BMS)) {
            val p = pack(type = type); val kind = type.protocolKind()
            val first = nativeHistorySourcesForLink(LinkSpec(address, kind, listOf(OwnedSource(3))), vehicle(listOf(p)))
            val changed = p.copy(index = 90, label = "Renamed", bmsAddress = address.lowercase())
            val second = nativeHistorySourcesForLink(LinkSpec(address.lowercase(), kind, listOf(OwnedSource(90))),
                vehicle(listOf(changed)).copy(name = "Different title"))
            assertEquals(setOf(HistoryTelemetrySource(TelemetryOrigin.BATTERY, 0)), first.keys)
            assertEquals(first.values.single().id, second.values.single().id)
            assertTrue(protocolIds.add(first.values.single().id), "A parser change must change source identity")
            val moved = p.copy(bmsAddress = "AA:BB:CC:DD:EE:02")
            val other = nativeHistorySourcesForLink(LinkSpec(moved.bmsAddress, kind, listOf(OwnedSource(3))), vehicle(listOf(moved)))
            assertNotEquals(first.values.single().id, other.values.single().id)
            assertEquals(HistorySourceKind.BATTERY, first.values.single().kind)
            assertEquals("Renamed", second.values.single().label)
        }
    }

    @Test fun plainControllerAndVirtualRailHaveStableSeparateCalibratedIdentities() {
        val c = controller(); val spec = LinkSpec(address, ProtocolKind.VESC, listOf(OwnedSource(11)), listOf(OwnedSource(7)))
        val first = nativeHistorySourcesForLink(spec, vehicle(emptyList(), listOf(c)))
        assertEquals(setOf(HistorySourceKind.CONTROLLER, HistorySourceKind.DERIVED_BATTERY, HistorySourceKind.REPORTED_CAN), first.values.map { it.kind }.toSet())
        assertEquals(3, first.values.map { it.id }.distinct().size)
        val renamed = c.copy(index = 80, label = "Renamed")
        val newSpec = spec.copy(ownedPacks = listOf(OwnedSource(99)), ownedControllers = listOf(OwnedSource(80)))
        val second = nativeHistorySourcesForLink(newSpec, vehicle(emptyList(), listOf(renamed)))
        assertEquals(first.mapValues { it.value.id }, second.mapValues { it.value.id })
        val recalibrated = nativeHistorySourcesForLink(spec, vehicle(emptyList(), listOf(c.copy(motor = c.motor.copy(gearRatio = 2f)))))
        assertTrue(first.keys.filter { it.role != TelemetryRole.REPORTED_CAN }.all { first.getValue(it).id != recalibrated.getValue(it).id })
        val externalPack = pack().copy(bmsAddress = "AA:BB:CC:DD:EE:02")
        val noVirtual = nativeHistorySourcesForLink(spec.copy(ownedPacks = emptyList()), vehicle(listOf(externalPack), listOf(c)))
        assertEquals(setOf(HistorySourceKind.CONTROLLER, HistorySourceKind.REPORTED_CAN), noVirtual.values.map { it.kind }.toSet())
        val explicitVirtual = nativeHistorySourcesForLink(spec, vehicle(listOf(externalPack), listOf(c.copy(providesDerivedBattery = true))))
        assertEquals(first.mapValues { it.value.id }, explicitVirtual.mapValues { it.value.id })
    }

    @Test fun begodeKeepsNativeBranchesAndAssembliesEvenWhenSecondBranchIsLatent() {
        val p = pack(type = BmsType.BEGODE).copy(cellCount = 40)
        val spec = LinkSpec(address, ProtocolKind.BEGODE, listOf(OwnedSource(3), OwnedSource(4)))
        val sources = nativeHistorySourcesForLink(spec, vehicle(listOf(p)))
        assertEquals(7, sources.size); assertEquals(7, sources.values.map { it.id }.distinct().size)
        for (branch in 0..1) {
            assertEquals(HistorySourceKind.BATTERY, sources.getValue(HistoryTelemetrySource(TelemetryOrigin.BATTERY, branch)).kind)
            for (assembly in 0..1) assertEquals(HistorySourceKind.BATTERY_ASSEMBLY,
                sources.getValue(HistoryTelemetrySource(TelemetryOrigin.BATTERY, branch, TelemetryRole.ASSEMBLY, assembly)).kind)
        }
        val wheelOnly = vehicle(emptyList(), listOf(controller().copy(controllerType = ControllerType.BEGODE)))
        assertEquals(7, nativeHistorySourcesForLink(spec.copy(ownedControllers = listOf(OwnedSource(7))), wheelOnly).size)
        val controllerKey = HistoryTelemetrySource(TelemetryOrigin.CONTROLLER, 0)
        val changed = nativeHistorySourcesForLink(spec, vehicle(listOf(p.copy(cellCount = 32))))
        assertNotEquals(sources.getValue(controllerKey).id, changed.getValue(controllerKey).id)
        assertEquals(sources.filterKeys { it.origin == TelemetryOrigin.BATTERY }.mapValues { it.value.id },
            changed.filterKeys { it.origin == TelemetryOrigin.BATTERY }.mapValues { it.value.id })
    }

    @Test fun unsupportedAmbiguousAndMismatchedPlansAreRejectedBeforeAttribution() {
        val p = pack(); val v = vehicle(listOf(p)); val spec = LinkSpec(address, ProtocolKind.ANT, listOf(OwnedSource(3)))
        val bad = listOf(
            spec to null,
            spec.copy(ownedPacks = listOf(OwnedSource(3, canId = 5))) to v,
            spec to v.copy(packs = listOf(p.copy(aliasGroup = "same-battery"))),
            spec.copy(protocolKind = ProtocolKind.JK) to v,
            spec.copy(ownedPacks = listOf(OwnedSource(99))) to v,
            spec.copy(ownedPacks = listOf(OwnedSource(3), OwnedSource(3))) to v,
            spec.copy(address = "OTHER") to v,
            spec to v.copy(packs = listOf(p.copy(canId = 5))),
            spec to v.copy(packs = listOf(p, p.copy(label = "Duplicate index"))),
            LinkSpec(address, ProtocolKind.VESC, ownedControllers = listOf(OwnedSource(7))) to vehicle(emptyList(), listOf(controller())),
            LinkSpec(address, ProtocolKind.VESC, listOf(OwnedSource(11)), listOf(OwnedSource(7))) to
                vehicle(emptyList(), listOf(controller().copy(canId = 5))),
            LinkSpec(address, ProtocolKind.FARDRIVER, ownedControllers = listOf(OwnedSource(7))) to
                vehicle(emptyList(), listOf(controller().copy(controllerType = ControllerType.FARDRIVER))),
            spec.copy(protocolKind = ProtocolKind.FARDRIVER) to v,
        )
        bad.forEach { (link, profile) -> assertFailsWith<IllegalArgumentException> { nativeHistorySourcesForLink(link, profile) } }
    }
}

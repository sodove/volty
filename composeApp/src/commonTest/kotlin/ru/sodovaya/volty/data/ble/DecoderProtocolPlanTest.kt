package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.AntBmsProtocol
import ru.sodovaya.volty.data.bms.MotionSource
import ru.sodovaya.volty.data.bms.VescGatewayProtocol
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.MotorConfig
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DecoderProtocolPlanTest {
    private val motor = MotorConfig(polePairs = 15, wheelDiameterMm = 254)

    @Test
    fun `every controller protocol kind declares the source layout and counts of its factory`() {
        val controllerKinds = ControllerType.entries.map { it.protocolKind() }.toSet()
        val plannedKinds = ProtocolKind.entries.filter { controllerProtocolPlan(it, false, motor) != null }.toSet()
        assertEquals(controllerKinds, plannedKinds)

        for (kind in controllerKinds) {
            for (deriveBattery in listOf(false, true)) {
                val plan = assertNotNull(controllerProtocolPlan(kind, deriveBattery, motor), kind.name)
                val protocol = plan.create()
                assertEquals(plan.packCount, protocol.packCount, "$kind derive=$deriveBattery packs")
                assertEquals(
                    plan.controllerCount,
                    (protocol as? MotionSource)?.controllerCount ?: 0,
                    "$kind derive=$deriveBattery controllers",
                )
                assertEquals(expectedSources(kind, deriveBattery), plan.sources.toSet(), "$kind derive=$deriveBattery sources")
            }
        }
    }

    @Test
    fun `battery only protocol kinds have no controller plan`() {
        for (kind in listOf(ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT,
            ProtocolKind.DALY, ProtocolKind.VESC_BMS)) {
            assertNull(controllerProtocolPlan(kind, deriveBattery = true, motor = motor), kind.name)
        }
    }

    @Test
    fun `mixed VESC gateway plan preserves controller order hosted pack roles and counts`() {
        val link = LinkSpec(
            address = "AA:BB:CC:DD:EE:01",
            protocolKind = ProtocolKind.VESC,
            ownedPacks = listOf(
                OwnedSource(globalIndex = 8, kind = ProtocolKind.VESC_BMS),
                OwnedSource(globalIndex = 9, canId = 42, kind = ProtocolKind.VESC_BMS),
            ),
            ownedControllers = listOf(
                OwnedSource(globalIndex = 3),
                OwnedSource(globalIndex = 7, canId = 41, kind = ProtocolKind.VESC),
            ),
        )
        val plan = assertNotNull(controllerProtocolPlan(
            kind = ProtocolKind.VESC,
            deriveBattery = true,
            motor = motor,
            link = link,
            motorFor = { if (it == 7) motor.copy(gearRatio = 2f) else motor },
        ))
        assertEquals(2, plan.packCount)
        assertEquals(2, plan.controllerCount)
        assertEquals(setOf(
            DecoderSource(TelemetryOrigin.CONTROLLER, 0),
            DecoderSource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN),
            DecoderSource(TelemetryOrigin.CONTROLLER, 1),
            DecoderSource(TelemetryOrigin.CONTROLLER, 1, TelemetryRole.REPORTED_CAN),
            DecoderSource(TelemetryOrigin.BATTERY, 0),
            DecoderSource(TelemetryOrigin.BATTERY, 1),
        ), plan.sources.toSet())

        val protocol = assertIs<VescGatewayProtocol>(plan.create())
        assertEquals(2, protocol.packCount)
        assertEquals(2, protocol.controllerCount)
    }

    @Test
    fun `gateway derived fallback is declared only for the derived gateway slot`() {
        val link = LinkSpec(
            address = "AA:BB:CC:DD:EE:01",
            protocolKind = ProtocolKind.VESC,
            ownedControllers = listOf(OwnedSource(globalIndex = 3, canId = 41, kind = ProtocolKind.VESC)),
        )
        val derived = assertNotNull(controllerProtocolPlan(ProtocolKind.VESC, true, motor, link))
        assertEquals(1, derived.packCount)
        assertEquals(1, derived.controllerCount)
        assertEquals(setOf(
            DecoderSource(TelemetryOrigin.CONTROLLER, 0),
            DecoderSource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN),
            DecoderSource(TelemetryOrigin.BATTERY, 0, TelemetryRole.DERIVED),
        ), derived.sources.toSet())
        assertIs<VescGatewayProtocol>(derived.create())

        val physical = assertNotNull(controllerProtocolPlan(
            ProtocolKind.VESC,
            true,
            motor,
            link.copy(ownedPacks = listOf(OwnedSource(globalIndex = 8, kind = ProtocolKind.VESC_BMS))),
        ))
        assertEquals(1, physical.packCount)
        assertEquals(setOf(DecoderSource(TelemetryOrigin.BATTERY, 0)),
            physical.sources.filter { it.origin == TelemetryOrigin.BATTERY }.toSet())
    }

    @Test
    fun `source list is copied and source addresses use domain validation`() {
        val mutableSources = mutableListOf(DecoderSource(TelemetryOrigin.CONTROLLER, 0))
        val plan = DecoderProtocolPlan(0, 0, mutableSources) { AntBmsProtocol() }
        mutableSources += DecoderSource(TelemetryOrigin.CONTROLLER, 1)
        assertEquals(listOf(DecoderSource(TelemetryOrigin.CONTROLLER, 0)), plan.sources)

        assertFailsWith<IllegalArgumentException> {
            DecoderSource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.ASSEMBLY, assemblyIndex = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            DecoderSource(TelemetryOrigin.BATTERY, 0, TelemetryRole.REPORTED_CAN)
        }
        assertFailsWith<IllegalArgumentException> {
            DecoderSource(TelemetryOrigin.BATTERY, 0, TelemetryRole.ASSEMBLY)
        }
    }

    @Test
    fun `plan factory reports count drift from its declared topology`() {
        val plan = DecoderProtocolPlan(packCount = 2, controllerCount = 0, sources = emptyList()) {
            AntBmsProtocol()
        }
        assertFailsWith<IllegalStateException> { plan.create() }
    }

    private fun expectedSources(kind: ProtocolKind, deriveBattery: Boolean): Set<DecoderSource> = buildSet {
        fun controller(index: Int = 0) = add(DecoderSource(TelemetryOrigin.CONTROLLER, index))
        fun battery(index: Int, role: TelemetryRole = TelemetryRole.CONTRIBUTOR) =
            add(DecoderSource(TelemetryOrigin.BATTERY, index, role))

        when (kind) {
            ProtocolKind.VESC -> {
                controller()
                add(DecoderSource(TelemetryOrigin.CONTROLLER, 0, TelemetryRole.REPORTED_CAN))
                if (deriveBattery) battery(0, TelemetryRole.DERIVED)
            }
            ProtocolKind.BEGODE -> {
                for (branch in 0..1) {
                    battery(branch)
                    for (assembly in 0..1) {
                        add(DecoderSource(TelemetryOrigin.BATTERY, branch, TelemetryRole.ASSEMBLY, assembly))
                    }
                }
                controller()
            }
            ProtocolKind.KELLY, ProtocolKind.FARDRIVER -> {
                controller()
                if (deriveBattery) battery(0, TelemetryRole.DERIVED)
            }
            ProtocolKind.NINEBOT -> {
                controller()
                for (branch in 0..1) battery(branch)
            }
            ProtocolKind.NINEBOT_LEGACY, ProtocolKind.INMOTION -> {
                controller()
                battery(0)
            }
            ProtocolKind.KINGSONG, ProtocolKind.VETERAN -> {
                controller()
                battery(0, TelemetryRole.DERIVED)
                battery(0)
                battery(1)
            }
            ProtocolKind.JK, ProtocolKind.JBD, ProtocolKind.ANT,
            ProtocolKind.DALY, ProtocolKind.VESC_BMS -> error("No controller plan for $kind")
        }
    }
}

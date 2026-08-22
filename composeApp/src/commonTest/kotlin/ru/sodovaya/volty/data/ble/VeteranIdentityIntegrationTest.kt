package ru.sodovaya.volty.data.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import ru.sodovaya.volty.data.bms.BmsTypeDetector
import ru.sodovaya.volty.data.bms.VeteranProtocol
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.pickedControllerVehicle

@OptIn(ExperimentalTime::class)
class VeteranIdentityIntegrationTest {

    @Test
    fun `Leaperkim battery type and Nosfet controller share Veteran protocol`() {
        assertEquals(ProtocolKind.VETERAN, BmsType.LEAPERKIM.protocolKind())
        assertEquals(ProtocolKind.VETERAN, ControllerType.NOSFET.protocolKind())
        assertIs<VeteranProtocol>(
            controllerMotionProtocol(
                kind = ProtocolKind.VETERAN,
                deriveBattery = false,
                motor = ru.sodovaya.volty.domain.model.MotorConfig()
            )
        )
        assertTrue(controllerMotionSupported(ControllerType.NOSFET))
    }

    @Test
    fun `Nosfet detection requires the strong name and never FFE0 alone`() {
        assertEquals(
            ControllerType.NOSFET,
            BmsTypeDetector.detectController("Nosfet Apex", listOf("0000ffe0-0000-1000-8000-00805f9b34fb"))
        )
        assertNull(
            BmsTypeDetector.detectController(
                name = null,
                serviceUuids = listOf("0000ffe0-0000-1000-8000-00805f9b34fb")
            )
        )
    }

    @Test
    fun `Nosfet builder stores one controller and two Leaperkim packs on one address`() {
        val vehicle = pickedControllerVehicle(
            id = "nosfet-1",
            name = "Nosfet Apex",
            iconKey = "wheel",
            controllerType = ControllerType.NOSFET,
            address = "AA:BB:CC:DD:EE:FF",
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )

        assertEquals(2, vehicle.packs.size)
        assertEquals(listOf(BmsType.LEAPERKIM, BmsType.LEAPERKIM), vehicle.packs.map { it.bmsType })
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:FF"), vehicle.packs.map { it.bmsAddress })
        assertEquals(1, vehicle.controllers.size)
        assertEquals(ControllerType.NOSFET, vehicle.controllers.single().controllerType)
        assertEquals("AA:BB:CC:DD:EE:FF", vehicle.controllers.single().address)
        assertEquals(false, vehicle.controllers.single().providesDerivedBattery)

        val link = planLinks(vehicle.packs, vehicle.controllers).single()
        assertEquals(ProtocolKind.VETERAN, link.protocolKind)
        assertEquals(listOf(0, 1), link.ownedPacks.map { it.globalIndex })
        assertEquals(listOf(0), link.ownedControllers.map { it.globalIndex })
    }
}

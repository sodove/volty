package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.repository.DeviceTypeMemory
import ru.sodovaya.volty.domain.repository.DeviceTypeProvenance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class DeviceTypeResolutionTest {

    private fun controllerVehicle(address: String, type: ControllerType) = Vehicle(
        id = "v-controller",
        name = "Controller vehicle",
        iconKey = "generic",
        packs = emptyList(),
        controllers = listOf(
            Controller(index = 0, label = "Main", controllerType = type, address = address)
        ),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0)
    )

    private fun packVehicle(address: String, type: BmsType) = Vehicle(
        id = "v-pack",
        name = "Pack vehicle",
        iconKey = "generic",
        packs = listOf(Pack(index = 0, label = "Pack", bmsType = type, bmsAddress = address)),
        chemistry = Chemistry.LI_ION_NMC,
        createdAt = Instant.fromEpochSeconds(0)
    )

    @Test
    fun `saved controller type outranks a conflicting detector`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = controllerVehicle("AA:BB", ControllerType.VESC),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = null
        )

        assertEquals(ControllerType.VESC, resolved.controllerType)
        assertNull(resolved.bmsType)
        assertEquals(DeviceTypeProvenance.REMEMBERED, resolved.provenance)
    }

    @Test
    fun `saved pack type outranks a conflicting detector`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = packVehicle("AA:BB", BmsType.ANT_BMS),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = ControllerType.VESC
        )

        assertEquals(BmsType.ANT_BMS, resolved.bmsType)
        assertNull(resolved.controllerType)
        assertEquals(DeviceTypeProvenance.REMEMBERED, resolved.provenance)
    }

    @Test
    fun `an unclaimed address keeps detector values`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = controllerVehicle("OTHER", ControllerType.VESC),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = null
        )

        assertEquals(BmsType.JK_BMS, resolved.bmsType)
        assertNull(resolved.controllerType)
        assertEquals(DeviceTypeProvenance.DETECTED, resolved.provenance)
    }

    @Test
    fun `a controller claim cannot resolve a different address`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = controllerVehicle("OTHER", ControllerType.VESC),
            detectedBmsType = null,
            detectedControllerType = ControllerType.KELLY
        )

        assertEquals(ControllerType.KELLY, resolved.controllerType)
        assertNull(resolved.bmsType)
        assertEquals(DeviceTypeProvenance.DETECTED, resolved.provenance)
    }

    @Test
    fun `an explicit address correction outranks a conflicting detector`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = null,
            rememberedType = DeviceTypeMemory(address = "AA:BB", bmsType = BmsType.ANT_BMS),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = null
        )

        assertEquals(BmsType.ANT_BMS, resolved.bmsType)
        assertNull(resolved.controllerType)
        assertEquals(DeviceTypeProvenance.REMEMBERED, resolved.provenance)
    }

    @Test
    fun `saved vehicle type outranks an explicit correction for the same address`() {
        val resolved = resolveDeviceTypes(
            address = "AA:BB",
            knownVehicle = controllerVehicle("AA:BB", ControllerType.VESC),
            rememberedType = DeviceTypeMemory(address = "AA:BB", bmsType = BmsType.JK_BMS),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = ControllerType.KELLY
        )

        assertEquals(ControllerType.VESC, resolved.controllerType)
        assertNull(resolved.bmsType)
        assertEquals(DeviceTypeProvenance.REMEMBERED, resolved.provenance)
    }

    @Test
    fun `an explicit correction is scoped to its address rather than its name`() {
        val resolved = resolveDeviceTypes(
            address = "DIFFERENT",
            knownVehicle = null,
            rememberedType = DeviceTypeMemory(address = "AA:BB", bmsType = BmsType.ANT_BMS),
            detectedBmsType = BmsType.JK_BMS,
            detectedControllerType = null
        )

        assertEquals(BmsType.JK_BMS, resolved.bmsType)
        assertEquals(DeviceTypeProvenance.DETECTED, resolved.provenance)
    }
}

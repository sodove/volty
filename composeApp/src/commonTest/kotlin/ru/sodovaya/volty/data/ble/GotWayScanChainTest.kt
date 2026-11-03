package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.data.bms.BmsTypeDetector
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.picker.addBmsType
import ru.sodovaya.volty.presentation.picker.addControllerType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A GotWay-named advertisement, scanned and handed to the wizard, is a Begode - never a Veteran / Leaperkim. */
class GotWayScanChainTest {
    private val ffe0 = "0000ffe0-0000-1000-8000-00805f9b34fb"
    private val nus = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    private fun chain(name: String?, services: List<String>): DiscoveredDevice {
        val bms = BmsTypeDetector.detect(name, services)
        val controller = BmsTypeDetector.detectController(name, services)
        val resolved = resolveDeviceTypes("AA:BB", null, null, bms, controller)
        return DiscoveredDevice("AA:BB", name, -50, resolved.bmsType, resolved.controllerType, resolved.provenance)
    }

    @Test fun gotway_names_resolve_to_begode_on_both_halves() {
        for (name in listOf("GotWay_59672", "GotWay_12345", "GOTWAY_1", "gotway_75042", "Gotway")) {
            for (services in listOf(emptyList(), listOf(ffe0), listOf(nus), listOf(ffe0, nus))) {
                val d = chain(name, services)
                assertEquals(BmsType.BEGODE, d.bmsType, "$name $services bms")
                assertNull(d.controllerType, "$name $services controller")
                assertEquals(ControllerType.BEGODE, d.addControllerType(), "$name $services add controller")
                assertEquals(BmsType.BEGODE, d.addBmsType(), "$name $services add bms")
            }
        }
    }

    @Test fun a_gotway_name_beats_a_stale_leaperkim_or_veteran_scan_memory() {
        // Scan memory is free-standing (no saved vehicle) and an earlier session's wrong pick must not outvote a name
        // the detector is certain of.
        val stale = ru.sodovaya.volty.domain.repository.DeviceTypeMemory("AA:BB", controllerType = ControllerType.VETERAN)
        val resolved = resolveDeviceTypes("AA:BB", null, stale, BmsType.BEGODE, null)
        assertEquals(BmsType.BEGODE, resolved.bmsType)
        assertNull(resolved.controllerType)
        val staleBms = ru.sodovaya.volty.domain.repository.DeviceTypeMemory("AA:BB", bmsType = BmsType.LEAPERKIM)
        val resolvedBms = resolveDeviceTypes("AA:BB", null, staleBms, BmsType.BEGODE, null)
        assertEquals(BmsType.BEGODE, resolvedBms.bmsType)
    }
}

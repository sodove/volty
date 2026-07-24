package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehicleControllersTest {
    private fun vec(packs: List<Pack> = emptyList(), controllers: List<Controller> = emptyList()) =
        Vehicle(id = "v", name = "n", iconKey = "generic", packs = packs, controllers = controllers,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now())

    @Test fun controller_only_vehicle_is_allowed() {
        val v = vec(controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "AA")))
        assertTrue(v.hasControllers)
        assertEquals("AA", v.primaryAddress)
    }

    @Test fun no_sources_is_rejected() {
        assertFailsWith<IllegalArgumentException> { vec() }
    }

    @Test fun primaryAddress_prefers_controller_then_pack() {
        val withBoth = vec(
            packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK")),
            controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "CTRL"))
        )
        assertEquals("CTRL", withBoth.primaryAddress)
        val packOnly = vec(packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK")))
        assertEquals("PACK", packOnly.primaryAddress)
    }

    @Test fun vesc_bms_reports_soc() {
        assertTrue(BmsType.VESC_BMS.reportsStateOfCharge)
    }

    @Test fun pack_new_fields_default_null() {
        val p = Pack(0, "P", BmsType.ANT_BMS, "AA")
        assertEquals(null, p.canId)
        assertEquals(null, p.aliasGroup)
    }
}

package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Covers the safe (`*OrNull`) counterparts to the throwing primary-pack
 * accessors in `Vehicle.kt`, which explode on a controller-only vehicle.
 * See `VehicleSources.kt`.
 */
@OptIn(ExperimentalTime::class)
class VehicleSourcesTest {
    private fun vec(packs: List<Pack> = emptyList(), controllers: List<Controller> = emptyList()) =
        Vehicle(id = "v", name = "n", iconKey = "generic", packs = packs, controllers = controllers,
            chemistry = Chemistry.LI_ION_NMC, createdAt = Clock.System.now())

    private val controllerOnly = vec(controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "AA")))
    private val packOnly = vec(packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK", cellCount = 12)))
    private val mixed = vec(
        packs = listOf(Pack(0, "P", BmsType.ANT_BMS, "PACK", cellCount = 12)),
        controllers = listOf(Controller(0, "ESC", ControllerType.VESC, "CTRL"))
    )
    /**
     * Deliberately NOT a single shared-address pair: with only one address on
     * each side, `setOf("SAME")` is satisfied by any type-correct
     * implementation of `allAddresses` (a `Set<String>` can't hold a
     * duplicate regardless of whether it actually reads both `packs` and
     * `controllers`). Giving each side an extra, non-shared address makes the
     * expected set's size discriminate: dropping either source changes the
     * result to 2 elements instead of 3, and dropping the address-level
     * dedup itself is unreachable to observe via a `Set` return type — this
     * fixture instead proves both sources are actually being read.
     */
    private val sharedAddress = vec(
        packs = listOf(
            Pack(0, "P", BmsType.ANT_BMS, "SAME"),
            Pack(1, "P2", BmsType.ANT_BMS, "P2")
        ),
        controllers = listOf(
            Controller(0, "ESC", ControllerType.VESC, "SAME"),
            Controller(1, "ESC2", ControllerType.VESC, "C2")
        )
    )

    // -- primaryPackOrNull / bmsTypeOrNull / bmsAddressOrNull / cellCountOrNull --

    @Test fun controller_only_vehicle_has_no_pack_derived_values() {
        assertNull(controllerOnly.primaryPackOrNull)
        assertNull(controllerOnly.bmsTypeOrNull)
        assertNull(controllerOnly.bmsAddressOrNull)
        assertNull(controllerOnly.cellCountOrNull)
    }

    @Test fun pack_only_vehicle_reports_its_pack_values() {
        assertEquals(packOnly.packs.first(), packOnly.primaryPackOrNull)
        assertEquals(BmsType.ANT_BMS, packOnly.bmsTypeOrNull)
        assertEquals("PACK", packOnly.bmsAddressOrNull)
        assertEquals(12, packOnly.cellCountOrNull)
    }

    @Test fun mixed_vehicle_reports_the_pack_values_not_the_controller() {
        assertEquals(mixed.packs.first(), mixed.primaryPackOrNull)
        assertEquals(BmsType.ANT_BMS, mixed.bmsTypeOrNull)
        assertEquals("PACK", mixed.bmsAddressOrNull)
        assertEquals(12, mixed.cellCountOrNull)
    }

    // -- allAddresses --

    @Test fun allAddresses_is_only_the_controller_address_when_controller_only() {
        assertEquals(setOf("AA"), controllerOnly.allAddresses)
    }

    @Test fun allAddresses_is_only_the_pack_address_when_pack_only() {
        assertEquals(setOf("PACK"), packOnly.allAddresses)
    }

    @Test fun allAddresses_contains_both_pack_and_controller_address_when_mixed() {
        assertEquals(setOf("PACK", "CTRL"), mixed.allAddresses)
    }

    @Test fun allAddresses_deduplicates_when_pack_and_controller_share_one_link_address() {
        assertEquals(setOf("SAME", "P2", "C2"), sharedAddress.allAddresses)
    }

    @Test fun allAddresses_is_never_empty_for_a_valid_vehicle() {
        assertTrue(controllerOnly.allAddresses.isNotEmpty())
        assertTrue(packOnly.allAddresses.isNotEmpty())
        assertTrue(mixed.allAddresses.isNotEmpty())
    }

    // -- needsDerivedBattery --

    @Test fun needsDerivedBattery_true_only_for_controller_only() {
        assertTrue(controllerOnly.needsDerivedBattery)
        assertFalse(packOnly.needsDerivedBattery)
        assertFalse(mixed.needsDerivedBattery)
    }
}

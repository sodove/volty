package ru.sodovaya.volty.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class VehiclePacksTest {

    private fun single() = singlePackVehicle(
        id = "v1",
        name = "Scooter",
        iconKey = "scooter",
        bmsType = BmsType.JK_BMS,
        bmsAddress = "AA:BB:CC:DD:EE:FF",
        chemistry = Chemistry.LI_ION_NMC,
        cellCount = 16,
        createdAt = Clock.System.now()
    )

    @Test
    fun singlePackFactoryProducesExactlyOnePack() {
        val v = single()
        assertEquals(1, v.packs.size)
        assertEquals(0, v.packs[0].index)
        assertEquals(PackTopology.PARALLEL, v.topology)
        assertFalse(v.isMultiPack)
    }

    @Test
    fun legacyAccessorsReadThroughToTheFirstPack() {
        val v = single()
        // The throwing `packs.first()` shims were deleted in G1 task 2; their
        // safe counterparts must still read through to the primary pack for an
        // ordinary one-pack vehicle — this is the "BMS path is unchanged" pin.
        assertEquals(BmsType.JK_BMS, v.bmsTypeOrNull)
        assertEquals("AA:BB:CC:DD:EE:FF", v.bmsAddressOrNull)
        assertEquals("AA:BB:CC:DD:EE:FF", v.primaryAddress)
        assertEquals(16, v.cellCountOrNull)
    }

    @Test
    fun withCellCountUpdatesEveryBranchOfTheSameWheelButNotAnotherPack() {
        val two = single().let { v ->
            v.copy(
                packs = listOf(
                    Pack(0, "Wheel branch 1", BmsType.BEGODE, "WH:01"),
                    Pack(1, "Wheel branch 2", BmsType.BEGODE, "WH:01"),
                    Pack(2, "Independent wheel", BmsType.BEGODE, "WH:02", cellCount = 24)
                )
            )
        }
        val updated = two.withCellCount(20)
        assertEquals(20, updated.packs[0].cellCount)
        assertEquals(20, updated.packs[1].cellCount, "the other branch is the same physical wheel")
        assertEquals(24, updated.packs[2].cellCount, "a genuinely different pack keeps its own count")
        assertTrue(updated.isMultiPack)
    }

    @Test
    fun vehicleWithoutPacksIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Vehicle(
                id = "v1",
                name = "Broken",
                iconKey = "generic",
                packs = emptyList(),
                topology = PackTopology.PARALLEL,
                chemistry = Chemistry.LI_ION_NMC,
                createdAt = Clock.System.now()
            )
        }
    }

    @Test
    fun expandedToSynthesisesUniqueIndicesForNonContiguousPacks() {
        // A stored list can, in principle, start above index 0. Deriving the
        // next index from the list SIZE would synthesise a duplicate index 1
        // here — and VehicleConnection.submit matches by index, so one slot
        // would be permanently unreachable.
        val stored = listOf(Pack(index = 1, label = "Branch B", bmsType = BmsType.BEGODE, bmsAddress = "AA:01"))
        val expanded = stored.expandedTo(2)
        assertEquals(2, expanded.size)
        assertEquals(listOf(1, 2), expanded.map { it.index })
        assertEquals("Pack 3", expanded[1].label, "the label follows the synthesised index")
    }

    @Test
    fun expandedToCarriesARiderEnteredCellCountToTheSecondWheelBranch() {
        val stored = listOf(
            Pack(index = 0, label = "Battery", bmsType = BmsType.BEGODE, bmsAddress = "AA:01", cellCount = 40)
        )
        val expanded = stored.expandedTo(2)
        assertEquals(listOf(0, 1), expanded.map { it.index })
        assertEquals("Pack 2", expanded[1].label)
        assertEquals(40, expanded[1].cellCount, "both branches are the same 40S wheel")
    }

    @Test
    fun guestSentinelStillWorks() {
        val guest = singlePackVehicle(
            id = "${GUEST_VEHICLE_ID_PREFIX}AA:BB",
            name = "Guest BMS",
            iconKey = "battery",
            bmsType = BmsType.JK_BMS,
            bmsAddress = "AA:BB",
            chemistry = Chemistry.LI_ION_NMC,
            createdAt = Clock.System.now()
        )
        assertTrue(guest.isGuest)
    }
}

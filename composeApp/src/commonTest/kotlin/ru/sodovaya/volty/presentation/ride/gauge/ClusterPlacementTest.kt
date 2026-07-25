package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterPlacementTest {

    private val w = 1000
    private val h = 1400

    @Test fun every_slot_has_a_placement() {
        assertEquals(ClusterSlot.entries.size, ClusterPlacement.slots.size)
    }

    @Test fun the_hero_is_the_largest_dial() {
        val hero = ClusterPlacement.slots.getValue(ClusterSlot.HERO)
        val others = ClusterPlacement.slots.filterKeys { it != ClusterSlot.HERO }.values
        assertTrue(others.all { it.sizeFraction < hero.sizeFraction })
    }

    @Test fun the_centre_dials_sit_above_their_neighbours() {
        val slots = ClusterPlacement.slots
        assertTrue(slots.getValue(ClusterSlot.TOP_CENTRE).zIndex > slots.getValue(ClusterSlot.TOP_LEFT).zIndex)
        assertTrue(slots.getValue(ClusterSlot.TOP_CENTRE).zIndex > slots.getValue(ClusterSlot.TOP_RIGHT).zIndex)
        assertTrue(slots.getValue(ClusterSlot.BOTTOM_CENTRE).zIndex > slots.getValue(ClusterSlot.BOTTOM_LEFT).zIndex)
    }

    @Test fun the_battery_inset_overlaps_the_hero_and_sits_on_top_of_it() {
        val hero = ClusterPlacement.place(ClusterSlot.HERO, w, h)
        val inset = ClusterPlacement.place(ClusterSlot.HERO_INSET, w, h)
        assertTrue(inset.left < hero.right && inset.top < hero.bottom, "inset must overlap the hero")
        assertTrue(
            ClusterPlacement.slots.getValue(ClusterSlot.HERO_INSET).zIndex >
                ClusterPlacement.slots.getValue(ClusterSlot.HERO).zIndex
        )
    }

    @Test fun every_dial_stays_inside_the_cluster_bounds() {
        for (slot in ClusterSlot.entries) {
            val r = ClusterPlacement.place(slot, w, h)
            assertTrue(r.left >= 0 && r.top >= 0, "$slot starts outside: $r")
            assertTrue(r.right <= w, "$slot overflows width: $r")
            assertTrue(r.bottom <= h, "$slot overflows height: $r")
        }
    }

    @Test fun placement_scales_with_width() {
        val small = ClusterPlacement.place(ClusterSlot.HERO, 500, 700)
        val large = ClusterPlacement.place(ClusterSlot.HERO, 1000, 1400)
        assertEquals(small.width * 2, large.width)
    }

    @Test fun the_top_row_sits_above_the_hero_and_the_bottom_row_below_it() {
        val hero = ClusterPlacement.place(ClusterSlot.HERO, w, h)
        assertTrue(ClusterPlacement.place(ClusterSlot.TOP_CENTRE, w, h).top < hero.top)
        assertTrue(ClusterPlacement.place(ClusterSlot.BOTTOM_CENTRE, w, h).bottom > hero.bottom)
    }
}

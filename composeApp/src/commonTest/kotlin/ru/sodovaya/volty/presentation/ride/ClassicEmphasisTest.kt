package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.ride.gauge.ClusterSlot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec §7.2's Classic half: the "Inner gauge" picker emphasises a dial instead of driving an
 * inner ring. This pins the mapping itself — the whole contract [ClassicRideCluster] was
 * silently dropping (it never read `state.secondary` at all).
 */
class ClassicEmphasisTest {

    @Test fun each_secondary_gauge_maps_to_the_slot_showing_the_same_metric() {
        assertEquals(ClusterSlot.TOP_RIGHT, ClassicEmphasis.slotFor(SecondaryGauge.DUTY))
        assertEquals(ClusterSlot.HERO_INSET, ClassicEmphasis.slotFor(SecondaryGauge.BATTERY))
        assertEquals(ClusterSlot.TOP_CENTRE, ClassicEmphasis.slotFor(SecondaryGauge.POWER))
        assertEquals(ClusterSlot.TOP_LEFT, ClassicEmphasis.slotFor(SecondaryGauge.CURRENT))
        assertEquals(ClusterSlot.BOTTOM_RIGHT, ClassicEmphasis.slotFor(SecondaryGauge.MOTOR_TEMP))
        assertEquals(ClusterSlot.BOTTOM_LEFT, ClassicEmphasis.slotFor(SecondaryGauge.ESC_TEMP))
        assertEquals(ClusterSlot.BOTTOM_CENTRE, ClassicEmphasis.slotFor(SecondaryGauge.CONSUMPTION))
    }

    @Test fun every_secondary_gauge_maps_to_a_distinct_slot() {
        val slots = SecondaryGauge.entries.map { ClassicEmphasis.slotFor(it) }
        assertEquals(slots.size, slots.distinct().size)
    }

    // HERO always shows speed regardless of the secondary gauge choice — it is never a valid
    // emphasis target, so it must be absent from the mapping's range.
    @Test fun the_hero_slot_is_never_an_emphasis_target() {
        val slots = SecondaryGauge.entries.map { ClassicEmphasis.slotFor(it) }
        assertEquals(false, slots.contains(ClusterSlot.HERO))
    }
}

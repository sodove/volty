package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.ride.gauge.VescClusterSlot
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Spec §7.2's Classic half: the "Inner gauge" picker emphasises a dial instead of driving an
 * inner ring. This pins the mapping itself — the contract [ClassicRideCluster] once dropped
 * entirely (it never read `state.secondary` at all).
 */
class ClassicEmphasisTest {

    @Test fun each_secondary_gauge_maps_to_the_slot_showing_the_same_metric() {
        assertEquals(VescClusterSlot.DUTY, ClassicEmphasis.slotFor(SecondaryGauge.DUTY))
        assertEquals(VescClusterSlot.BATTERY, ClassicEmphasis.slotFor(SecondaryGauge.BATTERY))
        assertEquals(VescClusterSlot.POWER, ClassicEmphasis.slotFor(SecondaryGauge.POWER))
        assertEquals(VescClusterSlot.CURRENT, ClassicEmphasis.slotFor(SecondaryGauge.CURRENT))
        assertEquals(VescClusterSlot.MOTOR_TEMP, ClassicEmphasis.slotFor(SecondaryGauge.MOTOR_TEMP))
        assertEquals(VescClusterSlot.ESC_TEMP, ClassicEmphasis.slotFor(SecondaryGauge.ESC_TEMP))
        assertEquals(VescClusterSlot.CONSUMPTION, ClassicEmphasis.slotFor(SecondaryGauge.CONSUMPTION))
    }

    @Test fun every_secondary_gauge_maps_to_a_distinct_slot() {
        val slots = SecondaryGauge.entries.map { ClassicEmphasis.slotFor(it) }
        assertEquals(slots.size, slots.distinct().size)
    }

    // SPEED always shows speed regardless of the secondary gauge choice — it is never a valid
    // emphasis target, so it must be absent from the mapping's range.
    @Test fun the_hero_speed_slot_is_never_an_emphasis_target() {
        val slots = SecondaryGauge.entries.map { ClassicEmphasis.slotFor(it) }
        assertEquals(false, slots.contains(VescClusterSlot.SPEED))
    }
}

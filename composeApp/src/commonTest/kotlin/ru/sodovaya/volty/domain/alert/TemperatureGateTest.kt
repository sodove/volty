package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TemperatureGateTest {

    private fun TemperatureGate.feed(vararg values: Float): List<Float?> = values.map { accept(it) }

    @Test fun a_fresh_gate_needs_three_agreeing_samples() {
        assertEquals(listOf(null, null, 41f), TemperatureGate().feed(40f, 40f, 41f))
    }

    @Test fun a_lone_corrupt_frame_is_dropped_and_leaves_the_run_intact() {
        assertEquals(
            listOf(null, null, 40f, null, 40f),
            TemperatureGate().feed(40f, 40f, 40f, 130f, 40f),
            "the corrupt frame is neither confirmed nor does it reset the accepted run"
        )
    }

    @Test fun a_jump_of_exactly_twenty_is_not_a_jump() {
        assertEquals(60f, TemperatureGate().apply { feed(40f, 40f, 40f) }.accept(60f))
    }

    @Test fun a_genuine_fast_jump_is_accepted_on_the_third_agreeing_sample() {
        assertEquals(
            listOf(null, null, 40f, null, null, 75f),
            TemperatureGate().feed(40f, 40f, 40f, 75f, 75f, 75f)
        )
    }

    @Test fun jumps_that_disagree_with_each_other_never_accumulate() {
        assertEquals(
            listOf(null, null, 40f, null, null, null, 40f),
            TemperatureGate().feed(40f, 40f, 40f, 130f, 80f, 130f, 40f)
        )
    }

    @Test fun a_good_sample_between_jumps_restarts_the_jump_count() {
        assertEquals(
            listOf(null, null, 40f, null, 40f, null, null),
            TemperatureGate().feed(40f, 40f, 40f, 75f, 40f, 75f, 75f)
        )
    }

    @Test fun nan_and_infinity_are_rejected_without_touching_state() {
        val gate = TemperatureGate()
        assertNull(gate.accept(Float.NaN))
        assertNull(gate.accept(Float.POSITIVE_INFINITY))
        assertEquals(listOf(null, null, 40f), gate.feed(40f, 40f, 40f))
    }

    @Test fun reset_forgets_the_baseline() {
        val gate = TemperatureGate().apply { feed(40f, 40f, 40f) }
        gate.reset()
        assertEquals(listOf(null, null, 90f), gate.feed(90f, 90f, 90f))
    }
}

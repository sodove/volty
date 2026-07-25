package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialGeometryTest {

    private val zeroToHundred = DialScale(min = 0f, max = 100f, majorTicks = 5)
    private val bipolar = DialScale(min = -60f, max = 60f, majorTicks = 6)

    @Test fun the_scale_minimum_sits_at_the_dial_opening() {
        assertEquals(DialGeometry.START_ANGLE, DialGeometry.angleFor(0f, zeroToHundred))
    }

    @Test fun the_scale_maximum_sits_at_the_end_of_the_sweep() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, DialGeometry.angleFor(100f, zeroToHundred))
    }

    @Test fun the_midpoint_sits_halfway_round() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP / 2f, DialGeometry.angleFor(50f, zeroToHundred))
    }

    @Test fun a_bipolar_scale_puts_zero_in_the_middle() {
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP / 2f, DialGeometry.angleFor(0f, bipolar))
    }

    @Test fun values_beyond_the_scale_are_clamped_to_the_dial() {
        assertEquals(DialGeometry.START_ANGLE, DialGeometry.angleFor(-999f, zeroToHundred))
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, DialGeometry.angleFor(999f, zeroToHundred))
        assertEquals(0f, DialGeometry.fraction(-999f, zeroToHundred))
        assertEquals(1f, DialGeometry.fraction(999f, zeroToHundred))
    }

    @Test fun a_degenerate_scale_does_not_divide_by_zero() {
        val flat = DialScale(min = 5f, max = 5f, majorTicks = 2)
        assertEquals(0f, DialGeometry.fraction(5f, flat))
        assertTrue(DialGeometry.angleFor(5f, flat).isFinite())
    }

    @Test fun major_values_span_the_scale_inclusive() {
        val majors = DialGeometry.majorValues(zeroToHundred)
        assertEquals(6, majors.size)               // majorTicks = 5 ⇒ 6 labelled values
        assertEquals(0f, majors.first())
        assertEquals(100f, majors.last())
        assertTrue(abs(majors[1] - 20f) < 0.001f)
    }

    @Test fun tick_angles_include_minors_and_flag_the_majors() {
        val ticks = DialGeometry.tickAngles(zeroToHundred)
        assertEquals(5 * 4 + 1, ticks.size)        // majors × minorPerMajor + 1
        assertTrue(ticks.first().isMajor)
        assertTrue(ticks.last().isMajor)
        assertEquals(6, ticks.count { it.isMajor })
        assertEquals(DialGeometry.START_ANGLE, ticks.first().degrees)
        assertEquals(DialGeometry.START_ANGLE + DialGeometry.SWEEP, ticks.last().degrees)
    }

    @Test fun tick_angles_increase_monotonically() {
        val degrees = DialGeometry.tickAngles(bipolar).map { it.degrees }
        assertEquals(degrees.sorted(), degrees)
    }

    @Test fun the_danger_band_runs_from_its_threshold_to_the_scale_end() {
        val (start, sweep) = DialGeometry.dangerSweep(from = 90f, scale = zeroToHundred)!!
        assertEquals(DialGeometry.angleFor(90f, zeroToHundred), start)
        assertTrue(abs(sweep - DialGeometry.SWEEP * 0.10f) < 0.01f)
    }

    @Test fun a_danger_threshold_above_the_scale_yields_no_band() {
        assertNull(DialGeometry.dangerSweep(from = 150f, scale = zeroToHundred))
    }

    @Test fun a_danger_threshold_at_the_scale_minimum_bands_the_whole_dial() {
        val (start, sweep) = DialGeometry.dangerSweep(from = zeroToHundred.min, scale = zeroToHundred)!!
        assertEquals(DialGeometry.START_ANGLE, start)
        assertEquals(DialGeometry.SWEEP, sweep)
    }

    @Test fun a_danger_threshold_at_the_scale_maximum_yields_no_band() {
        assertNull(DialGeometry.dangerSweep(from = zeroToHundred.max, scale = zeroToHundred))
    }
}

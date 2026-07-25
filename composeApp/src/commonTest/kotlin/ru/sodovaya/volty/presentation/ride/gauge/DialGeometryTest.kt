package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.abs
import kotlin.math.roundToInt
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

    // --- pickMajorTicks: the hero dial's max is a runtime value, unlike every other Classic
    // dial's fixed-constant scale, so it is the only one whose majorTicks can stop dividing the
    // span evenly (80 / 7 = 11.43, not a whole number) — which used to render non-round tick
    // labels (0, 11, 23, 34, 46, 57, 69, 80) once DialGauge rounded each independently.

    @Test fun picks_7_when_the_max_is_a_multiple_of_70() {
        assertEquals(7, DialGeometry.pickMajorTicks(70f))
        assertEquals(7, DialGeometry.pickMajorTicks(140f))
    }

    @Test fun falls_back_to_5_when_7_does_not_divide_evenly() {
        assertEquals(5, DialGeometry.pickMajorTicks(80f))
        assertEquals(5, DialGeometry.pickMajorTicks(100f))
        assertEquals(5, DialGeometry.pickMajorTicks(120f))
    }

    @Test fun every_candidate_tick_count_yields_whole_number_major_values() {
        for (max in listOf(70f, 80f, 90f, 100f, 110f, 120f, 130f)) {
            val ticks = DialGeometry.pickMajorTicks(max)
            val interval = max / ticks
            assertTrue(abs(interval - interval.roundToInt()) < 0.01f, "max=$max ticks=$ticks interval=$interval")
        }
    }

    @Test fun a_degenerate_span_falls_back_to_the_first_candidate_without_crashing() {
        assertEquals(7, DialGeometry.pickMajorTicks(0f))
        assertEquals(7, DialGeometry.pickMajorTicks(-10f))
    }

    // --- numberRadius: lifted from DialGauge's draw lambda so this arithmetic — wrong by six
    // points on its first pass, per the task report — is pinned by a test rather than
    // re-verified by hand each time.

    @Test fun number_radius_sits_inside_the_tick_marks_minus_gap_and_label_half_diagonal() {
        val r = DialGeometry.numberRadius(tickOuterR = 90f, majorTickLen = 13f, gap = 4f, numberHalfDiagonal = 10f)
        assertEquals(90f - 13f - 4f - 10f, r)
    }

    @Test fun number_radius_never_goes_negative() {
        val r = DialGeometry.numberRadius(tickOuterR = 10f, majorTickLen = 13f, gap = 4f, numberHalfDiagonal = 10f)
        assertEquals(0f, r)
    }

    // --- centreScale: the part's actual acceptance criterion (1.0 == no shrink) at the small-dial
    // proportions this cluster ships, plus the shrink-never-grow safety net for an oversized
    // centre block.

    @Test fun centre_scale_is_1_when_the_centre_block_comfortably_fits_the_small_dial_proportions() {
        // Proportions lifted from DialGauge's own fractions of `radius` at a representative small
        // corner-dial radius (radius = 50px, the smallest slot in ClusterPlacement's cluster):
        // tickOuterR = 45, majorTickLen = 6.5, numberGap = 4dp ~ 4px, so numberR ~= 45 - 6.5 - 4 -
        // halfDiagonal. With a realistic small label half-diagonal (~6px) and a centre block half
        // diagonal comfortably under the safe radius, no shrink should be needed.
        val numberR = DialGeometry.numberRadius(tickOuterR = 45f, majorTickLen = 6.5f, gap = 4f, numberHalfDiagonal = 6f)
        val scale = DialGeometry.centreScale(
            numberR = numberR,
            numberHalfDiagonal = 6f,
            margin = 4f,
            centreHalfDiagonal = 14f
        )
        assertEquals(1f, scale)
    }

    @Test fun centre_scale_shrinks_but_never_below_half_for_a_pathologically_large_centre_block() {
        val scale = DialGeometry.centreScale(
            numberR = 20f,
            numberHalfDiagonal = 6f,
            margin = 4f,
            centreHalfDiagonal = 200f
        )
        assertTrue(scale < 1f)
        assertTrue(scale >= 0.5f)
    }

    @Test fun centre_scale_is_1_when_the_centre_block_has_no_measured_size() {
        assertEquals(1f, DialGeometry.centreScale(numberR = 10f, numberHalfDiagonal = 2f, margin = 4f, centreHalfDiagonal = 0f))
    }

    // --- decimalsFor: 0 decimals once a scale's own max reaches double digits, 1 below that —
    // the same rule DialGauge applied inline, now pure and tested.

    @Test fun decimals_for_a_double_digit_or_larger_max_is_zero() {
        assertEquals(0, DialGeometry.decimalsFor(10f))
        assertEquals(0, DialGeometry.decimalsFor(100f))
        assertEquals(0, DialGeometry.decimalsFor(-10f))
    }

    @Test fun decimals_for_a_single_digit_max_is_one() {
        assertEquals(1, DialGeometry.decimalsFor(8f))
        assertEquals(1, DialGeometry.decimalsFor(-2f))
    }
}

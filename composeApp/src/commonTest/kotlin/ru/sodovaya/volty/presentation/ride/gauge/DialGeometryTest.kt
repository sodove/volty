package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DialGeometryTest {

    // This module has no Compose UI-test dependency (deliberately not added — see the task
    // report), so a real TextLayoutResult isn't available here. These two constants stand in for
    // what a genuine text measurement would give the
    // `centre_scale_on_the_real_localized_small_corner_dial_...` test below: they are ESTIMATES,
    // not measured values, and are not tuned to hit any particular target number.

    /** Rough average glyph width for a sans-serif uppercase Latin/Cyrillic string, as a fraction
     *  of font size. */
    private val EstimatedAvgGlyphWidthEm = 0.6f

    /** Rough single line's height (ascender to descender), as a multiple of font size. */
    private val EstimatedLineHeightEm = 1.2f

    private fun estimatedTextWidth(charCount: Int, fontSize: Float, letterSpacing: Float = 0f): Float =
        charCount * fontSize * EstimatedAvgGlyphWidthEm + charCount * letterSpacing

    private fun estimatedTextHeight(fontSize: Float): Float = fontSize * EstimatedLineHeightEm

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

    // --- snapScaleMaxUp: a scale max converted into an arbitrary display unit (km/h -> mph)
    // doesn't inherit the round-number snap its km/h source already has (70 km/h -> 43.5 mph,
    // no clean divisor at all), so it needs its own snap to a round number before
    // pickMajorTicks stands a chance. Never rounds DOWN — the scale must still cover the real
    // value it was given.

    @Test fun snaps_up_to_the_next_multiple_of_the_step() {
        assertEquals(45f, DialGeometry.snapScaleMaxUp(43.5f, step = 5f))
        assertEquals(50f, DialGeometry.snapScaleMaxUp(49.71f, step = 5f))
    }

    @Test fun a_value_already_on_the_step_is_unchanged() {
        assertEquals(70f, DialGeometry.snapScaleMaxUp(70f, step = 5f))
        assertEquals(45f, DialGeometry.snapScaleMaxUp(45f, step = 5f))
    }

    @Test fun degenerate_inputs_pass_through_unchanged() {
        assertEquals(0f, DialGeometry.snapScaleMaxUp(0f))
        assertEquals(-5f, DialGeometry.snapScaleMaxUp(-5f))
        assertEquals(43.5f, DialGeometry.snapScaleMaxUp(43.5f, step = 0f))
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

    // --- centreScale: the acceptance criterion is that the centre block never collides with the
    // tick numbers (scale stays comfortably above its 0.5 floor), NOT that scale is always
    // exactly 1.0 — the test below shows a real dial where a mild, expected shrink is correct.
    // Also covers the shrink-never-grow safety net for a pathologically oversized centre block.

    // Replaces a stale pin: this test used to hand-pick `centreHalfDiagonal = 14f`, which encoded
    // the PRE-localization centre block (the English label "MOTOR", ~12.7dp). After localization
    // the real Russian label is longer — DialGeometryTest has no Compose text measurer available
    // (no Compose UI-test dependency in this repo, and this task deliberately doesn't add one),
    // so this derives its inputs from the SAME committed font-fraction constants DialGauge draws
    // with (DialGeometry.LabelFontFraction et al.) plus two named, documented ESTIMATES standing
    // in for real text measurement: an average glyph width and a line height, both as fractions
    // of font size. Every other number mirrors DialGauge's own draw-lambda arithmetic exactly
    // (radius fractions, spacing, gap, margin).
    @Test fun centre_scale_on_the_real_localized_small_corner_dial_shrinks_mildly_and_stays_well_clear_of_the_floor() {
        // Worst case per the task report: the Russian "ТЕМП. МОТОРА" label (ride_motor_temp,
        // uppercased by ClassicRideCluster) on a corner dial (sizeFraction 0.32, ClusterPlacement)
        // at a 360dp phone screen. RideDashboardScreen wraps its whole column in 12dp of padding
        // on each side, so the cluster itself is 360 - 24 = 336dp wide. (dp is used as a bare unit
        // throughout, not converted to real px: every quantity here — radius, spacing, margins,
        // gap — is either a Dp value at the same implied density or a fraction of one, and
        // DialGauge's own pxToSp/toPx round-trip is density-invariant by construction (see its
        // KDoc), so the density cancels out of the final ratio and never has to be chosen.)
        val clusterWidthDp = 360f - 2 * 12f
        val cornerDialSizeFraction = 0.32f // ClusterPlacement.slots' corner SlotBox.sizeFraction
        val dialDiameterDp = clusterWidthDp * cornerDialSizeFraction
        val dialPaddingDp = 4f // DialGauge.kt's `DialPadding`
        val radius = dialDiameterDp / 2f - dialPaddingDp // DialGauge's own `radius` val

        val labelFontSize = DialGeometry.LabelFontFraction * radius
        val labelLetterSpacing = DialGeometry.LabelLetterSpacingFraction * radius
        val valueFontSize = DialGeometry.ValueFontFraction * radius
        val unitFontSize = DialGeometry.UnitFontFraction * radius
        val numberFontSize = DialGeometry.NumberFontFraction * radius

        val label = "ТЕМП. МОТОРА" // ride_motor_temp, uppercased — 12 characters incl. "." and the space
        val labelWidth = estimatedTextWidth(label.length, labelFontSize, labelLetterSpacing)
        val labelHeight = estimatedTextHeight(labelFontSize)
        // The value ("68") and unit ("°C") this dial prints are far shorter than the label, so
        // they can't be the widest line — only their height contributes to the stacked block.
        val valueHeight = estimatedTextHeight(valueFontSize)
        val unitHeight = estimatedTextHeight(unitFontSize)
        val spacing = 2f // DialGauge.kt's `spacing` between the three stacked lines

        val centerMaxWidth = labelWidth
        val centerTotalHeight = labelHeight + spacing + valueHeight + spacing + unitHeight
        val centreHalfDiagonal = sqrt(
            (centerMaxWidth / 2f) * (centerMaxWidth / 2f) + (centerTotalHeight / 2f) * (centerTotalHeight / 2f)
        )

        // Tick labels on this same dial (BOTTOM_RIGHT, ClassicDialSpecs' motor-temp scale:
        // 0..140, majorTicks = 7) top out at 3 digits ("140") — the widest number this dial ever
        // prints, and so the one that pushes numberR (and therefore the safe radius) inward the
        // most.
        val numberHalfDiagonal = run {
            val w = estimatedTextWidth("140".length, numberFontSize)
            val h = estimatedTextHeight(numberFontSize)
            sqrt((w / 2f) * (w / 2f) + (h / 2f) * (h / 2f))
        }

        val tickOuterR = radius * 0.90f // DialGauge's own fraction
        val majorTickLen = radius * 0.13f // DialGauge's own fraction
        val numberGap = 4f // DialGauge.kt's `numberGap`
        val collisionMargin = 4f // DialGauge.kt's `collisionMargin`

        val numberR = DialGeometry.numberRadius(tickOuterR, majorTickLen, numberGap, numberHalfDiagonal)
        val scale = DialGeometry.centreScale(numberR, numberHalfDiagonal, collisionMargin, centreHalfDiagonal)

        // The real, post-localization number (~0.92 from these estimates) is NOT 1.0 any more:
        // the shrink guard genuinely engages now that the label is longer, but only mildly, and
        // stays far clear of the 0.5 floor — i.e. no collision. Bounded rather than pinned to one
        // decimal so this doesn't become fragile to the estimate constants' exact values.
        assertTrue(scale < 0.99f, "expected the shrink guard to engage now that the label is localized, was $scale")
        assertTrue(scale > 0.75f, "expected the shrink to stay mild, well clear of the 0.5 floor, was $scale")
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

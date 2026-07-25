package ru.sodovaya.volty.presentation.ride.gauge

import ru.sodovaya.volty.presentation.ride.gauge.VescNibShading.Rgb
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the radius fractions, the screen-angle convention, the trace sweep and the nib shading that
 * `VescDialGauge` draws with — the arithmetic that would otherwise be buried inside a Canvas draw
 * lambda where no test can reach it. Every expected number is hand-computed from VESC Tool's
 * `mobile/CustomGauge.qml` with an outer radius of 100, so a fraction reads directly as a percent.
 */
class VescDialMetricsTest {

    private val epsilon = 1e-9
    private val r = 100.0

    // Speed: minAngle=-225, maxAngle=45 (normal). Duty: minAngle=210, maxAngle=-15 (inverted).
    // Both are real ranges out of VESC's own mobile/RtDataSetup.qml.
    private val speed = VescGaugeRange(-225.0, 45.0, 0.0, 60.0, 10.0)
    private val duty = VescGaugeRange(210.0, -15.0, -100.0, 100.0, 25.0)
    private val current = VescGaugeRange(-225.0, 45.0, -100.0, 100.0, 25.0)

    // --- the needle is a blade at the RIM (QML:144-151) ------------------------------------

    @Test fun the_needle_tip_sits_five_percent_in_from_the_rim() {
        assertEquals(95.0, VescDialMetrics.needleTipRadius(r), epsilon)
    }

    @Test fun the_needle_base_stops_at_73_percent_of_the_radius_and_never_reaches_the_centre() {
        // tip 0.95R, blade 0.22R long -> base 0.73R. The single number this whole port turns on:
        // a needle drawn from the centre outward would put its root on the readout.
        assertEquals(73.0, VescDialMetrics.needleBaseRadius(r), epsilon)
        assertTrue(VescDialMetrics.needleBaseRadius(r) > 0.0)
    }

    @Test fun the_needle_never_even_reaches_the_ring_the_scale_numbers_sit_on() {
        // 0.73R base vs 0.66R label ring: the blade clears the numbers too, not just the readout.
        assertTrue(
            VescDialMetrics.needleBaseRadius(r) > VescDialGeometry.labelRadius(r),
            "needle base ${VescDialMetrics.needleBaseRadius(r)} must stay outside the " +
                "label ring at ${VescDialGeometry.labelRadius(r)}"
        )
    }

    @Test fun the_needle_blade_is_shorter_than_it_is_far_from_the_centre() {
        // A sanity restatement of the same geometry that does not depend on the exact fractions:
        // the blade's length (0.22R) is far less than the gap it leaves to the centre (0.73R).
        val bladeLength = VescDialMetrics.needleTipRadius(r) - VescDialMetrics.needleBaseRadius(r)
        assertTrue(bladeLength < VescDialMetrics.needleBaseRadius(r))
    }

    @Test fun the_needle_is_narrower_than_it_is_long() {
        assertEquals(12.0, r * VescDialMetrics.NEEDLE_WIDTH_FRACTION, epsilon)
        assertEquals(22.0, r * VescDialMetrics.NEEDLE_LENGTH_FRACTION, epsilon)
    }

    // --- ticks (QML:33-34, 319-320, 329-330, 390, 412) -------------------------------------

    @Test fun both_tick_families_share_an_outer_end_at_93_percent_of_the_radius() {
        assertEquals(93.0, VescDialMetrics.tickOuterRadius(r), epsilon)
    }

    @Test fun a_major_tick_is_ten_percent_of_the_radius_long_and_a_minor_seven() {
        assertEquals(83.0, VescDialMetrics.majorTickInnerRadius(r), epsilon)
        assertEquals(86.0, VescDialMetrics.minorTickInnerRadius(r), epsilon)
    }

    @Test fun minor_ticks_are_shorter_and_thinner_than_major_ticks() {
        assertTrue(VescDialMetrics.minorTickInnerRadius(r) > VescDialMetrics.majorTickInnerRadius(r))
        assertTrue(VescDialMetrics.MINOR_TICK_WIDTH_FRACTION < VescDialMetrics.MAJOR_TICK_WIDTH_FRACTION)
    }

    @Test fun the_scale_numbers_sit_clear_inside_the_inner_end_of_the_longest_tick() {
        // 0.66R labels vs 0.83R major tick inner end — the ticks and the numbers never touch.
        assertTrue(VescDialGeometry.labelRadius(r) < VescDialMetrics.majorTickInnerRadius(r))
    }

    // --- bezel (QML:230, 245, 258) ---------------------------------------------------------

    @Test fun the_two_bezel_rings_are_flush_and_share_one_stroke_width() {
        val stroke = VescDialMetrics.bezelStroke(r)
        assertEquals(3.5, stroke, epsilon)
        assertEquals(98.25, VescDialMetrics.bezelOuterRingRadius(r), epsilon)
        // outerRadius - stroke*3/2 + 1 = 100 - 5.25 + 1
        assertEquals(95.75, VescDialMetrics.bezelInnerRingRadius(r), epsilon)
        // Flush apart from the original's deliberate one-pixel seam closer.
        val gap = VescDialMetrics.bezelOuterRingRadius(r) - VescDialMetrics.bezelInnerRingRadius(r)
        assertEquals(stroke - 1.0, gap, epsilon)
    }

    @Test fun the_bezel_sits_entirely_outside_the_ticks() {
        assertTrue(
            VescDialMetrics.bezelInnerRingRadius(r) - VescDialMetrics.bezelStroke(r) / 2.0 >
                VescDialMetrics.tickOuterRadius(r)
        )
    }

    /**
     * Why emphasis (spec B §7.2) is NOT a heavier bezel, recorded as an assertion so nobody
     * re-derives it the hard way: the two rings already fill the whole band between the ticks and
     * the rim, so there is no weight left to add. `2 * bezelStroke` IS `outerRadius -
     * tickOuterRadius` exactly. The cue lives in [VescClusterGeometry.EMPHASIS_SIZE_FACTOR].
     */
    @Test fun the_bezel_exactly_fills_the_band_between_the_ticks_and_the_rim() {
        assertEquals(r - VescDialMetrics.tickOuterRadius(r), 2.0 * VescDialMetrics.bezelStroke(r), epsilon)
    }

    // --- trace glow (QML:212-220) ----------------------------------------------------------

    @Test fun the_trace_arc_and_its_glow_gradient_match_the_qml() {
        assertEquals(88.0, VescDialMetrics.traceRadius(r), epsilon)
        assertEquals(20.0, VescDialMetrics.traceStroke(r), epsilon)
        assertEquals(87.0, VescDialMetrics.traceGlowInnerRadius(r), epsilon)
        assertEquals(95.0, VescDialMetrics.traceGlowOuterRadius(r), epsilon)
    }

    @Test fun the_glow_gradient_inner_stop_is_scale_free() {
        // Compose radial gradients always start their stop scale at the centre, so the QML's inner
        // radius r0 becomes a stop position — and it must not depend on the dial's size.
        assertEquals(0.87 / 0.95, VescDialMetrics.traceGlowInnerStop, epsilon)
        assertTrue(VescDialMetrics.traceGlowInnerStop > 0.0 && VescDialMetrics.traceGlowInnerStop < 1.0)
    }

    // --- the twelve-to-three o'clock conversion (QML:221-222, 456-457) ----------------------

    @Test fun a_gauge_angle_of_zero_points_straight_up_on_the_drawing_surface() {
        assertEquals(-90.0, VescDialGeometry.screenAngleDegrees(0.0), epsilon)
        assertEquals(-PI / 2.0, VescDialGeometry.screenAngleRadians(0.0), epsilon)
    }

    @Test fun a_gauge_angle_of_ninety_points_right() {
        assertEquals(0.0, VescDialGeometry.screenAngleRadians(90.0), epsilon)
    }

    @Test fun the_conversion_is_a_pure_offset_so_it_preserves_angular_differences() {
        val a = VescDialGeometry.screenAngleDegrees(210.0)
        val b = VescDialGeometry.screenAngleDegrees(-15.0)
        assertEquals(210.0 - (-15.0), a - b, epsilon)
    }

    // --- trace sweep replaces the QML's anticlockwise flag (QML:218-223) --------------------

    @Test fun a_positive_reading_on_a_normal_gauge_sweeps_clockwise() {
        // valueToAngle(30) = -90, valueToAngle(0) = -225 -> +135
        assertEquals(135.0, speed.traceSweepDegrees(30.0), epsilon)
    }

    @Test fun a_reading_of_zero_sweeps_nothing() {
        assertEquals(0.0, speed.traceSweepDegrees(0.0), epsilon)
        assertEquals(0.0, duty.traceSweepDegrees(0.0), epsilon)
    }

    @Test fun a_negative_reading_on_a_normal_gauge_sweeps_anticlockwise() {
        // current: valueToAngle(0) = -90, valueToAngle(-50) = -157.5 -> -67.5
        assertEquals(-67.5, current.traceSweepDegrees(-50.0), epsilon)
    }

    @Test fun a_positive_reading_on_an_INVERTED_gauge_sweeps_anticlockwise() {
        // duty: valueToAngle(0) = 97.5, valueToAngle(50) = 41.25 -> -56.25.
        // The QML's flag `(value * isInverted) < 0` is true here (50 * -1); the signed
        // difference reproduces it with no branch.
        assertEquals(-56.25, duty.traceSweepDegrees(50.0), epsilon)
    }

    @Test fun a_negative_reading_on_an_INVERTED_gauge_sweeps_clockwise() {
        assertEquals(56.25, duty.traceSweepDegrees(-50.0), epsilon)
    }

    @Test fun the_sign_of_the_sweep_always_equals_the_qml_anticlockwise_flag() {
        listOf(speed, duty, current).forEach { range ->
            val isInverted = if (range.isInverted) -1 else 1
            listOf(-120.0, -50.0, -1.0, 0.0, 1.0, 50.0, 120.0).forEach { v ->
                val sweep = range.traceSweepDegrees(v)
                val qmlAnticlockwise = (v * isInverted) < 0.0
                if (abs(sweep) > epsilon) {
                    assertEquals(
                        qmlAnticlockwise,
                        sweep < 0.0,
                        "range $range at value $v: sweep $sweep disagrees with the QML flag"
                    )
                }
            }
        }
    }

    @Test fun the_sweep_never_exceeds_the_gauges_own_angle_range_so_it_needs_no_wrap_handling() {
        listOf(speed, duty, current).forEach { range ->
            listOf(-999.0, -50.0, 0.0, 50.0, 999.0).forEach { v ->
                assertTrue(abs(range.traceSweepDegrees(v)) <= abs(range.angleRange) + epsilon)
            }
        }
    }

    // --- hasTickmarks guard ----------------------------------------------------------------

    @Test fun a_gauge_with_a_label_step_has_tickmarks() {
        assertTrue(speed.hasTickmarks)
        assertTrue(duty.hasTickmarks)
    }

    @Test fun a_gauge_with_no_label_step_has_no_tickmarks_and_so_no_division_by_zero() {
        val stepless = speed.copy(labelStep = 0.0)
        assertEquals(0, stepless.tickmarkCount)
        assertFalse(stepless.hasTickmarks)
    }

    @Test fun a_gauge_with_exactly_one_tickmark_is_also_excluded() {
        // floor((10-0)/20 + 1) = 1 -> tickmarkSectionSize would divide by zero.
        val single = VescGaugeRange(-225.0, 45.0, 0.0, 10.0, 20.0)
        assertEquals(1, single.tickmarkCount)
        assertFalse(single.hasTickmarks)
    }

    // --- nib shading (QML:50, 173-174, 191-192) --------------------------------------------

    @Test fun the_shade_factor_is_one_when_the_needle_faces_the_light_head_on() {
        assertEquals(1.0, VescNibShading.shadeFactor(0.0, 0.0), epsilon)
        assertEquals(1.0, VescNibShading.shadeFactor(180.0, 0.0), epsilon)
    }

    @Test fun the_shade_factor_swings_between_a_half_and_one_and_a_half() {
        assertEquals(1.5, VescNibShading.shadeFactor(90.0, 0.0), epsilon)
        assertEquals(0.5, VescNibShading.shadeFactor(-90.0, 0.0), epsilon)
        (-360..360 step 7).forEach { deg ->
            val f = VescNibShading.shadeFactor(deg.toDouble(), 0.0)
            assertTrue(f in 0.5 - epsilon..1.5 + epsilon)
        }
    }

    @Test fun the_two_blade_halves_are_lit_from_opposite_sides() {
        // The QML pairs offset 0 (right half) with offset 180 (left half): exact opposites, so
        // one half is at its brightest precisely when the other is at its darkest.
        val angle = 90.0
        assertEquals(1.5, VescNibShading.shadeFactor(angle, 0.0), epsilon)
        assertEquals(0.5, VescNibShading.shadeFactor(angle, 180.0), epsilon)
    }

    @Test fun darker_by_a_factor_above_one_scales_every_component_down() {
        assertEquals(Rgb(0.5, 0.25, 0.0), VescNibShading.darker(Rgb(1.0, 0.5, 0.0), 2.0))
    }

    @Test fun darker_by_a_factor_of_one_is_the_identity() {
        val c = Rgb(0.4, 0.7, 0.1)
        assertEquals(c, VescNibShading.darker(c, 1.0))
    }

    @Test fun darker_by_a_factor_below_one_brightens_because_qt_delegates_to_lighter() {
        // v = 0.5, 1/factor = 2 -> 1.0, no overflow, so it is a plain scale-up.
        assertEquals(Rgb(1.0, 0.5, 0.0), VescNibShading.darker(Rgb(0.5, 0.25, 0.0), 0.5))
    }

    @Test fun lighter_beyond_full_brightness_desaturates_instead_of_clipping_the_hue() {
        // Pure red at factor 1.5: v=1 already, so the 0.5 overflow comes out of saturation.
        val out = VescNibShading.lighter(Rgb(1.0, 0.0, 0.0), 1.5)
        assertEquals(1.0, out.r, epsilon)
        assertEquals(0.5, out.g, epsilon)
        assertEquals(0.5, out.b, epsilon)
    }

    @Test fun lighter_keeps_the_hue_by_preserving_the_middle_components_relative_position() {
        // Orange (1.0, 0.5, 0.0): green sits exactly halfway between min and max, and must still.
        val out = VescNibShading.lighter(Rgb(1.0, 0.5, 0.0), 1.5)
        val t = (out.g - out.b) / (out.r - out.b)
        assertEquals(0.5, t, epsilon)
        assertTrue(out.r >= out.g && out.g >= out.b) // ordering, hence hue sector, unchanged
    }

    @Test fun lighter_on_a_grey_washes_all_the_way_to_white() {
        assertEquals(Rgb(1.0, 1.0, 1.0), VescNibShading.lighter(Rgb(0.8, 0.8, 0.8), 1.5))
    }

    @Test fun lighter_never_produces_a_component_outside_the_unit_range() {
        listOf(
            Rgb(1.0, 0.0, 0.0), Rgb(0.0, 0.6, 0.9), Rgb(0.9, 0.9, 0.2), Rgb(0.05, 0.05, 0.05)
        ).forEach { c ->
            listOf(1.0, 1.2, 1.5, 2.0, 4.0).forEach { f ->
                val out = VescNibShading.lighter(c, f)
                listOf(out.r, out.g, out.b).forEach { component ->
                    assertTrue(component in -epsilon..1.0 + epsilon, "$c * $f gave $out")
                }
            }
        }
    }

    @Test fun the_trace_colour_is_the_nib_colour_lightened_by_one_and_a_half() {
        val nib = Rgb(0.0, 0.6, 0.9)
        assertEquals(VescNibShading.lighter(nib, 1.5), VescNibShading.traceColor(nib))
    }

    @Test fun the_trace_colour_is_never_darker_than_the_nib_colour_it_came_from() {
        listOf(Rgb(1.0, 0.0, 0.0), Rgb(0.0, 0.6, 0.9), Rgb(0.2, 0.2, 0.2)).forEach { nib ->
            val trace = VescNibShading.traceColor(nib)
            assertTrue(trace.r >= nib.r - epsilon)
            assertTrue(trace.g >= nib.g - epsilon)
            assertTrue(trace.b >= nib.b - epsilon)
        }
    }

    // --- fonts are fractions of the radius, and the readout dominates (QML:107, 309) --------

    @Test fun the_value_font_is_two_and_a_half_times_every_other_font_on_the_dial() {
        assertEquals(0.3, VescDialMetrics.VALUE_FONT_FRACTION, epsilon)
        listOf(
            VescDialMetrics.CAPTION_FONT_FRACTION,
            VescDialMetrics.UNIT_FONT_FRACTION,
            VescDialMetrics.TICK_LABEL_FONT_FRACTION
        ).forEach { other ->
            assertEquals(0.12, other, epsilon)
            assertEquals(2.5, VescDialMetrics.VALUE_FONT_FRACTION / other, 1e-9)
        }
    }
}

package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [VescGaugeRange] / [VescDialGeometry] against values computed BY HAND from VESC Tool's
 * own `CustomGauge.qml` (see task-1-report.md for the full arithmetic). Two real per-gauge ranges
 * from `mobile/RtDataSetup.qml` are used throughout so this is not testing invented numbers:
 *   - Speed:  minAngle=-225, maxAngle=45,  minimumValue=0,    maximumValue=60,  labelStep=10  (normal — minAngle < maxAngle)
 *   - Duty:   minAngle=210,  maxAngle=-15, minimumValue=-100, maximumValue=100, labelStep=25  (INVERTED — minAngle > maxAngle)
 */
class VescDialGeometryTest {

    private val speed = VescGaugeRange(
        minAngle = -225.0,
        maxAngle = 45.0,
        minimumValue = 0.0,
        maximumValue = 60.0,
        labelStep = 10.0
    )

    private val duty = VescGaugeRange(
        minAngle = 210.0,
        maxAngle = -15.0,
        minimumValue = -100.0,
        maximumValue = 100.0,
        labelStep = 25.0
    )

    private val epsilon = 1e-9

    // --- isInverted (QML CustomGauge.qml:37) ---

    @Test fun speed_is_not_inverted_because_maxAngle_exceeds_minAngle() {
        assertFalse(speed.isInverted)
    }

    @Test fun duty_is_inverted_because_maxAngle_is_less_than_minAngle() {
        assertTrue(duty.isInverted)
    }

    // --- valueToAngle (QML:61-65) ---
    // normalised = (value - min) / (max - min), clamped to 0..1, then
    // (maxAngle - minAngle) * normalised + minAngle.

    @Test fun speed_valueToAngle_at_minimum_sits_at_minAngle() {
        assertEquals(-225.0, speed.valueToAngle(0.0), epsilon)
    }

    @Test fun speed_valueToAngle_at_maximum_sits_at_maxAngle() {
        assertEquals(45.0, speed.valueToAngle(60.0), epsilon)
    }

    @Test fun speed_valueToAngle_at_the_midpoint() {
        // normalised = (30-0)/60 = 0.5; angle = 270*0.5 + (-225) = -90
        assertEquals(-90.0, speed.valueToAngle(30.0), epsilon)
    }

    @Test fun speed_valueToAngle_clamps_below_minimum() {
        assertEquals(-225.0, speed.valueToAngle(-999.0), epsilon)
    }

    @Test fun speed_valueToAngle_clamps_above_maximum() {
        assertEquals(45.0, speed.valueToAngle(999.0), epsilon)
    }

    @Test fun duty_valueToAngle_at_minimum_sits_at_minAngle_even_though_minAngle_is_the_larger_number() {
        // The mirrored gauge: minAngle=210 (larger) sits at minimumValue=-100.
        assertEquals(210.0, duty.valueToAngle(-100.0), epsilon)
    }

    @Test fun duty_valueToAngle_at_maximum_sits_at_maxAngle_even_though_maxAngle_is_the_smaller_number() {
        assertEquals(-15.0, duty.valueToAngle(100.0), epsilon)
    }

    @Test fun duty_valueToAngle_at_the_midpoint() {
        // normalised = (0-(-100))/200 = 0.5; angle = (-225)*0.5 + 210 = 97.5
        assertEquals(97.5, duty.valueToAngle(0.0), epsilon)
    }

    @Test fun duty_valueToAngle_clamps_below_minimum_to_minAngle() {
        assertEquals(210.0, duty.valueToAngle(-999.0), epsilon)
    }

    @Test fun duty_valueToAngle_clamps_above_maximum_to_maxAngle() {
        assertEquals(-15.0, duty.valueToAngle(999.0), epsilon)
    }

    /**
     * Restores a guard the rewrite dropped along with its old home: the predecessor this file
     * replaced (`DialGeometry.fraction`) divided by `(max - min)` behind an explicit
     * `if (span <= 0f) return 0f`, pinned by its own `a_degenerate_scale_does_not_divide_by_zero`.
     * No range this project actually builds has `max == min`, so this is unreachable today — the
     * point is that it stays caught rather than silently reappearing as a `NaN` needle angle the
     * next time someone builds a [VescGaugeRange] by hand (a test fixture, a future caller).
     */
    @Test fun a_degenerate_zero_span_range_rests_the_needle_at_minAngle_instead_of_dividing_by_zero() {
        val flat = VescGaugeRange(minAngle = -225.0, maxAngle = 45.0, minimumValue = 5.0, maximumValue = 5.0, labelStep = 10.0)
        assertEquals(-225.0, flat.valueToAngle(5.0), epsilon)
        assertTrue(flat.valueToAngle(5.0).isFinite())
        // Not just the exact value that produced the old NaN — any value must resolve finitely.
        assertTrue(flat.valueToAngle(999.0).isFinite())
        assertTrue(flat.valueToAngle(-999.0).isFinite())
    }

    // --- isCovered (QML:72-83) ---
    // positive gauge value: tick in [0, gaugeValue] is covered.
    // negative gauge value: tick in [gaugeValue, 0] is covered.

    @Test fun a_tick_between_zero_and_a_positive_gauge_value_is_covered() {
        assertTrue(VescDialGeometry.isCovered(tickValue = 15.0, gaugeValue = 30.0))
    }

    @Test fun a_tick_above_a_positive_gauge_value_is_not_covered() {
        assertFalse(VescDialGeometry.isCovered(tickValue = 45.0, gaugeValue = 30.0))
    }

    @Test fun a_negative_tick_is_never_covered_when_the_gauge_value_is_positive() {
        assertFalse(VescDialGeometry.isCovered(tickValue = -5.0, gaugeValue = 30.0))
    }

    @Test fun a_tick_between_a_negative_gauge_value_and_zero_is_covered() {
        assertTrue(VescDialGeometry.isCovered(tickValue = -10.0, gaugeValue = -20.0))
    }

    @Test fun a_tick_below_a_negative_gauge_value_is_not_covered() {
        assertFalse(VescDialGeometry.isCovered(tickValue = -25.0, gaugeValue = -20.0))
    }

    @Test fun a_positive_tick_is_never_covered_when_the_gauge_value_is_negative() {
        assertFalse(VescDialGeometry.isCovered(tickValue = 5.0, gaugeValue = -20.0))
    }

    @Test fun zero_is_covered_regardless_of_which_branch_the_gauge_value_takes() {
        assertTrue(VescDialGeometry.isCovered(tickValue = 0.0, gaugeValue = 30.0))
        assertTrue(VescDialGeometry.isCovered(tickValue = 0.0, gaugeValue = -20.0))
    }

    @Test fun only_the_zero_tick_is_covered_when_the_gauge_value_is_exactly_zero() {
        assertTrue(VescDialGeometry.isCovered(tickValue = 0.0, gaugeValue = 0.0))
        assertFalse(VescDialGeometry.isCovered(tickValue = 0.001, gaugeValue = 0.0))
        assertFalse(VescDialGeometry.isCovered(tickValue = -0.001, gaugeValue = 0.0))
    }

    // --- tickmarkCount (QML:40) ---

    @Test fun speed_tickmarkCount_is_seven_majors() {
        // floor((60-0)/10 + 1) = 7
        assertEquals(7, speed.tickmarkCount)
    }

    @Test fun duty_tickmarkCount_is_nine_majors() {
        // floor((100-(-100))/25 + 1) = floor(9) = 9
        assertEquals(9, duty.tickmarkCount)
    }

    // --- tickmarkAngleFromIndex / tickmarkValueFromIndex (QML:358-360, 369-371) ---

    @Test fun speed_first_major_tick_sits_at_minAngle_with_the_minimum_value() {
        assertEquals(-225.0, speed.tickmarkAngleFromIndex(0), epsilon)
        assertEquals(0.0, speed.tickmarkValueFromIndex(0), epsilon)
    }

    @Test fun speed_last_major_tick_sits_at_maxAngle_with_the_maximum_value() {
        assertEquals(45.0, speed.tickmarkAngleFromIndex(6), epsilon)
        assertEquals(60.0, speed.tickmarkValueFromIndex(6), epsilon)
    }

    @Test fun speed_major_tick_section_size_is_45_degrees() {
        // rangeUsed(7,10)/(7-1) = (((7-1)*10)/60)*270 / 6 = 45
        assertEquals(45.0, speed.tickmarkSectionSize, epsilon)
    }

    @Test fun duty_first_major_tick_sits_at_minAngle_the_larger_number_with_the_minimum_value() {
        assertEquals(210.0, duty.tickmarkAngleFromIndex(0), epsilon)
        assertEquals(-100.0, duty.tickmarkValueFromIndex(0), epsilon)
    }

    @Test fun duty_last_major_tick_sits_at_maxAngle_the_smaller_number_with_the_maximum_value() {
        assertEquals(-15.0, duty.tickmarkAngleFromIndex(8), epsilon)
        assertEquals(100.0, duty.tickmarkValueFromIndex(8), epsilon)
    }

    @Test fun duty_major_tick_section_size_is_negative_because_the_gauge_sweeps_the_other_way() {
        // rangeUsed(9,25)/(9-1) = (((9-1)*25)/200)*(-225) / 8 = -28.125
        assertEquals(-28.125, duty.tickmarkSectionSize, epsilon)
    }

    @Test fun major_tick_angles_increase_monotonically_on_a_normal_range() {
        val angles = (0 until speed.tickmarkCount).map { speed.tickmarkAngleFromIndex(it) }
        assertEquals(angles.sorted(), angles)
    }

    @Test fun major_tick_angles_DECREASE_monotonically_on_an_inverted_range() {
        val angles = (0 until duty.tickmarkCount).map { duty.tickmarkAngleFromIndex(it) }
        assertEquals(angles.sortedDescending(), angles)
        // Not vacuous: also confirm it is NOT sorted ascending, i.e. genuinely reversed.
        assertTrue(angles != angles.sorted())
    }

    // --- minor ticks: 4 between each pair of majors, offset so a minor never lands on its parent (QML:41, 362-367) ---

    @Test fun speed_has_24_minor_ticks_total_four_between_each_of_the_six_major_gaps() {
        assertEquals(24, speed.totalMinorTickmarkCount)
    }

    @Test fun duty_has_32_minor_ticks_total_four_between_each_of_the_eight_major_gaps() {
        assertEquals(32, duty.totalMinorTickmarkCount)
    }

    @Test fun speed_first_minor_after_a_major_is_offset_not_coincident_with_it() {
        // baseAngle = tickmarkAngleFromIndex(0) = -225; relative = (0%4)*9 + 9 = 9 -> -216
        val angle = speed.minorTickmarkAngleFromIndex(0)
        assertEquals(-216.0, angle, epsilon)
        assertTrue(angle != speed.tickmarkAngleFromIndex(0))
    }

    @Test fun speed_last_minor_before_the_next_major_is_offset_not_coincident_with_it() {
        // index 3 is the 4th minor within the first major gap: relative = (3%4)*9 + 9 = 36 -> -189
        val angle = speed.minorTickmarkAngleFromIndex(3)
        assertEquals(-189.0, angle, epsilon)
        assertTrue(angle != speed.tickmarkAngleFromIndex(1)) // next major is at -180, not -189
    }

    @Test fun speed_minor_index_four_rolls_into_the_second_major_group() {
        // floor(4/4) = 1 -> baseAngle = tickmarkAngleFromIndex(1) = -180; relative = (4%4=0)*9+9 = 9 -> -171
        assertEquals(-171.0, speed.minorTickmarkAngleFromIndex(4), epsilon)
    }

    @Test fun speed_minor_tick_sequence_across_two_major_groups_matches_hand_computed_values() {
        val expected = listOf(-216.0, -207.0, -198.0, -189.0, -171.0, -162.0, -153.0, -144.0)
        val actual = (0..7).map { speed.minorTickmarkAngleFromIndex(it) }
        expected.zip(actual).forEach { (e, a) -> assertEquals(e, a, epsilon) }
    }

    @Test fun duty_first_minor_after_its_major_is_offset_the_other_direction() {
        // baseAngle = 210; minorTickmarkSectionSize = -28.125/5 = -5.625; relative = (0%4)*-5.625 + -5.625 = -5.625
        val angle = duty.minorTickmarkAngleFromIndex(0)
        assertEquals(204.375, angle, epsilon)
        assertTrue(angle != duty.tickmarkAngleFromIndex(0))
    }

    @Test fun minor_tick_values_interpolate_between_their_major_neighbours() {
        // tickmarkValueFromMinorIndex(0) = tickmarkValueFromIndex(0) + (0*minorSectionValue + minorSectionValue)
        // minorTickmarkSectionValue = tickmarkSectionValue(10) / 5 = 2 -> 0 + 2 = 2
        assertEquals(2.0, speed.tickmarkValueFromMinorIndex(0), epsilon)
        // minor index 3 (last minor before major 1): 0 + (3*2 + 2) = 8
        assertEquals(8.0, speed.tickmarkValueFromMinorIndex(3), epsilon)
        // minor index 4 (first minor after major 1, value 10): 10 + (0*2+2) = 12
        assertEquals(12.0, speed.tickmarkValueFromMinorIndex(4), epsilon)
    }

    // --- labelInset / labelRadius: labels centre at radius R - 0.34R (QML:32, 456-457) ---

    @Test fun label_inset_is_34_percent_of_the_outer_radius() {
        assertEquals(34.0, VescDialGeometry.labelInset(outerRadius = 100.0), epsilon)
    }

    @Test fun label_radius_sits_at_66_percent_of_the_outer_radius() {
        assertEquals(66.0, VescDialGeometry.labelRadius(outerRadius = 100.0), epsilon)
    }

    // --- the needle's rotation angle is exactly valueToAngle (QML:152) — no separate function,
    // just confirming the value a caller would actually animate the needle's Rotation to.

    @Test fun the_needle_angle_for_a_three_quarter_scale_reading_is_valueToAngle() {
        // normalised = 45/60 = 0.75; angle = 270*0.75 + (-225) = 202.5 - 225 = -22.5
        assertEquals(-22.5, speed.valueToAngle(45.0), epsilon)
    }

    // --- centre text stack anchoring (QML:101-140), lifted out of the draw lambda (Task 2 review) ---

    @Test fun the_value_is_centred_on_centerY_regardless_of_its_own_height() {
        // valueTop = centerY - valueHeight/2, so the value's MIDPOINT sits exactly on centerY.
        val layout = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 40.0, captionHeight = 10.0)
        assertEquals(180.0, layout.valueTop, epsilon)
        assertEquals(200.0, layout.valueTop + 40.0 / 2.0, epsilon) // midpoint == centerY
    }

    @Test fun the_caption_bottom_edge_meets_the_values_top_edge() {
        // captionTop = valueTop - captionHeight, i.e. captionTop + captionHeight == valueTop.
        val layout = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 40.0, captionHeight = 10.0)
        assertEquals(170.0, layout.captionTop, epsilon)
        assertEquals(layout.valueTop, layout.captionTop + 10.0, epsilon)
    }

    @Test fun the_unit_top_edge_meets_the_values_bottom_edge_using_the_VALUES_height_not_its_own() {
        // QML:117 anchors the unit to speedLabel (the VALUE)'s bottom edge -- unitTop depends on
        // valueHeight, never on the unit's own measured height (there is no unitHeight parameter
        // at all). This is the one detail a "just use the block's own height" refactor would get
        // wrong, so it is pinned directly: two calls that share centerY/valueHeight but differ
        // only in captionHeight must produce the SAME unitTop.
        val a = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 40.0, captionHeight = 10.0)
        val b = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 40.0, captionHeight = 999.0)
        assertEquals(220.0, a.unitTop, epsilon) // valueTop(180) + valueHeight(40)
        assertEquals(a.unitTop, b.unitTop, epsilon)
        // Not vacuous: captionTop DOES differ between a and b, so this is not "everything is equal".
        assertTrue(a.captionTop != b.captionTop)
    }

    @Test fun a_taller_value_pushes_the_caption_further_up_and_the_unit_further_down() {
        val short = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 20.0, captionHeight = 10.0)
        val tall = VescDialGeometry.centerTextLayout(centerY = 200.0, valueHeight = 60.0, captionHeight = 10.0)
        assertTrue(tall.captionTop < short.captionTop, "a taller value must push the caption further up")
        assertTrue(tall.unitTop > short.unitTop, "a taller value must push the unit further down")
    }

    // =============================================================================================
    // Caption / unit fit — the localized captions CustomGauge.qml never had to draw
    // =============================================================================================
    //
    // This replaces the shrink-to-fit regression pin the previous renderer carried and Task 4
    // deleted with that renderer's files. It is not the same pin: the old one shrank the READOUT,
    // which is the one line that must never move (QML :107 makes it 0.3R, two and a half times
    // everything else on the dial). What is pinned here is the budget the two 0.12R lines get and
    // the factor they are scaled by when they exceed it.

    /**
     * Advance widths in units of 1/2048 em for the characters that appear in the four captions
     * below, read off Roboto Regular — the family Compose falls back to on Android, and therefore
     * the one the real measurement uses. Modelling the width rather than measuring it is the point
     * of the split the fix is built on: measurement needs a font, a density and a Compose
     * `TextMeasurer`; choosing a scale factor from a measured width needs none of those, and only
     * the second half belongs in a pure test.
     */
    private val robotoAdvance: Map<Char, Int> = mapOf(
        // Latin (CONSUMPTION)
        'C' to 1381, 'O' to 1520, 'N' to 1560, 'S' to 1370, 'U' to 1509,
        'M' to 2103, 'P' to 1290, 'T' to 1251, 'I' to 596,
        // Cyrillic (МОЩНОСТЬ, РАСХОД, ТЕМП. МОТОРА)
        'М' to 2103, 'О' to 1520, 'Щ' to 2100, 'Н' to 1502, 'С' to 1381,
        'Т' to 1251, 'Ь' to 1290, 'Р' to 1290, 'А' to 1336, 'Х' to 1319,
        'Д' to 1450, 'Е' to 1247, 'П' to 1502, '.' to 569
    )

    /** The width of [text] set at [fontSize], in the same unit as [fontSize]. */
    private fun width(text: String, fontSize: Double): Double =
        text.sumOf { requireNotNull(robotoAdvance[it]) { "no advance for '$it'" } } / 2048.0 * fontSize

    /**
     * The two dial sizes the eight-gauge cluster is built from on a 1080 px phone: `g2` for the six
     * ordinary dials and `1.05 * g2` for Power and Consumption (`RtDataSetup.qml` :103-104,
     * :489-490). Taken from the real fit rather than invented, so these are the radii a rider's
     * device actually draws.
     */
    private val clusterRadii: List<Pair<String, Double>> = run {
        val sizes = VescClusterGeometry.fit(1080.0, Double.POSITIVE_INFINITY)
        listOf("g2" to sizes.g2 / 2.0, "1.05*g2" to 1.05 * sizes.g2 / 2.0)
    }

    /**
     * The budget one 0.12R line gets on a dial of radius [r], measured the way the renderer does:
     * the value block is 0.3R tall (plus Roboto's own 1.17 line height) and the caption hangs off
     * its top edge, so the caption's outer edge sits `valueHeight/2 + captionHeight` from centre.
     */
    private fun captionBudget(r: Double): Double {
        val lineHeight = 1.17 // Roboto's ascent+descent, i.e. what a one-line layout measures
        val valueHeight = lineHeight * VescDialMetrics.VALUE_FONT_FRACTION * r
        val captionHeight = lineHeight * VescDialMetrics.CAPTION_FONT_FRACTION * r
        return VescDialGeometry.centerTextMaxWidth(r, valueHeight / 2.0 + captionHeight)
    }

    /**
     * The real worst cases, at the two sizes the cluster actually uses. `ТЕМП. МОТОРА` is listed as
     * the single line it is drawn as: `ClassicDialSpecs.twoLineCaption` splits it at its space, so
     * the widest line the renderer ever measures for it is `МОТОРА`.
     *
     * All four fit at their nominal 0.12R, and the assertion records by how much — the widest, EN
     * `CONSUMPTION`, uses about 79% of the budget. That is the pin: this is the margin a change to
     * `CAPTION_FONT_FRACTION`, `VALUE_FONT_FRACTION` or `labelInset` would eat into, and the number
     * to look at before adding a longer caption or a new locale.
     */
    @Test fun every_shipped_caption_fits_its_dial_at_full_size_with_the_margin_recorded() {
        val captions = listOf("МОЩНОСТЬ", "РАСХОД", "МОТОРА", "CONSUMPTION")
        clusterRadii.forEach { (name, r) ->
            val budget = captionBudget(r)
            captions.forEach { caption ->
                val w = width(caption, VescDialMetrics.CAPTION_FONT_FRACTION * r)
                assertEquals(
                    1.0,
                    VescDialGeometry.centerTextScale(w, budget),
                    epsilon,
                    "$caption should not need shrinking on a $name dial (${w} of $budget)"
                )
                assertTrue(w / budget < 0.85, "$caption uses ${w / budget} of the $name budget")
            }
            // ...and CONSUMPTION really is the tight one, so the margin above is not measuring the
            // wrong string.
            val widest = captions.maxByOrNull { width(it, VescDialMetrics.CAPTION_FONT_FRACTION * r) }
            assertEquals("CONSUMPTION", widest)
            assertTrue(
                width("CONSUMPTION", VescDialMetrics.CAPTION_FONT_FRACTION * r) / budget > 0.75,
                "the worst case should be close enough to the budget for this test to be worth having"
            )
        }
    }

    /**
     * The case the fix exists for: a caption longer than any shipped one is scaled down instead of
     * running out over the scale, and the factor is exactly the ratio of the two widths.
     */
    @Test fun a_caption_wider_than_its_dial_is_scaled_by_exactly_the_ratio_it_overflows_by() {
        clusterRadii.forEach { (name, r) ->
            val budget = captionBudget(r)
            val w = width(OVERLONG_CAPTION, VescDialMetrics.CAPTION_FONT_FRACTION * r)
            assertTrue(w > budget, "the test string must actually overflow on a $name dial")
            assertEquals(budget / w, VescDialGeometry.centerTextScale(w, budget), epsilon)
            assertTrue(VescDialGeometry.centerTextScale(w, budget) < 1.0)
        }
    }

    // A "the scale factor does not depend on how big the dial is" test used to live here: it
    // computed centerTextScale(width(caption, r), captionBudget(r)) at the cluster's two real radii
    // and asserted the two factors equal. Deleted, not strengthened: `centerTextScale`'s own
    // branches key ONLY on the ratio `maxWidth / measuredWidth` (see its definition below), and
    // both `width()` and `captionBudget()` are linear in `r` by construction (the first is a sum of
    // per-character advances times a fontSize that is itself `fraction * r`; the second is a chord
    // of a ring radius that is `0.66 * r`) — so `width(k*r) / captionBudget(k*r) ==
    // width(r) / captionBudget(r)` for ANY `k`, by algebra, independent of whether the underlying
    // fractions (0.66, the font sizes, …) are the CORRECT ones. A wrong-but-still-proportional
    // constant — the only kind of bug this whole codebase's "everything is a fraction of R"
    // convention would plausibly introduce — passes this comparison exactly as well as a correct
    // one, so it discriminates nothing beyond what `a_caption_wider_than_its_dial_is_scaled_by_...`
    // above already covers by computing the expected ratio independently at each radius.

    // --- the budget itself: a chord, not a diameter, and it never goes negative -----------------

    @Test fun the_budget_is_the_chord_of_the_number_ring_at_the_lines_own_height() {
        // At the centre line the chord IS the ring's diameter, 2 * 0.66R.
        assertEquals(132.0, VescDialGeometry.centerTextMaxWidth(100.0, 0.0), epsilon)
        // 0.66R = 66; at 33 out, half-chord = sqrt(66^2 - 33^2) = 57.157...
        assertEquals(2.0 * 57.15767664977295, VescDialGeometry.centerTextMaxWidth(100.0, 33.0), 1e-9)
        // Higher up is always narrower — this is the whole reason it is a chord.
        assertTrue(
            VescDialGeometry.centerTextMaxWidth(100.0, 40.0) <
                VescDialGeometry.centerTextMaxWidth(100.0, 20.0)
        )
    }

    @Test fun a_line_taller_than_the_number_ring_gets_a_zero_budget_rather_than_a_NaN() {
        assertEquals(0.0, VescDialGeometry.centerTextMaxWidth(100.0, 66.0), epsilon)
        assertEquals(0.0, VescDialGeometry.centerTextMaxWidth(100.0, 999.0), epsilon)
        assertEquals(0.0, VescDialGeometry.centerTextMaxWidth(0.0, 0.0), epsilon)
        // ...and a zero budget floors the scale instead of collapsing the text to nothing.
        assertEquals(
            VescDialGeometry.MIN_CENTER_TEXT_SCALE,
            VescDialGeometry.centerTextScale(50.0, 0.0),
            epsilon
        )
    }

    @Test fun the_scale_never_drops_below_the_floor_however_absurd_the_caption() {
        assertEquals(VescDialGeometry.MIN_CENTER_TEXT_SCALE, VescDialGeometry.centerTextScale(10_000.0, 100.0), epsilon)
        assertTrue(VescDialGeometry.MIN_CENTER_TEXT_SCALE > 0.0)
    }

    @Test fun an_empty_or_unmeasurable_line_is_left_alone_rather_than_scaled_to_zero() {
        assertEquals(1.0, VescDialGeometry.centerTextScale(0.0, 100.0), epsilon)
        assertEquals(1.0, VescDialGeometry.centerTextScale(-1.0, 100.0), epsilon)
    }

    private companion object {
        /** Twice `МОЩНОСТЬ`: longer than any caption any locale ships, and it does overflow. */
        const val OVERLONG_CAPTION = "МОЩНОСТЬМОЩНОСТЬ"
    }

    // =============================================================================================
    // The unit line's casing: CustomGauge.qml:123 (`Font.AllUppercase`), narrowed to Latin units
    // =============================================================================================
    //
    // On a Russian device the consumption unit is `Вт·ч/км` (correct SI case). VESC's blanket
    // upper-case rule turns it into `ВТ·Ч/КМ`, which is wrong, not merely a style choice — the
    // narrowed rule below must leave it exactly as authored while still upper-casing every Latin
    // unit the QML ever shipped (`A`, `%`, `W`, `KM/H`, `WH/KM`).

    @Test fun a_latin_unit_is_still_uppercased_exactly_as_the_QML_intends() {
        // The two discriminating cases: mixed/lower-case Latin units must come back shouting,
        // which is what would fail if the narrowing were applied backwards or too broadly.
        assertEquals("KM/H", VescDialGeometry.uppercaseUnitIfLatin("km/h"))
        assertEquals("WH/KM", VescDialGeometry.uppercaseUnitIfLatin("wh/km"))
    }

    @Test fun a_non_latin_unit_keeps_its_authored_case_instead_of_being_shouted() {
        // The regression this whole predicate exists for: the OLD code (`unitText.uppercase()`
        // unconditionally) turns this into "ВТ·Ч/КМ" and fails this assertion; only a script-aware
        // rule can leave a correctly-cased SI unit alone.
        assertEquals("Вт·ч/км", VescDialGeometry.uppercaseUnitIfLatin("Вт·ч/км"))
    }

    @Test fun a_unit_with_no_letters_at_all_passes_through_either_way() {
        // Not discriminating on its own (uppercase() is already a no-op on these strings, so the
        // old blanket rule passed them too) — kept because the task specifically calls them out as
        // cases that must not be mangled, and a future change to the predicate could still break them.
        assertEquals("°C", VescDialGeometry.uppercaseUnitIfLatin("°C"))
        assertEquals("%", VescDialGeometry.uppercaseUnitIfLatin("%"))
    }

    @Test fun the_latin_predicate_itself_is_letter_by_letter_not_string_by_string() {
        assertTrue(VescDialGeometry.isAsciiLetters("km/h"))
        assertTrue(VescDialGeometry.isAsciiLetters("%")) // no letters at all -> vacuously true
        assertTrue(VescDialGeometry.isAsciiLetters("°C")) // one Latin letter, one symbol
        assertFalse(VescDialGeometry.isAsciiLetters("Вт·ч/км")) // Cyrillic letters present
        // A single non-Latin letter anywhere in an otherwise-Latin string disqualifies the whole
        // unit, rather than only the offending character — there is no such thing as a half-shouted
        // unit on a real dial face.
        assertFalse(VescDialGeometry.isAsciiLetters("kmС/h"))
    }
}

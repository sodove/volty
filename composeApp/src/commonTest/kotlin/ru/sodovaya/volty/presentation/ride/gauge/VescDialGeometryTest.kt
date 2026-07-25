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
}

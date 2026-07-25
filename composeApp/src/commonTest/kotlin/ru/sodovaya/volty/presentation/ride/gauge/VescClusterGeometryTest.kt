package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins [VescClusterGeometry.place] against values computed BY HAND from VESC Tool's own
 * `mobile/RtDataSetup.qml` `GridLayout` (:58-537) at a concrete `g`/`g2`, so this is not testing
 * invented numbers. `g = 200.0` (Speed's own size) and `g2 = 110.0` (everything else's) are chosen
 * so every fraction below divides evenly by hand.
 *
 * The QML nests each gauge's anchor on its own PARENT gauge (Current -> Duty -> Power; Speed ->
 * Battery; ESC -> Motor -> Consumption), not on a shared container. [VescClusterGeometry] flattens
 * that chain into one absolute offset per dial (see the class doc for why); the expected numbers
 * here are chained by hand the same way, so a broken link anywhere in the implementation's own
 * chain shows up as a wrong absolute number, not just a wrong relative one.
 */
class VescClusterGeometryTest {

    private val epsilon = 1e-9
    private val g = 200.0
    private val g2 = 110.0
    private val trioHeight = 1.1 * g2 // QML :68, :431 -- 121.0
    private val topRowCenterY = trioHeight / 2.0 // 60.5
    private val bottomRowCenterY = trioHeight + g + trioHeight / 2.0 // 121 + 200 + 60.5 = 381.5

    private val boxes = VescClusterGeometry.place(g, g2)

    // --- every slot present, nothing silently dropped ---------------------------------------

    @Test fun every_slot_has_a_placement() {
        assertEquals(VescClusterSlot.entries.size, boxes.size)
        VescClusterSlot.entries.forEach { assertTrue(boxes.containsKey(it), "$it missing") }
    }

    // --- top trio: hand-chained from QML :75-76 (Current), :91 (Duty), :106-107 (Power) -----

    @Test fun current_sits_at_its_qml_offset_from_the_top_row_centre() {
        // QML :75-76: horizontalCenterOffset -0.675*g2, verticalCenterOffset +0.1*g2.
        val current = boxes.getValue(VescClusterSlot.CURRENT)
        assertEquals(-0.675 * g2, current.centerX, epsilon) // -74.25
        assertEquals(topRowCenterY + 0.1 * g2, current.centerY, epsilon) // 71.5
        assertEquals(g2, current.size, epsilon)
    }

    @Test fun duty_is_nested_on_current_shifted_right_by_135_percent_of_g2() {
        // QML :91: horizontalCenterOffset g2*1.35 relative to currentGauge (its parent), no
        // vertical offset at all -- so duty keeps current's Y exactly.
        val current = boxes.getValue(VescClusterSlot.CURRENT)
        val duty = boxes.getValue(VescClusterSlot.DUTY)
        assertEquals(current.centerX + 1.35 * g2, duty.centerX, epsilon) // 74.25
        assertEquals(current.centerY, duty.centerY, epsilon)
        assertEquals(g2, duty.size, epsilon)
    }

    @Test fun power_is_nested_on_duty_and_lands_exactly_on_the_top_row_centre() {
        // QML :106-107: offset (-0.675, -0.1)*g2 relative to dutyGauge. Chaining
        // Current(-0.675,+0.1) -> Duty(+1.35,0) -> Power(-0.675,-0.1) sums to (0, 0) relative to
        // the row centre -- Power, the biggest and last-declared (hence topmost-painted) dial of
        // the trio, sits exactly centred. If any one of the three offsets or the chain order were
        // wrong this would not land on topRowCenterY.
        val duty = boxes.getValue(VescClusterSlot.DUTY)
        val power = boxes.getValue(VescClusterSlot.POWER)
        assertEquals(duty.centerX - 0.675 * g2, power.centerX, epsilon)
        assertEquals(duty.centerY - 0.1 * g2, power.centerY, epsilon)
        assertEquals(0.0, power.centerX, epsilon)
        assertEquals(topRowCenterY, power.centerY, epsilon)
        assertEquals(1.05 * g2, power.size, epsilon) // QML :103-104
    }

    // --- middle: hand-computed from QML :134 (Speed), :306 (Battery) ------------------------

    @Test fun speed_sits_off_centre_by_a_quarter_of_its_own_size_less_g2_halved() {
        // QML :134: horizontalCenterOffset (width/4 - gaugeSize2)/2, width = speedGauge's own
        // width = g. (200/4 - 110)/2 = (50-110)/2 = -30.
        val speed = boxes.getValue(VescClusterSlot.SPEED)
        assertEquals((g / 4.0 - g2) / 2.0, speed.centerX, epsilon)
        assertEquals(-30.0, speed.centerX, epsilon)
        assertEquals(g, speed.size, epsilon)
    }

    @Test fun battery_is_nested_on_speed_offset_by_a_quarter_of_speeds_size_plus_half_its_own() {
        // QML :306: horizontalCenterOffset parent.width/4 + width/2, parent = speedGauge
        // (width = g), width = batteryGauge's own width (g2). 200/4 + 110/2 = 50+55 = 105.
        val speed = boxes.getValue(VescClusterSlot.SPEED)
        val battery = boxes.getValue(VescClusterSlot.BATTERY)
        assertEquals(speed.centerX + g / 4.0 + g2 / 2.0, battery.centerX, epsilon)
        assertEquals(75.0, battery.centerX, epsilon) // -30 + 105
        assertEquals(speed.centerY, battery.centerY, epsilon) // no vertical offset in the QML
        assertEquals(g2, battery.size, epsilon)
    }

    // --- bottom trio: same magnitudes as the top, Y sign inverted (QML :439-440, :466, :492-493) ---

    @Test fun esc_mirrors_current_with_the_vertical_sign_flipped() {
        val esc = boxes.getValue(VescClusterSlot.ESC_TEMP)
        assertEquals(-0.675 * g2, esc.centerX, epsilon) // same X as Current
        assertEquals(bottomRowCenterY - 0.1 * g2, esc.centerY, epsilon) // Current used +0.1
        assertEquals(g2, esc.size, epsilon)
    }

    @Test fun motor_is_nested_on_esc_shifted_right_exactly_like_duty_on_current() {
        val esc = boxes.getValue(VescClusterSlot.ESC_TEMP)
        val motor = boxes.getValue(VescClusterSlot.MOTOR_TEMP)
        assertEquals(esc.centerX + 1.35 * g2, motor.centerX, epsilon)
        assertEquals(esc.centerY, motor.centerY, epsilon)
        assertEquals(g2, motor.size, epsilon)
    }

    @Test fun consumption_is_nested_on_motor_and_lands_exactly_on_the_bottom_row_centre() {
        val motor = boxes.getValue(VescClusterSlot.MOTOR_TEMP)
        val consumption = boxes.getValue(VescClusterSlot.CONSUMPTION)
        assertEquals(motor.centerX - 0.675 * g2, consumption.centerX, epsilon)
        assertEquals(motor.centerY + 0.1 * g2, consumption.centerY, epsilon)
        assertEquals(0.0, consumption.centerX, epsilon)
        assertEquals(bottomRowCenterY, consumption.centerY, epsilon)
        assertEquals(1.05 * g2, consumption.size, epsilon)
    }

    // --- the actual "inverted relative to the top" claim, checked directly, independent of the ---
    // --- absolute numbers above (this is the one a sign-copy-paste bug would not slip past) -----

    @Test fun every_bottom_trio_vertical_offset_is_the_negation_of_its_top_trio_counterpart() {
        val current = boxes.getValue(VescClusterSlot.CURRENT)
        val duty = boxes.getValue(VescClusterSlot.DUTY)
        val power = boxes.getValue(VescClusterSlot.POWER)
        val esc = boxes.getValue(VescClusterSlot.ESC_TEMP)
        val motor = boxes.getValue(VescClusterSlot.MOTOR_TEMP)
        val consumption = boxes.getValue(VescClusterSlot.CONSUMPTION)

        assertEquals(current.centerY - topRowCenterY, -(esc.centerY - bottomRowCenterY), epsilon)
        assertEquals(duty.centerY - topRowCenterY, -(motor.centerY - bottomRowCenterY), epsilon)
        assertEquals(power.centerY - topRowCenterY, -(consumption.centerY - bottomRowCenterY), epsilon)

        // Not vacuous: the top trio's own per-dial vertical offsets are NOT all equal, so this
        // could not pass merely because "everything is zero" -- Current and ESC genuinely differ.
        assertTrue(current.centerY - topRowCenterY != 0.0)
        assertTrue(esc.centerY - bottomRowCenterY != 0.0)
    }

    // --- the whole point of the composition: overlap, not a scatter of separate discs ---------

    private fun overlaps(a: VescClusterBox, b: VescClusterBox): Boolean {
        val distance = hypot(a.centerX - b.centerX, a.centerY - b.centerY)
        return distance < (a.size + b.size) / 2.0
    }

    @Test fun power_overlaps_both_of_its_neighbours_in_the_top_trio() {
        // This is what merges Current/Duty/Power into one instrument instead of three floating
        // discs -- the previous (old) renderer's bug. A regression back to non-overlapping
        // fractions would flip this to false.
        val current = boxes.getValue(VescClusterSlot.CURRENT)
        val duty = boxes.getValue(VescClusterSlot.DUTY)
        val power = boxes.getValue(VescClusterSlot.POWER)
        assertTrue(overlaps(power, current), "Power must overlap Current")
        assertTrue(overlaps(power, duty), "Power must overlap Duty")
    }

    @Test fun consumption_overlaps_both_of_its_neighbours_in_the_bottom_trio() {
        val esc = boxes.getValue(VescClusterSlot.ESC_TEMP)
        val motor = boxes.getValue(VescClusterSlot.MOTOR_TEMP)
        val consumption = boxes.getValue(VescClusterSlot.CONSUMPTION)
        assertTrue(overlaps(consumption, esc), "Consumption must overlap ESC")
        assertTrue(overlaps(consumption, motor), "Consumption must overlap Motor")
    }

    @Test fun battery_overlaps_speed() {
        val speed = boxes.getValue(VescClusterSlot.SPEED)
        val battery = boxes.getValue(VescClusterSlot.BATTERY)
        assertTrue(overlaps(speed, battery), "Battery must overlap Speed")
    }

    // --- scaling: every offset is a plain multiple of g/g2, so doubling either must double ------
    // --- every dependent length exactly (a hidden additive constant would break this) -----------

    @Test fun doubling_g2_doubles_every_g2_only_slot_and_leaves_speed_alone() {
        val doubled = VescClusterGeometry.place(g, g2 * 2.0)
        val current = boxes.getValue(VescClusterSlot.CURRENT)
        val currentDoubled = doubled.getValue(VescClusterSlot.CURRENT)
        assertEquals(current.centerX * 2.0, currentDoubled.centerX, epsilon)
        assertEquals(current.size * 2.0, currentDoubled.size, epsilon)

        // Speed's own size never depends on g2 (QML :131-132: width = parent.height = g).
        val speed = boxes.getValue(VescClusterSlot.SPEED)
        val speedDoubled = doubled.getValue(VescClusterSlot.SPEED)
        assertEquals(speed.size, speedDoubled.size, epsilon)
        assertTrue(speed.centerX != speedDoubled.centerX) // but its OFFSET does depend on g2
    }

    @Test fun doubling_g_doubles_speed_but_leaves_the_top_trio_alone() {
        val doubled = VescClusterGeometry.place(g * 2.0, g2)
        val speed = boxes.getValue(VescClusterSlot.SPEED)
        val speedDoubled = doubled.getValue(VescClusterSlot.SPEED)
        assertEquals(speed.size * 2.0, speedDoubled.size, epsilon)

        val current = boxes.getValue(VescClusterSlot.CURRENT)
        val currentDoubled = doubled.getValue(VescClusterSlot.CURRENT)
        assertEquals(current.centerX, currentDoubled.centerX, epsilon)
        assertEquals(current.centerY, currentDoubled.centerY, epsilon)
        assertEquals(current.size, currentDoubled.size, epsilon)
    }

    // --- total height: two trio rows plus the hero row between them (QML :68, :125, :431) ------

    @Test fun total_height_is_two_trio_rows_plus_the_hero_row() {
        assertEquals(2.0 * 1.1 * g2 + g, VescClusterGeometry.totalHeight(g, g2), epsilon)
        assertEquals(442.0, VescClusterGeometry.totalHeight(g, g2), epsilon) // 242 + 200
    }

    // --- degenerate input is rejected rather than silently producing NaN/garbage ----------------

    @Test fun a_non_positive_g_or_g2_is_rejected() {
        assertFailsWith<IllegalArgumentException> { VescClusterGeometry.place(0.0, g2) }
        assertFailsWith<IllegalArgumentException> { VescClusterGeometry.place(g, 0.0) }
        assertFailsWith<IllegalArgumentException> { VescClusterGeometry.place(-1.0, g2) }
    }
}

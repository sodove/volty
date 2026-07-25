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

    // --- fit: turning the space available into g/g2 (QML :45-47) -------------------------------
    //
    // The defect this closes: the sizes used to be caller-supplied, so a cluster taller than its
    // box was placed at its ideal positions anyway and the container clipped the bottom trio.
    // Every assertion below is on the PLACEMENTS the fitted sizes produce, not on the sizes
    // themselves, so it fails for the real symptom (a dial outside the box) rather than for an
    // arithmetic identity.

    /** Every dial, at every plausible aspect ratio, lands fully inside the box it was fitted to. */
    @Test fun a_fitted_cluster_never_puts_a_dial_outside_its_box() {
        val boxes = listOf(
            1080.0 to 1920.0,  // phone portrait
            1080.0 to 800.0,   // squat: height binds
            720.0 to 4000.0,   // tall and narrow: width binds
            2000.0 to 1200.0,  // tablet landscape
            300.0 to 300.0     // small square
        )
        for ((w, h) in boxes) {
            val sizes = VescClusterGeometry.fit(w, h)
            val placed = VescClusterGeometry.place(sizes.g, sizes.g2)
            val extent = VescClusterGeometry.verticalExtent(sizes.g, sizes.g2)
            val centreX = w / 2.0
            val where = "${w}x$h"
            placed.forEach { (slot, box) ->
                val left = centreX + box.centerX - box.size / 2.0
                val right = centreX + box.centerX + box.size / 2.0
                // The layout shifts every dial down by the extent's own start, so that is the
                // origin these are measured against — not zero.
                val top = box.centerY - box.size / 2.0 - extent.start
                val bottom = box.centerY + box.size / 2.0 - extent.start
                assertTrue(left >= -1e-6, "$slot overflows the left edge at $where (left=$left)")
                assertTrue(right <= w + 1e-6, "$slot overflows the right edge at $where (right=$right)")
                assertTrue(top >= -1e-6, "$slot overflows the top at $where (top=$top)")
                assertTrue(bottom <= h + 1e-6, "$slot overflows the bottom at $where (bottom=$bottom)")
            }
            assertTrue(
                extent.endInclusive - extent.start <= h + 1e-6,
                "the cluster reports a height taller than the box it was fitted to at $where"
            )
        }
    }

    // --- paint order and extent: the QML's composition, with nothing hoisted out of it ----------

    /**
     * The cluster has NO emphasis concept: no dial is resized and none is re-stacked, whatever the
     * rider's "Inner gauge" setting says. That setting belongs to Clean's hero ring, which shows
     * one secondary metric and so must choose; Classic shows all eight. The emphasis that used to
     * live here drew the chosen dial at `1.12` and painted it last, which put a Duty dial in front
     * of — and bigger than — the `1.05` Power dial the QML makes its trio's hero.
     */
    @Test fun the_paint_order_is_the_qmls_own_nesting_order_and_nothing_is_brought_forward() {
        assertEquals(VescClusterSlot.entries, VescClusterGeometry.paintOrder)
        // Power and Consumption are the hero of their trio: declared last in the QML, so painted
        // last, so never partly tucked under the neighbour they overlap.
        val order = VescClusterGeometry.paintOrder
        assertTrue(order.indexOf(VescClusterSlot.POWER) > order.indexOf(VescClusterSlot.DUTY))
        assertTrue(order.indexOf(VescClusterSlot.POWER) > order.indexOf(VescClusterSlot.CURRENT))
        assertTrue(order.indexOf(VescClusterSlot.CONSUMPTION) > order.indexOf(VescClusterSlot.MOTOR_TEMP))
        assertTrue(order.indexOf(VescClusterSlot.CONSUMPTION) > order.indexOf(VescClusterSlot.ESC_TEMP))
        assertTrue(order.indexOf(VescClusterSlot.BATTERY) > order.indexOf(VescClusterSlot.SPEED))
    }

    /**
     * Power is `1.05 * g2` tall inside a row only `1.1 * g2` high, i.e. `0.025 * g2` of margin —
     * the tightest fit in the cluster and the one a size change would break first. The extent must
     * therefore be exactly `0 .. totalHeight`: nothing overhangs either end.
     */
    @Test fun no_dial_reaches_past_the_cluster_so_the_extent_is_exactly_the_total_height() {
        val extent = VescClusterGeometry.verticalExtent(g, g2)
        assertEquals(0.0, extent.start, epsilon)
        assertEquals(VescClusterGeometry.totalHeight(g, g2), extent.endInclusive, epsilon)
        // Not vacuous: Power really does come within 0.025*g2 of the top of the cluster.
        val power = boxes.getValue(VescClusterSlot.POWER)
        assertEquals(0.025 * g2, power.centerY - power.size / 2.0, epsilon)
    }

    /**
     * The cluster's normal home is a scrolling column, i.e. no height limit at all. Width alone
     * then decides — and the result must be the same as an arbitrarily tall box would give, or the
     * scrolling case would silently be a different layout from the bounded one.
     */
    @Test fun an_unbounded_height_falls_back_to_the_width_alone() {
        val unbounded = VescClusterGeometry.fit(1080.0, Double.POSITIVE_INFINITY)
        val veryTall = VescClusterGeometry.fit(1080.0, 100_000.0)
        assertEquals(veryTall.g, unbounded.g, epsilon)
        assertEquals(1080.0 / VescClusterGeometry.QML_WIDTH_DIVISOR, unbounded.g, epsilon)
        // A zero or negative height is treated the same way rather than collapsing the cluster.
        assertEquals(unbounded.g, VescClusterGeometry.fit(1080.0, 0.0).g, epsilon)
    }

    /** Whichever axis is tighter is the one that binds — checked in both directions. */
    @Test fun the_tighter_axis_is_the_one_that_binds() {
        // Squat box: the height term is far the smaller of the two here.
        val squat = VescClusterGeometry.fit(1080.0, 800.0)
        assertEquals(800.0 / VescClusterGeometry.heightPerG, squat.g, epsilon)
        assertTrue(squat.g < 1080.0 / VescClusterGeometry.QML_WIDTH_DIVISOR)
        // Tall box: the width term wins instead.
        val tall = VescClusterGeometry.fit(720.0, 4000.0)
        assertEquals(720.0 / VescClusterGeometry.QML_WIDTH_DIVISOR, tall.g, epsilon)
    }

    @Test fun fit_keeps_the_QMLs_own_ratio_between_the_two_gauge_sizes() {
        val sizes = VescClusterGeometry.fit(1080.0, 1920.0)
        assertEquals(0.55, VescClusterGeometry.G2_FRACTION, epsilon) // QML :47
        assertEquals(sizes.g * 0.55, sizes.g2, epsilon)
    }

    /** A degenerate box produces zero sizes rather than a negative one `place` would reject. */
    @Test fun a_degenerate_box_fits_to_nothing() {
        assertEquals(0.0, VescClusterGeometry.fit(0.0, 1000.0).g, epsilon)
        assertEquals(0.0, VescClusterGeometry.fit(-10.0, 1000.0).g, epsilon)
        assertEquals(0.0, VescClusterGeometry.fit(0.0, 1000.0).g2, epsilon)
    }

    /**
     * The two derived limits the fit relies on, pinned against the arithmetic they claim to
     * summarise. `2.21` is `2 * 1.1 * 0.55 + 1` (two trio rows plus the hero row); `0.65` is the
     * Speed dial's own reach (half of `g`, pushed `0.15g` left of centre), matched by Battery on
     * the right. They are DERIVED from [VescClusterGeometry.place]/`verticalExtent`, so an offset
     * change moves them — which is the point of deriving rather than transcribing them.
     *
     * These are the figures the retired Classic emphasis inflated: reserving room for a `1.12`
     * dial in every slot pushed the width limit to `1.42` and the height limit past `2.21`, so the
     * whole cluster was drawn ~4% smaller than it had to be, permanently, to leave space for a cue
     * that has since been removed. With that gone the QML's own `1.37` divisor is the one that
     * binds again, exactly as it does in VESC Tool.
     */
    @Test fun the_fits_limits_are_the_ones_the_placements_actually_need() {
        assertEquals(2.0 * 1.1 * VescClusterGeometry.G2_FRACTION + 1.0, VescClusterGeometry.heightPerG, 1e-9)
        assertEquals(2.21, VescClusterGeometry.heightPerG, 1e-9)
        assertEquals(0.15 + 0.5, VescClusterGeometry.halfWidthPerG, 1e-9)
        assertEquals(0.65, VescClusterGeometry.halfWidthPerG, 1e-9)
        // The QML's own divisor is the wider of the two, so it is what `fit` uses — and it leaves
        // the cluster a small margin rather than pinning it edge to edge.
        assertTrue(2.0 * VescClusterGeometry.halfWidthPerG < VescClusterGeometry.QML_WIDTH_DIVISOR)
        assertEquals(1.30, 2.0 * VescClusterGeometry.halfWidthPerG, 1e-9)
    }
}

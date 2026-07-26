package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MotionAlertRuleTest {

    @Test fun empty_level_list_is_the_one_and_only_way_to_say_off() {
        val off = AlertRule(MotionAlertKind.SPEED, emptyList())
        assertTrue(off.isOff)
        // A rule with a level is on even if that level is muted — muting every
        // level is NOT how an alert is turned off (F §10.2).
        val muted = AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(40f, enabled = false)))
        assertFalse(muted.isOff)
    }

    @Test fun ascending_levels_are_accepted_including_equal_neighbours() {
        AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f), AlertLevel(80f), AlertLevel(90f)))
        AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(80f)))
        AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f)))
    }

    @Test fun unsorted_levels_are_rejected_the_engine_may_never_see_them() {
        val boom = assertFailsWith<IllegalArgumentException> {
            AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(90f), AlertLevel(80f), AlertLevel(100f)))
        }
        assertTrue(boom.message.orEmpty().contains("ascend"), "unhelpful message: ${boom.message}")
    }

    @Test fun a_descent_anywhere_in_the_list_is_rejected_not_just_the_first_pair() {
        assertFailsWith<IllegalArgumentException> {
            AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(80f), AlertLevel(120f), AlertLevel(110f)))
        }
    }

    @Test fun a_fourth_level_is_rejected() {
        val boom = assertFailsWith<IllegalArgumentException> {
            AlertRule(
                MotionAlertKind.MOTOR_TEMP,
                listOf(AlertLevel(80f), AlertLevel(90f), AlertLevel(100f), AlertLevel(110f))
            )
        }
        assertTrue(boom.message.orEmpty().contains("at most 3"), "unhelpful message: ${boom.message}")
    }

    @Test fun copy_revalidates_the_guards_are_not_bypassable() {
        // data class copy() runs init. Nothing today declares a private
        // constructor + factory or an @Serializable no-arg path that would skip
        // it; this pins that, because losing it would be silent.
        val ok = AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(90f)))
        assertFailsWith<IllegalArgumentException> {
            ok.copy(levels = listOf(AlertLevel(90f), AlertLevel(80f)))
        }
        assertFailsWith<IllegalArgumentException> {
            ok.copy(levels = listOf(AlertLevel(70f), AlertLevel(80f), AlertLevel(90f), AlertLevel(100f)))
        }
        assertFailsWith<IllegalArgumentException> { AlertLevel(80f).copy(thresholdValue = Float.NaN) }
    }

    @Test fun a_nan_threshold_is_rejected_it_would_arm_an_alert_that_can_never_fire() {
        val boom = assertFailsWith<IllegalArgumentException> { AlertLevel(Float.NaN) }
        assertTrue(boom.message.orEmpty().contains("finite"), "unhelpful message: ${boom.message}")
    }

    @Test fun an_infinite_threshold_is_rejected_in_both_directions() {
        assertFailsWith<IllegalArgumentException> { AlertLevel(Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { AlertLevel(Float.NEGATIVE_INFINITY) }
    }

    @Test fun normaliser_output_is_always_ascending_for_every_input_order() {
        val permutations = listOf(
            listOf(80f, 90f, 100f), listOf(80f, 100f, 90f), listOf(90f, 80f, 100f),
            listOf(90f, 100f, 80f), listOf(100f, 80f, 90f), listOf(100f, 90f, 80f)
        )
        permutations.forEach { order ->
            val out = sortedLevels(order.map { AlertLevel(it) }).map { it.thresholdValue }
            assertEquals(listOf(80f, 90f, 100f), out, "not sorted for input $order")
        }
    }

    @Test fun normaliser_output_is_always_constructible_as_a_rule() {
        // The point of the normaliser: whatever the rider typed, AlertRule accepts
        // the result. If sortedLevels stopped sorting, this init would throw.
        val rule = AlertRule(
            MotionAlertKind.DUTY,
            sortedLevels(listOf(AlertLevel(95f), AlertLevel(70f), AlertLevel(85f)))
        )
        assertEquals(listOf(70f, 85f, 95f), rule.levels.map { it.thresholdValue })
    }

    @Test fun normaliser_never_drops_and_never_refuses() {
        val typed = listOf(
            AlertLevel(100f, enabled = false),
            AlertLevel(80f),
            AlertLevel(90f, enabled = false),
            AlertLevel(70f)
        )
        val out = sortedLevels(typed)
        assertEquals(typed.size, out.size, "normaliser dropped a level")
        assertEquals(typed.toSet(), out.toSet(), "normaliser altered a level")
    }

    @Test fun normaliser_keeps_the_enabled_flag_with_its_own_threshold() {
        val out = sortedLevels(
            listOf(AlertLevel(100f, enabled = false), AlertLevel(80f, enabled = true))
        )
        assertEquals(listOf(AlertLevel(80f, true), AlertLevel(100f, false)), out)
    }

    @Test fun defaults_match_the_spec_numbers() {
        assertEquals(
            listOf(80f, 90f),
            AlarmDefaults.rule(MotionAlertKind.DUTY).levels.map { it.thresholdValue }
        )
        assertEquals(
            listOf(90f),
            AlarmDefaults.rule(MotionAlertKind.ESC_TEMP).levels.map { it.thresholdValue }
        )
        assertEquals(
            listOf(110f),
            AlarmDefaults.rule(MotionAlertKind.MOTOR_TEMP).levels.map { it.thresholdValue }
        )
        assertTrue(AlarmDefaults.rule(MotionAlertKind.SPEED).isOff, "speed ships off")
    }

    @Test fun defaults_cover_every_kind_exactly_once() {
        assertEquals(MotionAlertKind.entries, AlarmDefaults.all().map { it.kind })
        AlarmDefaults.all().forEach { rule ->
            assertTrue(rule.levels.all { it.enabled }, "${rule.kind}: defaults ship enabled")
        }
    }
}

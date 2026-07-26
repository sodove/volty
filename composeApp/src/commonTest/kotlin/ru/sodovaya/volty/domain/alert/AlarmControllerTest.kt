package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AlarmControllerTest {

    // Three duty steps 10 pp apart, so the 3 pp release band sits strictly
    // inside each gap and "released level 3" and "engaged level 2" are
    // distinguishable readings rather than the same one.
    private val dutyThreeSteps = ArmedRules(
        listOf(
            AlertRule(
                MotionAlertKind.DUTY,
                listOf(AlertLevel(70f), AlertLevel(80f), AlertLevel(90f))
            )
        )
    )

    private fun duty(percent: Float) = ControllerData(dutyPercent = percent, isConnected = true)

    /** Feed a whole sequence and return the level after each sample. */
    private fun AlarmController.dutyLevels(vararg percents: Float): List<Int> =
        percents.map { update(duty(it), dutyThreeSteps).level }

    // ------------------------------------------------------------ escalation

    @Test fun duty_climbs_step_by_step_as_it_crosses_each_threshold() {
        val controller = AlarmController()
        assertEquals(
            listOf(0, 0, 1, 1, 2, 2, 3),
            controller.dutyLevels(0f, 69.9f, 70f, 79.9f, 80f, 89.9f, 90f),
            "each threshold engages the moment it is reached, and not before"
        )
    }

    @Test fun duty_falls_back_down_only_through_the_release_bands() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        assertEquals(3, controller.state.level, "at the top step to begin with")

        assertEquals(
            // 87 = 90 - 3 still holds step 3; 86.9 releases it, and lands on
            // step 2 because 86.9 is well past 80. Same shape one step down.
            listOf(3, 3, 2, 2, 2, 1, 1, 1, 0),
            controller.dutyLevels(89f, 87f, 86.9f, 80f, 77f, 76.9f, 71f, 67f, 66.9f),
            "a step holds until the reading is a full release band below its threshold"
        )
    }

    @Test fun the_top_step_wins_when_several_kinds_contribute() {
        val controller = AlarmController()
        val rules = ArmedRules(
            listOf(
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f), AlertLevel(80f))),
                AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(100f), AlertLevel(120f))),
                AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(50f)))
            )
        )
        val state = controller.update(
            ControllerData(
                dutyPercent = 72f,          // duty step 1
                motorTempC = 125f,          // motor step 2 — the loudest
                speedKmh = 30f,             // speed below its only step
                speedSource = SpeedSource.REPORTED,
                hasMotorTemp = true,
                isConnected = true
            ),
            rules
        )

        assertEquals(2, state.level, "the most urgent thing that is true decides the level")
        assertEquals(
            listOf(
                AlarmContributor(MotionAlertKind.MOTOR_TEMP, level = 2, value = 125f),
                AlarmContributor(MotionAlertKind.DUTY, level = 1, value = 72f)
            ),
            state.contributors,
            "both contributing kinds are listed, most urgent first; speed is silent so it is absent"
        )
    }

    /** ESC temperature and speed each need their own test: a shared one stops at the first failure. */
    private val tempAndSpeed = ArmedRules(
        listOf(
            AlertRule(MotionAlertKind.ESC_TEMP, listOf(AlertLevel(90f))),
            AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(50f)))
        )
    )

    @Test fun esc_temperature_raises_the_alarm_on_its_own() {
        val state = AlarmController().update(
            ControllerData(escTempC = 91f, speedKmh = 10f, isConnected = true),
            tempAndSpeed
        )
        assertEquals(1, state.level, "ESC temperature past its step sounds by itself")
        assertEquals(
            listOf(AlarmContributor(MotionAlertKind.ESC_TEMP, 1, 91f)),
            state.contributors,
            "and reports the reading that did it"
        )
    }

    @Test fun speed_raises_the_alarm_on_its_own() {
        val state = AlarmController().update(
            ControllerData(
                speedKmh = 55f,
                speedSource = SpeedSource.REPORTED,
                escTempC = 40f,
                isConnected = true
            ),
            tempAndSpeed
        )
        assertEquals(1, state.level, "speed past its step sounds by itself")
        assertEquals(
            listOf(AlarmContributor(MotionAlertKind.SPEED, 1, 55f)),
            state.contributors,
            "and reports the reading that did it"
        )
    }

    // ------------------------------------------------------------ hysteresis

    @Test fun a_reading_wandering_across_the_first_threshold_does_not_chatter() {
        val controller = AlarmController()
        assertEquals(
            listOf(0, 1, 1, 1, 1, 1),
            controller.dutyLevels(69f, 70f, 69.5f, 70.2f, 68.5f, 69.9f),
            "once step 1 engages it stays engaged until 67 — the reading never drops back to silence"
        )
    }

    @Test fun a_reading_wandering_across_the_boundary_between_two_levels_does_not_chatter() {
        val controller = AlarmController()
        controller.update(duty(75f), dutyThreeSteps)
        assertEquals(1, controller.state.level, "starting inside step 1's band")

        assertEquals(
            listOf(2, 2, 2, 2, 2, 2),
            controller.dutyLevels(80f, 79.8f, 80.1f, 79.5f, 80.3f, 78f),
            "the gap between step 1 and step 2 is crossed once, not once per sample"
        )
    }

    @Test fun a_reading_parked_exactly_on_a_threshold_is_stable() {
        val controller = AlarmController()
        val levels = controller.dutyLevels(80f, 80f, 80f, 80f)
        assertEquals(listOf(2, 2, 2, 2), levels, "an unchanging reading gives an unchanging level")
    }

    @Test fun each_metric_releases_over_its_own_band() {
        val rules = ArmedRules(
            listOf(
                AlertRule(MotionAlertKind.MOTOR_TEMP, listOf(AlertLevel(100f))),
                AlertRule(MotionAlertKind.SPEED, listOf(AlertLevel(50f)))
            )
        )
        fun sample(tempC: Float, speed: Float) = ControllerData(
            motorTempC = tempC,
            speedKmh = speed,
            speedSource = SpeedSource.REPORTED,
            hasMotorTemp = true,
            isConnected = true
        )

        val controller = AlarmController()
        controller.update(sample(tempC = 105f, speed = 55f), rules)
        assertEquals(2, controller.state.contributors.size, "both kinds engaged first")

        // 1 °C for temperature, 2 km/h for speed: one sample inside each band,
        // one just outside. Swap the two constants and this test fails.
        val held = controller.update(sample(tempC = 99.5f, speed = 48f), rules)
        assertEquals(
            listOf(MotionAlertKind.SPEED, MotionAlertKind.MOTOR_TEMP),
            held.contributors.map { it.kind }.sortedBy { it.ordinal },
            "99.5 °C is still within 1 °C of 100, and 48 km/h within 2 km/h of 50"
        )

        val released = controller.update(sample(tempC = 98.9f, speed = 47.9f), rules)
        assertEquals(AlarmState.SILENT, released, "a hair further and both let go")
    }

    // -------------------------------------------------------- disabled steps

    @Test fun a_disabled_middle_level_is_skipped_and_the_one_above_keeps_its_position() {
        val muted = ArmedRules(
            listOf(
                AlertRule(
                    MotionAlertKind.DUTY,
                    listOf(AlertLevel(70f), AlertLevel(80f, enabled = false), AlertLevel(90f))
                )
            )
        )

        assertEquals(
            1,
            AlarmController().update(duty(85f), muted).level,
            "past the muted second threshold the alarm stays at step 1 — the mute is not a promotion"
        )
        assertEquals(
            3,
            AlarmController().update(duty(92f), muted).level,
            "and the top step is still step 3, not step 2 shifted down"
        )
        // The unmuted contrast — the same thresholds putting a reading past the
        // second one at step 2 — is `duty_climbs_step_by_step_...`; repeating it
        // here would be an assertion no implementation of the mute could falsify.
    }

    @Test fun a_disabled_level_does_not_bound_the_urgency_ramp_of_the_step_below() {
        val muted = ArmedRules(
            listOf(
                AlertRule(
                    MotionAlertKind.DUTY,
                    listOf(AlertLevel(70f), AlertLevel(80f, enabled = false), AlertLevel(90f))
                )
            )
        )
        // Step 1 ramps 70 -> 90 (the next step that can actually escalate), so
        // 80 is halfway. Ramping 70 -> 80 would read 1.0 here and the tone would
        // sit pinned at maximum across the whole upper half of the band.
        assertEquals(
            0.5f,
            AlarmController().update(duty(80f), muted).urgency,
            0.0001f,
            "the ramp reaches towards the next step that can sound, skipping the muted one"
        )
    }

    // ----------------------------------------------------------- the urgency

    @Test fun urgency_ramps_from_zero_to_one_across_the_active_band() {
        assertEquals(
            0f,
            AlarmController().update(duty(70f), dutyThreeSteps).urgency,
            0.0001f,
            "0 at the bottom edge of step 1's band"
        )
        assertEquals(
            0.5f,
            AlarmController().update(duty(75f), dutyThreeSteps).urgency,
            0.0001f,
            "halfway across 70..80"
        )
        assertEquals(
            0.9f,
            AlarmController().update(duty(79f), dutyThreeSteps).urgency,
            0.0001f,
            "approaching 1 as the next step comes into reach"
        )
        assertEquals(
            0.25f,
            AlarmController().update(duty(82.5f), dutyThreeSteps).urgency,
            0.0001f,
            "and starts again from 0 inside step 2's band, 80..90"
        )
    }

    @Test fun the_final_band_has_no_step_above_it_so_urgency_is_one() {
        assertEquals(
            1f,
            AlarmController().update(duty(90f), dutyThreeSteps).urgency,
            0.0001f,
            "the top step is already as urgent as the alarm gets"
        )
        assertEquals(
            1f,
            AlarmController().update(duty(400f), dutyThreeSteps).urgency,
            0.0001f,
            "and stays there however far past it the reading goes"
        )
    }

    @Test fun a_step_held_below_its_own_band_by_hysteresis_ramps_at_zero() {
        val controller = AlarmController()
        controller.update(duty(85f), dutyThreeSteps)
        val held = controller.update(duty(78f), dutyThreeSteps)
        assertEquals(2, held.level, "still holding step 2 inside the release band")
        assertEquals(
            0f,
            held.urgency,
            0.0001f,
            "urgency does not go negative when a held reading sits below its band"
        )
    }

    @Test fun urgency_follows_the_kind_that_is_furthest_through_its_band() {
        val rules = ArmedRules(
            listOf(
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f), AlertLevel(80f))),
                AlertRule(MotionAlertKind.ESC_TEMP, listOf(AlertLevel(60f), AlertLevel(80f)))
            )
        )
        val state = AlarmController().update(
            ControllerData(dutyPercent = 72f, escTempC = 75f, isConnected = true),
            rules
        )
        assertEquals(1, state.level, "both kinds are at step 1")
        assertEquals(
            0.75f,
            state.urgency,
            0.0001f,
            "ESC temp is 75% through 60..80; duty is only 20% through 70..80"
        )
    }

    // --------------------------------------------------------------- silence

    /**
     * `AlertRule(DUTY, emptyList())` is `isOff`; that claim belongs to
     * `MotionAlertRuleTest` and is not restated here. What this test adds is that
     * such a rule reaching the engine — which it can, `ArmedRules` is
     * constructible — produces silence rather than an index-out-of-bounds from an
     * implementation that reaches for `levels.last()`.
     *
     * The silence assertion is a **contract statement, not a proven one**: with
     * an empty list there is no loop iteration to perturb, so no mutation of the
     * code that exists can make it false. It is kept because the crash it guards
     * against is a real alternative implementation, and deleting it would leave
     * that hole uncovered.
     */
    @Test fun a_rule_with_no_levels_never_raises_anything() {
        val off = ArmedRules(listOf(AlertRule(MotionAlertKind.DUTY, emptyList())))
        val state = AlarmController().update(duty(100f), off)
        assertEquals(AlarmState.SILENT, state, "a duty of 100% against no levels is silence")
        assertFalse(state.isSounding)
    }

    /**
     * Also a contract statement rather than a proven assertion — iterating an
     * empty rule list cannot produce a contributor, so nothing that exists can be
     * mutated to fail it. It records that the engine does **not** resolve
     * `AlarmDefaults` behind the gate: "nothing armed" means silence, not "fall
     * back to our opinion".
     */
    @Test fun no_armed_rules_at_all_never_raises_anything() {
        val state = AlarmController().update(duty(100f), ArmedRules.NONE)
        assertEquals(AlarmState.SILENT, state)
    }

    @Test fun two_rules_for_one_kind_are_folded_by_the_louder_one() {
        // Vehicle refuses a duplicated kind and armedRules cannot invent one, so
        // this only reaches the engine from a caller assembling rules by hand —
        // where quietly keeping whichever came last would drop a real alarm.
        // Both rules fire on this reading, at different steps, so first-wins and
        // last-wins give different answers and neither is the one asserted.
        val loudFirst = ArmedRules(
            listOf(
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f), AlertLevel(80f))),
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f)))
            )
        )
        assertEquals(2, AlarmController().update(duty(85f), loudFirst).level, "loud rule first")
        assertEquals(
            2,
            AlarmController().update(duty(85f), ArmedRules(loudFirst.rules.reversed())).level,
            "and the same the other way round, so it is not simply the first or the last"
        )
    }

    @Test fun a_zero_width_band_between_tied_thresholds_is_full_urgency() {
        // AlertRule allows two steps to share a threshold. Inside one rule the tie
        // is unreachable — both positions engage on the same attack, so the higher
        // wins and the ramp takes the "no step above" path. It becomes reachable
        // through a duplicated kind: the [70] rule leaves DUTY holding step 1, and
        // on the next sample the tied rule holds *its* step 1 by hysteresis, with
        // the second 80 as the upper edge of a band of zero width.
        val tied = ArmedRules(
            listOf(
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(80f), AlertLevel(80f))),
                AlertRule(MotionAlertKind.DUTY, listOf(AlertLevel(70f)))
            )
        )
        val controller = AlarmController()
        controller.update(duty(78f), tied)
        val state = controller.update(duty(78f), tied)

        assertEquals(1, state.level, "held at step 1 by the tied rule's release band")
        assertEquals(
            1f,
            state.urgency,
            0.0001f,
            "a band with nothing between its edges is already fully climbed — without the " +
                "guard this divides by zero and reads as no urgency at all"
        )
    }

    @Test fun a_reading_that_is_not_a_number_silences_rather_than_latches() {
        // AlertLevel refuses a NaN *threshold*, but nothing can refuse a NaN
        // *reading* — a decoder bug produces one. Written as `!(value < t)` the
        // comparison would treat it as past every step and hold the alarm on
        // with no reading that could ever clear it.
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        assertEquals(
            AlarmState.SILENT,
            controller.update(duty(Float.NaN), dutyThreeSteps),
            "a reading that is not a number is not a reading past a threshold"
        )
    }

    @Test fun recovery_clears_the_state_completely() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        assertEquals(3, controller.state.level, "sounding first")

        val recovered = controller.update(duty(20f), dutyThreeSteps)
        assertEquals(AlarmState.SILENT, recovered, "a healthy reading leaves nothing behind")
        assertEquals(recovered, controller.state, "and the exposed state is the value returned")
    }

    @Test fun reset_silences_a_sounding_alarm_and_forgets_the_held_step() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        controller.reset()
        assertEquals(AlarmState.SILENT, controller.state, "reset silences")

        // 88 is inside step 3's release band: had the held step survived, this
        // would come back at 3 instead of 2.
        assertEquals(
            2,
            controller.update(duty(88f), dutyThreeSteps).level,
            "and the next sample is judged on its own"
        )
    }

    // ------------------------------------------------ the disconnected sample

    @Test fun a_disconnected_sample_silences_the_alarm_whatever_it_carries() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        assertEquals(3, controller.state.level, "sounding while connected")

        val dropped = controller.update(
            // The placeholder BmsRepository.activeMotion emits while nothing is
            // connected carries zeros; a stale non-zero duty is the harder case
            // and must silence just the same.
            ControllerData(dutyPercent = 95f, isConnected = false),
            dutyThreeSteps
        )
        assertEquals(AlarmState.SILENT, dropped, "an unmeasured sample cannot hold the alarm up")
    }

    @Test fun a_disconnected_sample_clears_the_held_step_so_reconnection_starts_fresh() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        controller.update(ControllerData(), dutyThreeSteps)

        // 88 sits inside step 3's release band (87..90). Had the drop-out only
        // muted the output and kept the memory, this would resume at step 3 on a
        // reading that never justified it.
        assertEquals(
            2,
            controller.update(duty(88f), dutyThreeSteps).level,
            "the first sample after the gap is judged on itself"
        )
    }

    @Test fun a_condition_that_is_still_true_on_reconnection_re_arms_immediately() {
        val controller = AlarmController()
        controller.update(duty(95f), dutyThreeSteps)
        controller.update(ControllerData(), dutyThreeSteps)
        assertEquals(
            3,
            controller.update(duty(95f), dutyThreeSteps).level,
            "attack is immediate, so clearing the memory costs no warning time"
        )
    }
}

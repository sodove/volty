package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The stateful half of the audible alarm's reasoning: idempotence, and the rules
 * about previews that would otherwise only be discoverable by holding a phone.
 *
 * Every one of these would be a branch buried next to the `AudioTrack` calls if
 * the planner did not exist.
 */
class AlarmSignalPlannerTest {

    private fun state(level: Int, urgency: Float = 0f) = AlarmState(
        level = level,
        urgency = urgency,
        contributors = if (level == 0) emptyList()
        else listOf(AlarmContributor(MotionAlertKind.DUTY, level, 85f))
    )

    // --- Idempotence: the whole point of the class ---------------------------

    @Test
    fun the_same_level_twice_is_no_change_of_command() {
        val planner = AlarmSignalPlanner()
        assertEquals(AlarmTransition.RESTART, planner.update(state(2)))
        val started = planner.command

        repeat(5) {
            assertEquals(AlarmTransition.NONE, planner.update(state(2)), "a repeated sample restarted the alarm")
        }
        // Equality, not identity: the planner rebuilds the command value each
        // sample and compares it. That is the point — the *value* is what decides
        // whether the platform does anything, so an equal command is no work even
        // though it is a fresh object.
        assertEquals(started, planner.command, "the command changed while nothing else did")
    }

    @Test
    fun repeated_silence_stays_silent_without_re_issuing_a_stop() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        assertEquals(AlarmTransition.STOP, planner.update(state(0)))
        repeat(3) {
            assertEquals(AlarmTransition.NONE, planner.update(state(0)), "silence was stopped again")
        }
    }

    @Test
    fun a_wandering_urgency_inside_one_step_retunes_and_never_restarts() {
        val planner = AlarmSignalPlanner()
        planner.update(state(1, 0f))
        // Far enough apart to clear the urgency quantisation, so this fixture can
        // actually reach the condition it claims to test.
        val transitions = listOf(0.3f, 0.6f, 0.9f).map { planner.update(state(1, it)) }
        assertTrue(
            transitions.all { it == AlarmTransition.RETUNE },
            "a climbing urgency produced $transitions instead of retunes"
        )
    }

    @Test
    fun climbing_a_step_restarts_because_the_pattern_changed() {
        val planner = AlarmSignalPlanner()
        planner.update(state(1))
        assertEquals(AlarmTransition.RESTART, planner.update(state(2)))
        assertEquals(AlarmTransition.RESTART, planner.update(state(3)))
    }

    @Test
    fun a_fresh_planner_is_silent_and_a_first_silent_sample_changes_nothing() {
        val planner = AlarmSignalPlanner()
        assertSame(AlarmCommand.Silent, planner.command)
        assertEquals(AlarmTransition.NONE, planner.update(state(0)))
    }

    // --- The modality gate, applied to a running alarm -----------------------

    @Test
    fun the_master_switch_silences_an_alarm_that_is_already_sounding() {
        val planner = AlarmSignalPlanner()
        planner.update(state(3, 1f))
        assertTrue(planner.command is AlarmCommand.Play, "fixture is not sounding")

        assertEquals(AlarmTransition.STOP, planner.setModalities(AlarmModalities(alarmEnabled = false)))
        assertSame(AlarmCommand.Silent, planner.command)

        // And it stays silent as samples keep arriving — the gate is not a
        // one-shot that the next update undoes.
        assertEquals(AlarmTransition.NONE, planner.update(state(3, 1f)))
        assertSame(AlarmCommand.Silent, planner.command)
    }

    @Test
    fun turning_tones_off_mid_alarm_takes_effect_immediately_and_keeps_the_buzz() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        assertEquals(AlarmTransition.RESTART, planner.setModalities(AlarmModalities(toneEnabled = false)))
        val play = planner.command as AlarmCommand.Play
        assertEquals(null, play.tone, "the tone kept playing after the rider turned tones off")
        assertTrue(play.vibration != null, "the vibration was lost along with the tone")
    }

    @Test
    fun changing_music_mode_mid_alarm_restarts_with_the_new_policy() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))

        assertEquals(
            AlarmTransition.RESTART,
            planner.setModalities(AlarmModalities(musicMode = AlarmMusicMode.PLAY_OVER_MEDIA))
        )
        val play = planner.command as AlarmCommand.Play
        assertEquals(AlarmMusicMode.PLAY_OVER_MEDIA, play.musicMode)
    }

    @Test
    fun setting_the_same_modalities_again_changes_nothing() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        assertEquals(AlarmTransition.NONE, planner.setModalities(AlarmModalities.DEFAULT))
    }

    @Test
    fun a_master_switch_turned_back_on_restores_the_alarm_from_the_last_sample() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        planner.setModalities(AlarmModalities(alarmEnabled = false))
        assertEquals(AlarmTransition.RESTART, planner.setModalities(AlarmModalities.DEFAULT))
        assertEquals(2, (planner.command as AlarmCommand.Play).level)
    }

    // --- Previews ------------------------------------------------------------

    @Test
    fun a_preview_plays_the_step_and_reports_itself_as_a_preview() {
        val planner = AlarmSignalPlanner()
        assertEquals(AlarmTransition.RESTART, planner.preview(3))
        assertTrue(planner.isPreviewing)
        assertEquals(3, (planner.command as AlarmCommand.Play).level)
    }

    @Test
    fun a_preview_is_refused_outright_while_a_real_alarm_is_sounding() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        val sounding = planner.command

        assertEquals(AlarmTransition.NONE, planner.preview(1), "a preview interrupted a live alarm")
        assertSame(sounding, planner.command, "the live alarm was replaced by a preview")
        assertFalse(planner.isPreviewing)
    }

    @Test
    fun a_real_alarm_interrupts_a_preview_in_progress() {
        val planner = AlarmSignalPlanner()
        planner.preview(1)
        assertTrue(planner.isPreviewing)

        assertEquals(AlarmTransition.RESTART, planner.update(state(3, 1f)))
        assertFalse(planner.isPreviewing, "the preview survived a real alarm")
        assertEquals(3, (planner.command as AlarmCommand.Play).level)
    }

    @Test
    fun a_silent_sample_arriving_mid_preview_leaves_the_preview_alone() {
        // Samples arrive several times a second; if each one ended the preview the
        // button would appear broken.
        val planner = AlarmSignalPlanner()
        planner.preview(2)
        val previewing = planner.command
        repeat(4) {
            assertEquals(AlarmTransition.NONE, planner.update(state(0)), "a silent sample cut the preview short")
        }
        assertSame(previewing, planner.command)
        assertTrue(planner.isPreviewing)
    }

    @Test
    fun ending_a_preview_hands_the_alarm_back_to_the_live_state() {
        val planner = AlarmSignalPlanner()
        planner.preview(3)
        assertEquals(AlarmTransition.STOP, planner.endPreview())
        assertSame(AlarmCommand.Silent, planner.command)
        assertFalse(planner.isPreviewing)
    }

    @Test
    fun ending_a_preview_that_is_not_running_does_nothing() {
        val planner = AlarmSignalPlanner()
        assertEquals(AlarmTransition.NONE, planner.endPreview())
        planner.update(state(2))
        val sounding = planner.command
        assertEquals(AlarmTransition.NONE, planner.endPreview(), "endPreview stopped a live alarm")
        assertSame(sounding, planner.command)
    }

    @Test
    fun a_preview_the_gate_silences_leaves_nothing_to_end() {
        val planner = AlarmSignalPlanner(AlarmModalities(alarmEnabled = false))
        assertEquals(AlarmTransition.NONE, planner.preview(2))
        assertSame(AlarmCommand.Silent, planner.command)
        assertFalse(planner.isPreviewing, "a silent preview still claims to be previewing")
    }

    @Test
    fun turning_everything_off_mid_preview_ends_the_preview_too() {
        val planner = AlarmSignalPlanner()
        planner.preview(2)
        assertEquals(AlarmTransition.STOP, planner.setModalities(AlarmModalities(alarmEnabled = false)))
        assertFalse(planner.isPreviewing, "a silenced preview is still marked as previewing and will never be ended")
    }

    @Test
    fun a_modality_change_mid_preview_keeps_previewing_the_same_step() {
        val planner = AlarmSignalPlanner()
        planner.preview(2)
        assertEquals(AlarmTransition.RESTART, planner.setModalities(AlarmModalities(toneEnabled = false)))
        assertTrue(planner.isPreviewing)
        val play = planner.command as AlarmCommand.Play
        assertEquals(2, play.level)
        assertEquals(null, play.tone)
    }

    // --- Hard stop -----------------------------------------------------------

    @Test
    fun stop_silences_and_forgets_the_live_state() {
        val planner = AlarmSignalPlanner()
        planner.update(state(2))
        assertEquals(AlarmTransition.STOP, planner.stop())
        assertSame(AlarmCommand.Silent, planner.command)

        // Forgotten, not merely muted: a preview must now be allowed, which it
        // would not be if the planner still believed an alarm was sounding.
        assertEquals(AlarmTransition.RESTART, planner.preview(1))
    }

    @Test
    fun stop_cancels_a_preview_in_progress() {
        val planner = AlarmSignalPlanner()
        planner.preview(3)
        assertEquals(AlarmTransition.STOP, planner.stop())
        assertFalse(planner.isPreviewing)
    }

    @Test
    fun stopping_an_already_silent_planner_is_no_work() {
        assertEquals(AlarmTransition.NONE, AlarmSignalPlanner().stop())
    }
}

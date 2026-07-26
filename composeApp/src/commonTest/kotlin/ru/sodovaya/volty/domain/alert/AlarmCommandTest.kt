package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The modality gate and the transition rule — the two pure decisions that stand
 * between `AlarmController` and the speaker.
 *
 * Both live in `commonMain` precisely so they can be tested here rather than
 * inspected by ear: "master off means silence" and "the same level twice changes
 * nothing" are the kind of rule that is easy to get right once and break later
 * inside a platform file nothing can reach.
 */
class AlarmCommandTest {

    private fun state(level: Int, urgency: Float = 0f) = AlarmState(
        level = level,
        urgency = urgency,
        contributors = if (level == 0) emptyList()
        else listOf(AlarmContributor(MotionAlertKind.DUTY, level, 85f))
    )

    private val all = AlarmModalities.DEFAULT
    private val toneOnly = AlarmModalities(vibrationEnabled = false)
    private val vibrationOnly = AlarmModalities(toneEnabled = false)
    private val masterOff = AlarmModalities(alarmEnabled = false)

    // --- The prefs gate ------------------------------------------------------

    @Test
    fun the_master_switch_silences_a_sounding_alarm_whatever_the_other_two_say() {
        // Checked at a level that genuinely sounds under every other combination,
        // so the gate is what produced the silence and not a fixture that was
        // quiet anyway.
        assertTrue(alarmCommandFor(state(3, 1f), all) is AlarmCommand.Play, "fixture does not sound at all")

        assertSame(AlarmCommand.Silent, alarmCommandFor(state(3, 1f), masterOff))
        assertSame(
            AlarmCommand.Silent,
            alarmCommandFor(state(3, 1f), AlarmModalities(alarmEnabled = false, toneEnabled = true, vibrationEnabled = true))
        )
        assertSame(
            AlarmCommand.Silent,
            alarmCommandFor(state(3, 1f), AlarmModalities(alarmEnabled = false, toneEnabled = false, vibrationEnabled = false))
        )
    }

    @Test
    fun tone_off_leaves_the_vibration_and_vibration_off_leaves_the_tone() {
        val vibrationHalf = alarmCommandFor(state(2), vibrationOnly) as AlarmCommand.Play
        assertNull(vibrationHalf.tone, "tones are switched off but a tone was still commanded")
        assertNotNull(vibrationHalf.vibration, "vibration is on but nothing was commanded")

        val toneHalf = alarmCommandFor(state(2), toneOnly) as AlarmCommand.Play
        assertNotNull(toneHalf.tone, "tone is on but nothing was commanded")
        assertNull(toneHalf.vibration, "vibration is switched off but a pattern was still commanded")
    }

    @Test
    fun turning_both_modalities_off_is_silence_and_not_a_play_of_nothing() {
        // Two spellings of "make no sound" would be two things for the platform
        // layer to handle identically, and one of them would eventually be missed.
        val both = AlarmModalities(toneEnabled = false, vibrationEnabled = false)
        assertSame(AlarmCommand.Silent, alarmCommandFor(state(3, 1f), both))
    }

    @Test
    fun level_zero_is_silent_however_the_switches_are_set() {
        listOf(all, toneOnly, vibrationOnly, masterOff).forEach { modalities ->
            assertSame(AlarmCommand.Silent, alarmCommandFor(state(0), modalities), "level 0 sounded with $modalities")
        }
    }

    @Test
    fun a_negative_level_is_silent_rather_than_clamped_up_into_step_one() {
        // AlarmController cannot produce one, but "unknown" must never be louder
        // than "nothing wrong".
        assertSame(AlarmCommand.Silent, alarmCommandFor(-1, 0f, all))
    }

    @Test
    fun the_command_carries_the_level_clamped_to_the_table() {
        assertEquals(2, (alarmCommandFor(state(2), all) as AlarmCommand.Play).level)
        // Two engine levels that map to the same sound must compare equal, or the
        // platform would restart an identical tone.
        val beyond = alarmCommandFor(AlarmToneTable.STEPS + 1, 0f, all)
        val top = alarmCommandFor(AlarmToneTable.STEPS, 0f, all)
        assertEquals(top, beyond)
    }

    // --- The preview ---------------------------------------------------------

    @Test
    fun a_preview_plays_the_step_at_its_plainest_not_at_whatever_urgency_is_live() {
        // The preview teaches the step's *identity* — the thing §12 says carries
        // the signal — so it must not be bent by a band the rider is not in.
        assertEquals(alarmCommandFor(2, 0f, all), alarmPreviewCommand(2, all))
        assertTrue(alarmPreviewCommand(2, all) != alarmCommandFor(2, 1f, all))
    }

    @Test
    fun a_preview_goes_through_the_same_gate_as_a_live_alarm() {
        assertSame(AlarmCommand.Silent, alarmPreviewCommand(2, masterOff))
        assertNull((alarmPreviewCommand(2, vibrationOnly) as AlarmCommand.Play).tone)
    }

    @Test
    fun a_preview_runs_long_enough_for_two_whole_cycles_within_sane_bounds() {
        // "Two" is written out rather than taken from PREVIEW_CYCLES. Deriving the
        // expectation from the constant under test made this vacuous: dropping the
        // preview to a single cycle — which teaches the rider a pitch and no
        // rhythm, half of what F §12 says carries the signal — survived the suite.
        val requiredCycles = 2
        (1..AlarmToneTable.STEPS).forEach { level ->
            val command = alarmPreviewCommand(level, all) as AlarmCommand.Play
            val duration = alarmPreviewDurationMs(command)
            val period = maxOf(command.tone!!.periodMs, command.vibration!!.periodMs)

            assertTrue(
                duration >= PREVIEW_MIN_MS && duration <= PREVIEW_MAX_MS,
                "step $level previews for $duration ms, outside $PREVIEW_MIN_MS..$PREVIEW_MAX_MS"
            )
            // Two whole cycles, unless the ceiling cuts in first.
            assertTrue(
                duration >= minOf(period * requiredCycles, PREVIEW_MAX_MS),
                "step $level previews for $duration ms, less than $requiredCycles cycles of $period ms"
            )
        }
    }

    @Test
    fun a_vibration_only_preview_is_timed_by_the_haptic_not_by_a_tone_that_is_off() {
        // The haptic periods are the longer of the pair; timing off a missing tone
        // would cut a vibration-only preview short. Expectation spelled out rather
        // than recomputed with the implementation's own formula.
        val command = alarmPreviewCommand(1, vibrationOnly) as AlarmCommand.Play
        val vibrationPeriod = command.vibration!!.periodMs
        assertTrue(vibrationPeriod * 2 <= PREVIEW_MAX_MS, "fixture would be clamped, so it cannot show the timing")
        assertEquals(vibrationPeriod * 2, alarmPreviewDurationMs(command))
    }

    @Test
    fun a_tone_only_preview_of_the_fastest_step_is_stretched_to_the_floor() {
        // The case the floor exists for, and the only one that reaches it with the
        // shipped table: step 3's tone repeats every ~450 ms, so two cycles is
        // under a second — over before the rider is sure they pressed anything.
        // (With vibration on, the longer haptic period already clears the floor,
        // which is why this test uses tone-only rather than the default.)
        val command = alarmPreviewCommand(AlarmToneTable.STEPS, toneOnly) as AlarmCommand.Play
        assertTrue(
            command.tone!!.periodMs * PREVIEW_CYCLES < PREVIEW_MIN_MS,
            "fixture no longer falls below the floor, so it cannot test the floor"
        )
        assertEquals(PREVIEW_MIN_MS, alarmPreviewDurationMs(command))
    }

    @Test
    fun a_preview_slower_than_the_ceiling_is_cut_back_to_it() {
        // No shipped step is this slow — the ceiling is there so that adding a
        // calmer step later cannot hold the settings screen's speaker for ten
        // seconds. Driven with a synthetic command so the guard is actually
        // exercised rather than assumed.
        val slow = AlarmCommand.Play(
            level = 1,
            tone = AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 150, pulseGapMs = 0, restMs = 9_000),
            vibration = null
        )
        assertTrue(slow.tone!!.periodMs * PREVIEW_CYCLES > PREVIEW_MAX_MS, "fixture is not above the ceiling")
        assertEquals(PREVIEW_MAX_MS, alarmPreviewDurationMs(slow))
    }

    @Test
    fun there_is_nothing_to_wait_out_when_the_preview_is_silent() {
        assertEquals(0, alarmPreviewDurationMs(AlarmCommand.Silent))
    }

    // --- Transitions: what the platform actually has to do -------------------

    @Test
    fun an_identical_command_is_no_work_at_all() {
        val command = alarmCommandFor(state(2, 0.5f), all)
        assertEquals(AlarmTransition.NONE, alarmTransitionFor(command, command))
        assertEquals(AlarmTransition.NONE, alarmTransitionFor(AlarmCommand.Silent, AlarmCommand.Silent))
    }

    @Test
    fun a_changed_urgency_inside_one_step_retunes_and_does_not_restart() {
        // The one that matters: a rising duty reading produces a new command every
        // few samples, and restarting for each would chop the tone into stutters.
        val quiet = alarmCommandFor(state(1, 0f), all)
        val urgent = alarmCommandFor(state(1, 1f), all)
        assertTrue(quiet != urgent, "urgency produced no change at all — the fixture cannot test a retune")
        assertEquals(AlarmTransition.RETUNE, alarmTransitionFor(quiet, urgent))
    }

    @Test
    fun a_changed_step_restarts_because_the_pattern_itself_is_different() {
        assertEquals(
            AlarmTransition.RESTART,
            alarmTransitionFor(alarmCommandFor(state(1), all), alarmCommandFor(state(2), all))
        )
    }

    @Test
    fun a_step_change_restarts_even_when_only_the_tone_is_playing() {
        // With vibration switched off, two adjacent steps differ *only* in their
        // tone — which is shape-identical to an urgency retune, and a mutation
        // dropping the level check from the retune rule survived the whole suite
        // until this test existed.
        //
        // It matters because a retune keeps the running loop, and the loop only
        // picks up a new tone at its next burst: escalating that way would make
        // the rider wait out the *old* step's rest — up to 1.25 s at step 1 —
        // before hearing that things had got worse. Escalation must be immediate.
        val previous = alarmCommandFor(state(1), toneOnly) as AlarmCommand.Play
        val next = alarmCommandFor(state(2), toneOnly) as AlarmCommand.Play
        assertNull(previous.vibration, "fixture still has a vibration, so it cannot test the tone-only case")
        assertEquals(previous.vibration, next.vibration, "fixture differs in vibration, so the level check is moot")
        assertEquals(AlarmTransition.RESTART, alarmTransitionFor(previous, next))
    }

    @Test
    fun switching_a_modality_at_the_same_step_restarts_rather_than_retunes() {
        // A tone that was off has no running loop to retune, and a vibration
        // waveform can only change by being re-issued.
        assertEquals(
            AlarmTransition.RESTART,
            alarmTransitionFor(alarmCommandFor(state(2), all), alarmCommandFor(state(2), toneOnly))
        )
        assertEquals(
            AlarmTransition.RESTART,
            alarmTransitionFor(alarmCommandFor(state(2), all), alarmCommandFor(state(2), vibrationOnly))
        )
    }

    @Test
    fun anything_to_silence_is_a_stop_and_silence_to_anything_is_a_restart() {
        val playing = alarmCommandFor(state(3, 1f), all)
        assertEquals(AlarmTransition.STOP, alarmTransitionFor(playing, AlarmCommand.Silent))
        assertEquals(AlarmTransition.RESTART, alarmTransitionFor(AlarmCommand.Silent, playing))
    }
}

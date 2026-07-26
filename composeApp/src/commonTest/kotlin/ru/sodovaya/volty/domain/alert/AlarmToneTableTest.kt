package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F §12's obligation, made checkable.
 *
 * The sound itself cannot be unit-tested, but the claim §12 actually makes can
 * be: *the three steps are distinguishable on their own, and urgency only
 * refines*. That is a statement about numbers, and every one of these tests
 * fails if a future edit breaks it.
 *
 * What these tests deliberately do **not** do is restate the table. Asserting
 * `TONES[0].frequencyHz == 1000f` would pass forever and catch nothing but a
 * deliberate retune, which the "проверить сигнал" button exists to authorise.
 * Each test below pins a *relationship* the design depends on.
 */
class AlarmToneTableTest {

    private val steps = 1..AlarmToneTable.STEPS

    // --- §12: three steps, three signals ------------------------------------

    @Test
    fun the_three_steps_differ_in_pitch_pulse_count_and_rate_all_at_once() {
        val tones = steps.map { AlarmToneTable.toneFor(it, urgency = 0f) }

        // Three independent cues, so losing any one of them to a pocket still
        // leaves two. Each must be strictly monotone across the steps — equal
        // values on any one cue would mean two steps share it.
        val pitches = tones.map { it.frequencyHz }
        val pulses = tones.map { it.pulseCount }
        val periods = tones.map { it.periodMs }

        assertEquals(pitches.sorted(), pitches, "pitch must rise with the step: $pitches")
        assertEquals(pitches.distinct().size, pitches.size, "two steps share a pitch: $pitches")

        assertEquals(pulses.sorted(), pulses, "pulse count must rise with the step: $pulses")
        assertEquals(pulses.distinct().size, pulses.size, "two steps share a pulse count: $pulses")

        assertEquals(periods.sortedDescending(), periods, "repetition must get faster: $periods")
        assertEquals(periods.distinct().size, periods.size, "two steps repeat at the same rate: $periods")
    }

    @Test
    fun each_step_is_a_musical_interval_clear_of_the_next_not_a_ten_percent_nudge() {
        // §12's failure mode is two steps that "differ" on paper and sound alike
        // through a jacket. A minor third (~1.19x) is the floor for surviving
        // muffling; the shipped table uses roughly a fifth.
        val tones = steps.map { AlarmToneTable.toneFor(it, urgency = 0f) }
        tones.zipWithNext { lower, upper ->
            val ratio = upper.frequencyHz / lower.frequencyHz
            assertTrue(
                ratio >= 1.19f,
                "steps at ${lower.frequencyHz} Hz and ${upper.frequencyHz} Hz are only ${ratio}x apart — " +
                    "too close to tell apart through a pocket"
            )
        }
    }

    @Test
    fun every_pitch_including_the_urgency_bend_stays_in_the_band_a_pocket_passes() {
        // The band is written out here as literals **on purpose**. An earlier
        // version read it from constants in AlarmToneTable, which made the test
        // self-referential: widening the constant widened the assertion, and a
        // mutation that pushed the ceiling to 40 kHz survived the whole suite.
        //
        // 700 Hz: below this a phone speaker's own roll-off attenuates more than
        // the cloth does. 4 kHz: above this fabric absorbs quickly and the sound
        // stops localising. Both are properties of phones and jackets, not of this
        // code, so the test is the right place for them.
        val minHz = 700f
        val maxHz = 4000f
        steps.forEach { level ->
            listOf(0f, 1f).forEach { urgency ->
                val hz = AlarmToneTable.toneFor(level, urgency).frequencyHz
                assertTrue(
                    hz in minHz..maxHz,
                    "step $level at urgency $urgency is $hz Hz, outside $minHz..$maxHz Hz"
                )
            }
        }
    }

    @Test
    fun the_haptic_codes_the_same_three_steps_so_a_silenced_phone_still_says_which() {
        val vibrations = steps.map { AlarmToneTable.vibrationFor(it) }
        val pulses = vibrations.map { it.pulseCount }
        val periods = vibrations.map { it.periodMs }
        assertEquals(pulses.sorted(), pulses, "haptic pulse count must rise with the step: $pulses")
        assertEquals(pulses.distinct().size, pulses.size, "two steps buzz the same number of times: $pulses")
        assertEquals(periods.sortedDescending(), periods, "haptic must get faster: $periods")
        assertEquals(periods.distinct().size, periods.size, "two steps buzz at the same rate: $periods")
    }

    // --- The separation invariant: refinement must not erase what it refines --

    @Test
    fun a_step_at_full_urgency_never_reaches_the_next_step_s_pitch_or_rate() {
        steps.zipWithNext { lower, upper ->
            // The bent extremes of the lower step against the *plainest* form of
            // the upper one — the worst case for confusing them.
            val lowestOfUpper = AlarmToneTable.toneFor(upper, urgency = 0f)
            val highestOfLower = AlarmToneTable.toneFor(lower, urgency = 1f)

            assertTrue(
                highestOfLower.frequencyHz < lowestOfUpper.frequencyHz,
                "step $lower bends to ${highestOfLower.frequencyHz} Hz, reaching step $upper's " +
                    "${lowestOfUpper.frequencyHz} Hz — urgency has erased the step it was refining"
            )
            assertTrue(
                highestOfLower.periodMs > AlarmToneTable.toneFor(upper, urgency = 0f).periodMs,
                "step $lower at full urgency repeats every ${highestOfLower.periodMs} ms, at or past " +
                    "step $upper's ${lowestOfUpper.periodMs} ms"
            )
        }
    }

    @Test
    fun urgency_raises_the_pitch_and_shortens_the_rest_within_a_step() {
        steps.forEach { level ->
            val calm = AlarmToneTable.toneFor(level, urgency = 0f)
            val urgent = AlarmToneTable.toneFor(level, urgency = 1f)
            assertTrue(urgent.frequencyHz > calm.frequencyHz, "step $level: urgency did not raise the pitch")
            assertTrue(urgent.restMs < calm.restMs, "step $level: urgency did not shorten the rest")
            assertEquals(calm.pulseCount, urgent.pulseCount, "step $level: urgency must not change the pulse count")
        }
    }

    @Test
    fun the_rest_never_shrinks_away_so_a_burst_always_has_silence_around_it() {
        // A period that collapsed into its own burst would be a continuous tone —
        // the rhythm cue would be gone, and with it a third of §12's signal.
        steps.forEach { level ->
            val urgent = AlarmToneTable.toneFor(level, urgency = 1f)
            assertTrue(urgent.restMs > 0, "step $level at full urgency has no rest left: $urgent")
            assertTrue(urgent.periodMs > urgent.burstMs, "step $level: period does not contain its burst")
        }
    }

    // --- Urgency's own arithmetic -------------------------------------------

    @Test
    fun urgency_is_quantized_so_a_wandering_reading_does_not_wobble_the_pitch() {
        // Two readings inside the same quantisation cell must produce the very
        // same tone, or every sample would be a new command.
        //
        // Both offsets are **literals**, and that is the whole test. Deriving
        // them from URGENCY_STEPS — the constant under test — made the assertion
        // move with the mutation: at 1000 steps the "neighbour" shrank to
        // 1/4000 and the tones still matched, so `URGENCY_STEPS = 8 → 1000`
        // survived the suite. That mutation *is* the defect the constant exists
        // to prevent (pitch wobbling on sample noise) and nothing else catches
        // it. 0.03125 is a quarter of the intended 1/8 cell; 0.125 is a whole
        // one. Widening the cell has to fail here.
        val quarterCell = 0.03125f
        val wholeCell = 0.125f
        val a = AlarmToneTable.toneFor(2, urgency = 0.5f)
        val b = AlarmToneTable.toneFor(2, urgency = 0.5f + quarterCell)
        assertEquals(a, b, "readings $quarterCell apart produced different tones — the quantisation is too fine")

        val far = AlarmToneTable.toneFor(2, urgency = 0.5f + wholeCell)
        assertTrue(a != far, "a full quantisation step produced no change at all — urgency is being ignored")
    }

    @Test
    fun urgency_outside_zero_to_one_is_clamped_and_nan_reads_as_calm() {
        assertEquals(0f, AlarmToneTable.quantizeUrgency(-5f))
        assertEquals(1f, AlarmToneTable.quantizeUrgency(5f))
        // Not decorative: coerceIn passes NaN straight through (it fails both
        // comparisons), and a NaN would reach frequencyHz and render as garbage.
        assertEquals(0f, AlarmToneTable.quantizeUrgency(Float.NaN))
        assertTrue(
            AlarmToneTable.toneFor(1, Float.NaN).frequencyHz.isNaN().not(),
            "a NaN urgency produced a NaN pitch"
        )
    }

    // --- Level clamping ------------------------------------------------------

    @Test
    fun a_level_past_the_top_of_the_table_sounds_like_the_top_step_rather_than_crashing() {
        val top = AlarmToneTable.toneFor(AlarmToneTable.STEPS, urgency = 0f)
        assertEquals(top, AlarmToneTable.toneFor(AlarmToneTable.STEPS + 7, urgency = 0f))
        assertEquals(AlarmToneTable.STEPS, AlarmToneTable.clampLevel(AlarmToneTable.STEPS + 7))
        // Below the table too — alarmCommandFor keeps level 0 out, but clampLevel
        // must not index at -1 if anything else ever calls it.
        assertEquals(1, AlarmToneTable.clampLevel(0))
        assertEquals(AlarmToneTable.TONES.first(), AlarmToneTable.toneFor(-3, urgency = 0f))
    }

    @Test
    fun the_table_covers_every_level_the_rule_model_can_produce() {
        // If AlertRule ever allows a fourth level, this fails rather than
        // silently collapsing steps 3 and 4 onto one sound.
        assertEquals(AlertRule.MAX_LEVELS, AlarmToneTable.STEPS)
        assertEquals(AlarmToneTable.STEPS, AlarmToneTable.TONES.size)
        assertEquals(AlarmToneTable.STEPS, AlarmToneTable.VIBRATIONS.size)
    }

    // --- The waveform arithmetic the Vibrator is handed ----------------------

    @Test
    fun the_vibration_waveform_alternates_off_on_and_sums_to_one_whole_period() {
        steps.forEach { level ->
            val vibration = AlarmToneTable.vibrationFor(level)
            val timings = vibration.waveformMs()

            // Android's createWaveform starts with an OFF duration, so index 0
            // must be 0 for the buzz to begin immediately.
            assertEquals(0L, timings[0], "step $level: waveform does not start buzzing immediately")
            assertEquals(
                2 * vibration.pulseCount + 1,
                timings.size,
                "step $level: waveform has the wrong number of entries for ${vibration.pulseCount} pulses"
            )
            // Summing to exactly one period is what lets repeat-from-index-0 loop
            // seamlessly; an off-by-one entry would drift the rhythm.
            assertEquals(
                vibration.periodMs.toLong(),
                timings.sum(),
                "step $level: waveform sums to ${timings.sum()} ms, not one period of ${vibration.periodMs} ms"
            )
            // Odd indices are the ON durations.
            val onTotal = timings.filterIndexed { index, _ -> index % 2 == 1 }.sum()
            assertEquals(
                (vibration.pulseCount * vibration.pulseOnMs).toLong(),
                onTotal,
                "step $level: total buzz time is wrong"
            )
            assertEquals(vibration.restMs.toLong(), timings.last(), "step $level: waveform does not end on the rest")
        }
    }

    @Test
    fun a_single_pulse_waveform_has_no_inter_pulse_gap_in_it() {
        // The gap belongs *between* pulses; emitting one after a lone pulse would
        // stretch step 1's period by a gap that should not exist.
        val single = AlarmVibration(pulseCount = 1, pulseOnMs = 400, pulseGapMs = 999, restMs = 100)
        assertEquals(listOf(0L, 400L, 100L), single.waveformMs().toList())
        assertEquals(400, single.burstMs)
        assertEquals(500, single.periodMs)
    }
}

package ru.sodovaya.volty.domain.alert

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The PCM arithmetic — the part of the sound that can be silently wrong.
 *
 * A wrong frequency, a missing gap, an off-by-one pulse count and a click at
 * every pulse edge are all *audible* defects that no amount of staring at the
 * code reveals, and all four are arithmetic. What remains genuinely unverifiable
 * here is whether the result is loud enough, pleasant enough and localisable in
 * a pocket; that is what the "проверить сигнал" button is for.
 */
class AlarmToneRendererTest {

    private val rate = AlarmToneRenderer.SAMPLE_RATE

    // --- Shape ---------------------------------------------------------------

    @Test
    fun a_burst_is_exactly_as_long_as_its_pulses_and_the_gaps_between_them() {
        (1..AlarmToneTable.STEPS).forEach { level ->
            val tone = AlarmToneTable.toneFor(level, urgency = 0f)
            // Summed per term, exactly as the renderer builds it: each pulse and
            // each gap is truncated to a whole number of samples on its own, so
            // this is not the same as converting burstMs in one go.
            val expected = tone.pulseCount * AlarmToneRenderer.samplesFor(tone.pulseOnMs, rate) +
                (tone.pulseCount - 1) * AlarmToneRenderer.samplesFor(tone.pulseGapMs, rate)
            val actual = AlarmToneRenderer.burst(tone).size
            assertEquals(expected, actual, "step $level: burst is the wrong length for ${tone.pulseCount} pulses")

            // And [AlarmTone.burstMs] is an honest description of it: those
            // per-term truncations may cost a sample or two in total, never a
            // whole pulse. This is the assertion that catches an off-by-one pulse
            // count, which the exact check above would agree with.
            val described = AlarmToneRenderer.samplesFor(tone.burstMs, rate)
            assertTrue(
                abs(actual - described) <= 4,
                "step $level: burstMs claims $described samples but the burst is $actual — " +
                    "that is more than rounding, so the pulse arithmetic disagrees with itself"
            )
        }
    }

    @Test
    fun the_gap_between_pulses_is_actually_silent() {
        // Step 2 has one gap; a renderer that forgot to skip it would fill the gap
        // with tone and turn beep-beep into one long beep — losing the pulse-count
        // cue that F §12 leans on hardest.
        val tone = AlarmTone(frequencyHz = 1000f, pulseCount = 2, pulseOnMs = 100, pulseGapMs = 60, restMs = 0)
        val samples = AlarmToneRenderer.burst(tone)
        val pulse = AlarmToneRenderer.samplesFor(100, rate)
        val gap = AlarmToneRenderer.samplesFor(60, rate)

        val gapSlice = samples.copyOfRange(pulse, pulse + gap)
        assertTrue(gapSlice.all { it.toInt() == 0 }, "the gap between two pulses contains sound")
        // And the pulses either side of it do not.
        assertTrue(samples.copyOfRange(0, pulse).any { it.toInt() != 0 }, "the first pulse is silent")
        assertTrue(
            samples.copyOfRange(pulse + gap, pulse + gap + pulse).any { it.toInt() != 0 },
            "the second pulse is silent"
        )
    }

    @Test
    fun a_single_pulse_burst_has_no_trailing_gap() {
        val tone = AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 100, pulseGapMs = 999, restMs = 0)
        assertEquals(AlarmToneRenderer.samplesFor(100, rate), AlarmToneRenderer.burst(tone).size)
    }

    @Test
    fun silence_is_the_length_it_says_and_contains_nothing() {
        // Pinned against a **literal**, not against samplesFor. Comparing the two
        // reduced to `n == ShortArray(n).size`, which is true for every possible
        // implementation: `/ 1000L → / 100L` — ten times too much silence, i.e.
        // a step-3 burst rate of one every 700 ms instead of every 450 ms —
        // survived it, and this is the only test that pins the positive branch of
        // samplesFor at all. 250 ms at 44 100 Hz is 11 025 samples, by hand.
        val block = AlarmToneRenderer.silence(250, rate)
        assertEquals(44_100, rate, "fixture check: the arithmetic below is worked out at 44.1 kHz")
        assertEquals(11_025, block.size, "250 ms of silence is 11 025 samples at 44.1 kHz")
        assertEquals(11_025, AlarmToneRenderer.samplesFor(250, rate), "and samplesFor must agree with it")
        assertTrue(block.all { it.toInt() == 0 })
    }

    @Test
    fun a_zero_or_negative_duration_is_no_samples_rather_than_a_crash() {
        // A rest of 0 ms is reachable if anyone ever tunes REST_SHRINK to 1.0;
        // a negative array size would take the alarm out entirely.
        assertEquals(0, AlarmToneRenderer.samplesFor(0, rate))
        assertEquals(0, AlarmToneRenderer.samplesFor(-40, rate))
        assertEquals(0, AlarmToneRenderer.silence(-40, rate).size)
    }

    // --- Pitch ---------------------------------------------------------------

    @Test
    fun the_rendered_pitch_is_the_pitch_that_was_asked_for() {
        // Counted by sign changes: a sine at f Hz crosses zero 2f times a second,
        // so this fails if the phase step is wrong by any meaningful amount — the
        // one defect that would make every step sound like the same beep.
        listOf(1000f, 1600f, 2400f).forEach { hz ->
            val durationMs = 100
            val tone = AlarmTone(frequencyHz = hz, pulseCount = 1, pulseOnMs = durationMs, pulseGapMs = 0, restMs = 0)
            val crossings = signChanges(AlarmToneRenderer.burst(tone, rate))
            val expected = (2f * hz * durationMs / 1000f).roundToInt()
            assertTrue(
                abs(crossings - expected) <= 2,
                "$hz Hz rendered $crossings sign changes in $durationMs ms, expected about $expected"
            )
        }
    }

    @Test
    fun the_urgency_bend_reaches_the_rendered_audio_and_is_not_merely_stored() {
        val calm = AlarmToneTable.toneFor(2, urgency = 0f)
        val urgent = AlarmToneTable.toneFor(2, urgency = 1f)
        val calmCrossings = signChanges(AlarmToneRenderer.burst(calm, rate))
        val urgentCrossings = signChanges(AlarmToneRenderer.burst(urgent, rate))
        assertTrue(
            urgentCrossings > calmCrossings,
            "a fully urgent step 2 rendered $urgentCrossings sign changes against a calm $calmCrossings — " +
                "the pitch bend never reached the samples"
        )
    }

    // --- Level and edges -----------------------------------------------------

    @Test
    fun the_alarm_is_loud_enough_to_be_heard_and_quiet_enough_not_to_clip() {
        val tone = AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 100, pulseGapMs = 0, restMs = 0)
        val peak = AlarmToneRenderer.burst(tone).maxOf { abs(it.toInt()) }

        // **Literal floor, deliberately not AMPLITUDE * Short.MAX_VALUE.** An
        // earlier version derived the expectation from the constant under test, so
        // it moved with it: dropping AMPLITUDE from 0.85 to 0.60 — a 30 % quieter
        // safety alarm — survived the entire suite. How loud the alarm has to be
        // is a requirement, not a restatement of the code.
        val floor = (0.8 * Short.MAX_VALUE).roundToInt()
        assertTrue(peak >= floor, "peak sample $peak is below $floor — the alarm has been made quieter")
        assertTrue(peak < Short.MAX_VALUE.toInt(), "the tone reaches full scale and will clip into a rasp")
    }

    @Test
    fun the_renderer_actually_honours_its_own_amplitude_constant() {
        // Separate from the floor above, and killable by a different mutation:
        // this one fails if the renderer stops applying AMPLITUDE at all, which
        // the literal floor would not notice while the constant stays high.
        val tone = AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 100, pulseGapMs = 0, restMs = 0)
        val peak = AlarmToneRenderer.burst(tone).maxOf { abs(it.toInt()) }
        val expected = (AlarmToneRenderer.AMPLITUDE * Short.MAX_VALUE).roundToInt()
        assertTrue(
            abs(peak - expected) <= expected / 50,
            "peak sample $peak is not near the configured $expected"
        )
    }

    @Test
    fun every_pulse_starts_and_ends_at_zero_so_there_is_no_click() {
        // A pulse cut off mid-cycle steps the speaker cone instantaneously, which
        // is heard as a click on top of every beep — six a second at step 3.
        //
        // The **first sample** of a pulse says nothing about the fade-in: the
        // sine starts at sin(0) = 0 whatever the envelope does, so `samples[start]
        // == 0` held even with the fade-in half of pulseEnvelope deleted
        // (`edge = min(fromStart, fromEnd)` → `edge = fromEnd`). The last sample
        // is a real assertion — the sine is nowhere near a zero crossing there,
        // so only the envelope can bring it to 0 — and it stays.
        //
        // What replaces the vacuous half is a measurement of the ramp itself: over
        // the first half of the fade the envelope may not have passed halfway, so
        // the loudest sample in that window is far below the pulse's own peak.
        // Without a fade-in the very first cycle reaches full amplitude, and the
        // window spans several cycles at every step's pitch, so it is guaranteed
        // to catch one.
        val fadeSamples = AlarmToneRenderer.samplesFor(AlarmToneRenderer.FADE_MS, rate)
        (1..AlarmToneTable.STEPS).forEach { level ->
            val tone = AlarmToneTable.toneFor(level, urgency = 0f)
            val samples = AlarmToneRenderer.burst(tone)
            val pulse = AlarmToneRenderer.samplesFor(tone.pulseOnMs, rate)
            val gap = AlarmToneRenderer.samplesFor(tone.pulseGapMs, rate)
            val peak = samples.maxOf { abs(it.toInt()) }
            val half = fadeSamples / 2
            val cycle = (rate / tone.frequencyHz).toInt()
            assertTrue(
                half >= cycle,
                "fixture check: step $level's half-fade is $half samples and one cycle is $cycle — " +
                    "the window must contain a crest, or an un-faded pulse could slip through it"
            )
            repeat(tone.pulseCount) { index ->
                val start = index * (pulse + gap)
                assertEquals(0, samples[start].toInt(), "step $level pulse $index does not start at zero")
                assertEquals(0, samples[start + pulse - 1].toInt(), "step $level pulse $index ends with a click")

                val rampingIn = samples.copyOfRange(start, start + half).maxOf { abs(it.toInt()) }
                assertTrue(
                    rampingIn <= peak * 0.6,
                    "step $level pulse $index reaches $rampingIn of $peak within half a fade — " +
                        "it is switching on, not fading in, and that is the click"
                )
            }
        }
    }

    @Test
    fun a_pulse_is_mostly_flat_top_with_a_short_ramp_at_each_end() {
        // A beep, not a wobble. An earlier version of this test measured the body
        // of the pulse using its *own* idea of the fade length, so an envelope
        // that ramped across the entire pulse — audibly a swell rather than a
        // beep — sailed through it. Measuring the recovered envelope instead makes
        // the shape itself the thing under test.
        val tone = AlarmTone(frequencyHz = 1000f, pulseCount = 1, pulseOnMs = 100, pulseGapMs = 0, restMs = 0)
        val envelope = envelopeOf(AlarmToneRenderer.burst(tone), tone.frequencyHz)
        val peak = envelope.max()

        val atFullLevel = envelope.count { it >= peak * 0.95 }
        assertTrue(
            atFullLevel > envelope.size / 2,
            "only $atFullLevel of ${envelope.size} windows reach full level — " +
                "the fade covers most of the pulse, so this is a swell and not a beep"
        )
        // And there really is a ramp: the very edges must be well below the top.
        assertTrue(envelope.first() < peak * 0.5, "the pulse starts at full level — there is no fade in")
        assertTrue(envelope.last() < peak * 0.5, "the pulse ends at full level — there is no fade out")
    }

    @Test
    fun a_pulse_far_shorter_than_the_fade_still_reaches_full_volume() {
        // The point of capping the fade at a quarter of the pulse: a pulse shorter
        // than two fades would otherwise never reach full level, because the ramp
        // in and the ramp out would meet in the middle.
        //
        // The threshold has to be the *full* level, not merely "audible". At
        // `> MAX / 2` this test passed with the cap deleted outright — a 5 ms pulse
        // still limps to 0.62 of full scale — so it certified a guard it was not
        // actually exercising.
        val tone = AlarmTone(frequencyHz = 2000f, pulseCount = 1, pulseOnMs = 5, pulseGapMs = 0, restMs = 0)
        val samples = AlarmToneRenderer.burst(tone)
        assertTrue(samples.isNotEmpty(), "a 5 ms pulse rendered nothing")
        val peak = samples.maxOf { abs(it.toInt()) }
        val floor = (0.8 * Short.MAX_VALUE).roundToInt()
        assertTrue(peak >= floor, "a 5 ms pulse peaked at only $peak, below $floor — the fade ate most of the pulse")
    }

    /**
     * The amplitude envelope, recovered as the peak of each one-cycle window.
     *
     * A sine spends most of its time away from its own peak, so counting samples
     * near full scale says nothing about the envelope; one cycle is the shortest
     * window guaranteed to contain a crest.
     */
    private fun envelopeOf(samples: ShortArray, frequencyHz: Float): List<Int> {
        val window = (rate / frequencyHz).toInt().coerceAtLeast(1)
        return (0 until samples.size / window).map { index ->
            samples.copyOfRange(index * window, (index + 1) * window).maxOf { abs(it.toInt()) }
        }
    }

    /** Sign changes across the non-zero samples — a proxy for zero crossings. */
    private fun signChanges(samples: ShortArray): Int {
        var changes = 0
        var previous = 0
        samples.forEach { sample ->
            val sign = when {
                sample > 0 -> 1
                sample < 0 -> -1
                else -> return@forEach
            }
            if (previous != 0 && sign != previous) changes++
            previous = sign
        }
        return changes
    }
}

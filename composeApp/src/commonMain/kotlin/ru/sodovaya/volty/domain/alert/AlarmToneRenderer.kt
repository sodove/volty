package ru.sodovaya.volty.domain.alert

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Turns an [AlarmTone] into 16-bit mono PCM. Pure arithmetic, so the part of the
 * sound that can silently be wrong — the wrong frequency, an off-by-one pulse
 * count, a click at every pulse edge — is covered by a common test instead of
 * only by ear.
 *
 * The Android layer's whole job on top of this is to hand the array to an
 * `AudioTrack` and to write silence for [AlarmTone.restMs] afterwards.
 */
object AlarmToneRenderer {

    /** 44.1 kHz is universally supported; the tones top out near 2.7 kHz, far below Nyquist. */
    const val SAMPLE_RATE: Int = 44_100

    /** Peak amplitude as a fraction of full scale — headroom so nothing clips into a rasp. */
    const val AMPLITUDE: Float = 0.85f

    /**
     * Fade in and out at each pulse edge, milliseconds.
     *
     * A pulse that starts and stops mid-cycle steps the speaker cone
     * instantaneously, which is heard as a click on top of every beep — at step 3
     * that is six clicks a second, and it is exactly the kind of cheapness that
     * makes a rider distrust an alarm. A few milliseconds of ramp removes it
     * without softening the onset enough to matter.
     *
     * Capped at a quarter of the pulse in [pulseEnvelope] so a hypothetical very
     * short pulse fades rather than being all fade.
     */
    const val FADE_MS: Int = 4

    /**
     * One burst: [AlarmTone.pulseCount] pulses at [AlarmTone.frequencyHz],
     * separated by silent gaps. The trailing [AlarmTone.restMs] is **not**
     * included — the caller writes that as silence, in small slices, so it can
     * notice a level change without waiting out the whole rest.
     */
    fun burst(tone: AlarmTone, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val pulseSamples = samplesFor(tone.pulseOnMs, sampleRate)
        val gapSamples = samplesFor(tone.pulseGapMs, sampleRate)
        val total = tone.pulseCount * pulseSamples + (tone.pulseCount - 1).coerceAtLeast(0) * gapSamples
        val out = ShortArray(total)
        val step = 2.0 * PI * tone.frequencyHz / sampleRate
        var write = 0
        repeat(tone.pulseCount) { pulse ->
            for (i in 0 until pulseSamples) {
                val value = sin(step * i) * pulseEnvelope(i, pulseSamples, sampleRate) * AMPLITUDE
                out[write + i] = (value * Short.MAX_VALUE).roundToInt().toShort()
            }
            write += pulseSamples
            // The gap stays at the array's zero fill; skipping it is the gap.
            if (pulse < tone.pulseCount - 1) write += gapSamples
        }
        return out
    }

    /** A silent block of [ms], for writing out the rest between bursts. */
    fun silence(ms: Int, sampleRate: Int = SAMPLE_RATE): ShortArray =
        ShortArray(samplesFor(ms, sampleRate))

    /** Whole samples in [ms] at [sampleRate]. Never negative. */
    fun samplesFor(ms: Int, sampleRate: Int): Int =
        if (ms <= 0) 0 else (ms.toLong() * sampleRate / 1000L).toInt()

    /**
     * Linear ramp: 0 at both edges of the pulse, 1 in the middle. Returns 0 for
     * the first and last sample, which is what keeps a burst free of clicks.
     */
    private fun pulseEnvelope(index: Int, pulseSamples: Int, sampleRate: Int): Double {
        val fade = min(samplesFor(FADE_MS, sampleRate), pulseSamples / 4)
        if (fade <= 0) return 1.0
        val fromStart = index
        val fromEnd = pulseSamples - 1 - index
        val edge = min(fromStart, fromEnd)
        return if (edge >= fade) 1.0 else edge.toDouble() / fade
    }
}

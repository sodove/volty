package ru.sodovaya.volty.domain.voice

import ru.sodovaya.volty.domain.audio.MicProcessingSettings
import ru.sodovaya.volty.domain.audio.dbToGain

/**
 * Microphone stage in front of WebRTC's own processing: a fixed sensitivity boost followed by a
 * peak limiter. Attack is instant (no sample ever exceeds the ceiling); release is a LINEAR
 * return to unity gain at 1 / 80 ms, so a full recovery takes at most 80 ms.
 *
 * One instance owns its limiter state; build a new one when the settings change. Not thread-safe:
 * the capture thread is its only caller. [process] and [processSample] never allocate.
 */
class MicGainStage(settings: MicProcessingSettings, sampleRate: Int) {
    private val boost = dbToGain(settings.sensitivityDb).toFloat()
    private val ceiling = (dbToGain(settings.limiterCeilingDbfs) * PEAK).toFloat()
    private val releasePerSample = 1f / (RELEASE_SECONDS * sampleRate).coerceAtLeast(1f)

    /** Current limiter gain in (0, 1]; 1 = not limiting. */
    private var limit = 1f

    /** Processes one PCM16 sample; returns the processed PCM16 sample. */
    fun processSample(sample: Int): Int {
        val boosted = sample * boost
        val magnitude = if (boosted < 0f) -boosted else boosted
        // Release first, then let a loud sample pull the gain straight back down (instant attack).
        limit = if (limit + releasePerSample > 1f) 1f else limit + releasePerSample
        if (magnitude * limit > ceiling) limit = ceiling / magnitude
        val out = boosted * limit
        val rounded = if (out >= 0f) (out + 0.5f).toInt() else -((-out + 0.5f).toInt())
        return if (rounded > Short.MAX_VALUE) Short.MAX_VALUE.toInt()
        else if (rounded < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else rounded
    }

    /** In place over the first [count] samples. */
    fun process(samples: ShortArray, count: Int) {
        for (i in 0 until minOf(count, samples.size)) samples[i] = processSample(samples[i].toInt()).toShort()
    }

    private companion object {
        const val PEAK = 32767.0
        const val RELEASE_SECONDS = 0.08f
    }
}

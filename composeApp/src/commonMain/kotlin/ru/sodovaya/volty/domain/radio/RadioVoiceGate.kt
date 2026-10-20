package ru.sodovaya.volty.domain.radio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Open-mic gate. The noise floor is a running minimum of frame RMS (dBFS) that falls instantly
 * and rises 0.5 dB per second (50 frames), so steady wind adapts away while speech stays above it.
 */
class RadioVoiceGate {
    private var floorDb: Double? = null
    private var hangover = 0

    /** True = transmit this frame. */
    fun process(frame: ShortArray): Boolean {
        val rms = rmsDb(frame)
        val floor = floorDb
        val newFloor = when {
            floor == null -> rms.coerceAtLeast(MIN_FLOOR_DB)
            rms < floor -> rms.coerceAtLeast(MIN_FLOOR_DB)
            else -> minOf(rms, floor + RISE_DB_PER_FRAME)
        }
        floorDb = newFloor
        val threshold = max(newFloor + MARGIN_DB, ABSOLUTE_MIN_DB)
        if (rms >= threshold) {
            hangover = HANGOVER_FRAMES
            return true
        }
        if (hangover > 0) {
            hangover--
            return true
        }
        return false
    }

    private fun rmsDb(frame: ShortArray): Double {
        if (frame.isEmpty()) return Double.NEGATIVE_INFINITY
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        val rms = sqrt(sum / frame.size)
        return if (rms <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(rms / FULL_SCALE)
    }

    private companion object {
        const val FULL_SCALE = 32768.0
        const val MIN_FLOOR_DB = -100.0
        const val MARGIN_DB = 12.0
        const val ABSOLUTE_MIN_DB = -50.0
        const val RISE_DB_PER_FRAME = 0.5 / 50.0
        const val HANGOVER_FRAMES = 15
    }
}

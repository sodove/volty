package ru.sodovaya.volty.domain.radio

/** 20 ms of 16 kHz mono audio. */
const val RADIO_FRAME_SAMPLES = 320

/**
 * Sums PCM frames and soft-limits the result. Mix-minus is the caller's job: the hub calls [mix]
 * on every frame except the recipient's own.
 */
object RadioMixer {
    private const val KNEE = 24_576

    fun mix(frames: List<ShortArray>): ShortArray {
        val out = ShortArray(RADIO_FRAME_SAMPLES)
        for (i in 0 until RADIO_FRAME_SAMPLES) {
            var sum = 0
            for (frame in frames) if (i < frame.size) sum += frame[i]
            out[i] = limit(sum).toShort()
        }
        return out
    }

    private fun limit(x: Int): Int {
        val limited = when {
            x > KNEE -> KNEE + (x - KNEE) / 4
            x < -KNEE -> -KNEE + (x + KNEE) / 4
            else -> x
        }
        return limited.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
    }
}

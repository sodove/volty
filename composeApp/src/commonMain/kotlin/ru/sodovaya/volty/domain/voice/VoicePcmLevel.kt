package ru.sodovaya.volty.domain.voice

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class VoicePcmLevel(val rms: Int, val peak: Int)

/** Numeric level only; callers never retain or export the sampled PCM. */
fun voicePcm16Level(bytes: ByteArray, count: Int = bytes.size): VoicePcmLevel {
    require(count in 0..bytes.size && count % 2 == 0)
    if (count == 0) return VoicePcmLevel(0, 0)
    var squared = 0.0
    var peak = 0
    for (offset in 0 until count step 2) {
        val sample = ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
        squared += sample.toDouble() * sample
        peak = maxOf(peak, abs(sample))
    }
    return VoicePcmLevel(sqrt(squared / (count / 2)).roundToInt(), peak)
}

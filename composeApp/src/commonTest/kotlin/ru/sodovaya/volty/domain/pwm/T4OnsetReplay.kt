package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.domain.model.ControllerData

/** One new decode of the replayed dump: the motion the real [BegodeProtocol] published, and when the notification arrived. */
data class ReplayedSample(val atMillis: Long, val motion: ControllerData)

/**
 * Feeds [T4OnsetDump] through the real decoder exactly as the connection session does: every notification goes to
 * `onNotification`, and a sample is taken whenever `latestMotion` is a NEW instance (the MotionSampleGate rule).
 * So the samples include the in-between ones a cycle's 0x00, 0x01, 0x04 and 0x07 frames each produce.
 */
object T4OnsetReplay {
    fun samples(cellCount: Int = 24): List<ReplayedSample> {
        // The decoder's alarm "first set" time is the dump's own clock, not the wall clock of a fast replay.
        var at = 0L
        val protocol = BegodeProtocol(cellCount = cellCount, nowMillis = { at })
        var last: ControllerData? = null
        val out = mutableListOf<ReplayedSample>()
        for (line in T4OnsetDump.lines) {
            val parts = line.trim().split(' ')
            at = parts[0].toLong()
            protocol.onNotification(ByteArray(parts.size - 1) { parts[it + 1].toInt(16).toByte() })
            val m = protocol.latestMotion(0) ?: continue
            if (m === last) continue
            last = m
            out += ReplayedSample(at, m)
        }
        return out
    }
}

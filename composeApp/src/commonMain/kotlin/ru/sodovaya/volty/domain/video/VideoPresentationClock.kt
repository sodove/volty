package ru.sodovaya.volty.domain.video

/** Changing the live buffer depth must never play telemetry backwards. No prediction is performed. */
class VideoPresentationClock {
    private var previous: Long? = null
    fun frameMillis(nowMillis: Long, delayMillis: Long): Long {
        val delay = delayMillis.coerceIn(0, 300)
        val target = if (nowMillis < Long.MIN_VALUE + delay) Long.MIN_VALUE else nowMillis - delay
        return maxOf(previous ?: target, target).also { previous = it }
    }
    fun reset() { previous = null }
}

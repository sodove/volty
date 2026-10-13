package ru.sodovaya.volty.domain.history

import kotlin.math.abs

private const val MAX_CLOCK_SKEW_MILLIS = 1000.0

fun clockBoundary(previous: HistoryTick, next: HistoryTick): Boolean {
    if (previous.clockGeneration != next.clockGeneration || next.epochMillis < previous.epochMillis ||
        next.elapsedMillis < previous.elapsedMillis) return true
    // Subtract as Double to avoid overflow for malformed/legacy epoch values.
    val wallDelta = next.epochMillis.toDouble() - previous.epochMillis.toDouble()
    val elapsedDelta = next.elapsedMillis.toDouble() - previous.elapsedMillis.toDouble()
    return abs(wallDelta - elapsedDelta) > MAX_CLOCK_SKEW_MILLIS
}

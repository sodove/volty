package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistoryRange
import kotlin.math.roundToLong

/** Relative arithmetic preserves millisecond endpoints even near Long limits.
 * Positive pan follows a finger to the right, revealing earlier recorded time.
 * Unrepresentable clock spans and stale bounds fail closed.
 */
fun historyTransformWindow(window: HistoryRange, bounds: HistoryRange, zoom: Double = 1.0,
    anchor: Double = 0.5, pan: Double = 0.0, minimumMillis: Long = 1000): HistoryRange? {
    if (!zoom.isFinite() || zoom <= 0 || !anchor.isFinite() || !pan.isFinite() || minimumMillis <= 0) return null
    if (window.startEpochMillis < bounds.startEpochMillis || window.endEpochMillis > bounds.endEpochMillis) return null
    val duration = bounds.endEpochMillis - bounds.startEpochMillis
    if (duration < 0) return null
    if (duration == 0L || (zoom == 1.0 && pan == 0.0)) return window
    val span = window.endEpochMillis - window.startEpochMillis
    val minimum = minOf(minimumMillis, duration)
    val nextSpan = (span.toDouble() / zoom).coerceIn(minimum.toDouble(), duration.toDouble())
        .roundToLong().coerceIn(minimum, duration)
    val maxOffset = duration - nextSpan
    val offset = (window.startEpochMillis - bounds.startEpochMillis).toDouble() +
        (span - nextSpan).toDouble() * anchor.coerceIn(0.0, 1.0) - pan * span.toDouble()
    val start = bounds.startEpochMillis + offset.coerceIn(0.0, maxOffset.toDouble()).roundToLong().coerceIn(0, maxOffset)
    return HistoryRange(start, start + nextSpan)
}

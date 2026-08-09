package ru.sodovaya.volty.presentation.graph

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A safe vertical range for one plotted signal. */
data class PlotRange(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/** All timestamps on the shared time axis, de-duplicated and ordered. */
@OptIn(ExperimentalTime::class)
fun mergedPlotTimeline(series: List<GraphSeries>): List<Instant> =
    series.asSequence()
        .flatMap { it.points.asSequence() }
        .map { it.timestamp }
        .distinct()
        .sorted()
        .toList()

/**
 * Finds a range that keeps a constant signal drawable while preserving all
 * measured values. The range is intentionally per-series: volts, amps and °C
 * must not be forced onto one misleading numeric axis.
 */
fun plotRange(points: List<GraphPoint>, minimumSpan: Float = 0f): PlotRange? {
    if (points.isEmpty()) return null
    val rawMin = points.minOf { it.value }
    val rawMax = points.maxOf { it.value }
    val span = maxOf(rawMax - rawMin, minimumSpan, 0.001f)
    val center = (rawMin + rawMax) / 2f
    val half = span / 2f
    return PlotRange(center - half, center + half)
}

fun plotFraction(value: Float, range: PlotRange): Float {
    if (range.span <= 0f) return 0.5f
    return ((value - range.min) / range.span).coerceIn(0f, 1f)
}

package ru.sodovaya.volty.presentation.graph

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A safe vertical range for one plotted signal. */
data class PlotRange(val min: Float, val max: Float) {
    val span: Float get() = max - min
}

/** The measured extrema used by the signal table. */
data class PlotExtrema(val min: Float, val max: Float)

/** All timestamps on the shared time axis, de-duplicated and ordered. */
@OptIn(ExperimentalTime::class)
fun mergedPlotTimeline(series: List<GraphSeries>): List<Instant> =
    series.asSequence()
        .flatMap { it.points.asSequence() }
        .map { it.timestamp }
        .distinct()
        .sorted()
        .toList()

/** Returns stable labels for the shared time axis without interpolating fake samples. */
@OptIn(ExperimentalTime::class)
fun timelineTicks(timeline: List<Instant>): List<Instant> {
    if (timeline.isEmpty()) return emptyList()
    if (timeline.size < 3) return timeline.distinct()
    return listOf(timeline.first(), timeline[timeline.size / 2], timeline.last()).distinct()
}

/** Selects the real sample timestamp nearest to a tap on the shared axis. */
@OptIn(ExperimentalTime::class)
fun nearestTimelineTimestamp(timeline: List<Instant>, fraction: Float): Instant? {
    if (timeline.isEmpty()) return null
    val axisPoints = timeline.map { GraphPoint(it, 0f) }
    val target = timestampAtFraction(axisPoints, fraction) ?: return null
    return nearestPoint(axisPoints, target)?.timestamp
}

fun plotExtrema(points: List<GraphPoint>): PlotExtrema? {
    if (points.isEmpty()) return null
    return PlotExtrema(
        min = points.minOf { it.value },
        max = points.maxOf { it.value }
    )
}

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

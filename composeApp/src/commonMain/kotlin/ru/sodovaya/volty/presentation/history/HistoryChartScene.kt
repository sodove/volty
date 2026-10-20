package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

data class HistoryChartPoint(val epochMillis: Long, val value: Double, val x: Float, val y: Float)
data class HistoryChartScene(val segments: List<List<HistoryChartPoint>>, val minimum: Double?,
    val maximum: Double?, val truncated: Boolean)

/** Projects a bounded recorded overview, never the source of exact statistics.
 * Acquisition gaps were inserted BEFORE thinning. Sparse retained timestamps
 * must not invent further cadence gaps, interpolated values or viewport tails.
 */
fun historyChartScene(series: HistorySeries, range: HistoryRange): HistoryChartScene? {
    val descriptor = HistoryMetricRegistry.descriptor(series.key.metricId)
        ?.takeIf { it.indexedByCell == (series.key.cellIndex != null) } ?: return null
    fun axis(tick: HistoryTick): Long? = series.origin?.let { historyAxisEpochMillis(it, tick) }
        ?: tick.epochMillis.takeIf { series.origin == null }
    val times = series.samples.map { sample ->
        if (sample.key != series.key) return null
        val time = axis(sample.tick) ?: return null
        if (time !in range.startEpochMillis..range.endEpochMillis) return null
        time
    }
    if (times.zipWithNext().any { (a, b) -> b < a }) return null
    val boundaries = series.markers.filter { it.kind != HistoryMarkerKind.GPS_GAP &&
        (it.sourceId == null || it.sourceId == series.key.sourceId) }
        .map { marker -> axis(marker.tick) ?: return null }.sorted()
    val segments = mutableListOf<List<Pair<Long, Double>>>()
    var current = mutableListOf<Pair<Long, Double>>()
    fun flush() { if (current.isNotEmpty()) segments += current.toList(); current = mutableListOf() }
    var boundaryIndex = 0
    for (index in times.indices) {
        val time = times[index]
        // Retraction: cursor-value ambiguity is not a line-break policy. An
        // overview GAP separator can precede the first real post-gap sample at
        // exactly the same tick. Preserve saved order and every earned endpoint.
        val reading = series.samples[index].value
        if (reading == null) { flush(); continue }
        val previous = current.lastOrNull()?.first
        if (previous != null) {
            // Same strict previous boundary as the raw continuity index: a
            // marker at an earned restart point must not split its NEXT edge.
            while (boundaryIndex < boundaries.size && boundaries[boundaryIndex] <= previous) boundaryIndex++
            if (boundaryIndex < boundaries.size && boundaries[boundaryIndex] <= time) flush()
        }
        val value = reading * descriptor.displayMultiplier
        if (!value.isFinite()) return null
        current += time to if (value == 0.0) 0.0 else value
    }
    flush()
    val low = segments.minOfOrNull { segment -> segment.minOf { it.second } }
    val high = segments.maxOfOrNull { segment -> segment.maxOf { it.second } }
    fun x(time: Long): Float {
        if (range.startEpochMillis == range.endEpochMillis || time == range.startEpochMillis) return 0f
        if (time == range.endEpochMillis) return 1f
        val span = range.endEpochMillis - range.startEpochMillis
        val fraction = if (span >= 0) (time - range.startEpochMillis).toDouble() / span.toDouble()
            else (time.toDouble() - range.startEpochMillis.toDouble()) /
                (range.endEpochMillis.toDouble() - range.startEpochMillis.toDouble())
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }
    fun y(value: Double): Float {
        val min = checkNotNull(low); val max = checkNotNull(high)
        if (min == max) return 0.5f
        val span = max - min
        val fraction = if (span.isFinite()) (max - value) / span
            else (max / 2 - value / 2) / (max / 2 - min / 2)
        return fraction.coerceIn(0.0, 1.0).toFloat()
    }
    return HistoryChartScene(segments.map { segment -> segment.map { (time, value) ->
        HistoryChartPoint(time, value, x(time), y(value))
    } }, low, high, series.truncated)
}

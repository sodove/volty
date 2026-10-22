package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

const val HISTORY_MAP_LOW_ARGB: Long = 0xFF39B9FF
const val HISTORY_MAP_HIGH_ARGB: Long = 0xFFD16AFF
const val HISTORY_MAP_NEUTRAL_ARGB: Long = 0xFF94A8B8
const val HISTORY_MAP_MAX_COLOR_STROKES = 4096

data class HistoryMapColorStroke(val start: HistoryGpsFix, val end: HistoryGpsFix, val fraction: Double)
data class HistoryMapColoring(val strokes: List<HistoryMapColorStroke>, val minimum: Double,
    val maximum: Double, val partial: Boolean)
data class HistoryMapMetricEvidence(val series: HistorySeries, val stats: HistoryStats)

fun historyMapMetricEvidence(detail: HistoryDetailState?, key: HistorySeriesKey?): HistoryMapMetricEvidence? {
    if (detail == null || key == null || detail.loading || detail.error != null || detail.range == null ||
        detail.ride == null || detail.ride.id != detail.rideId || key !in detail.selectedKeys ||
        key in detail.loadingKeys || key in detail.metricErrors) return null
    val source = detail.sources.singleOrNull { it.id == key.sourceId } ?: return null
    val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: return null
    if (source.kind !in descriptor.sourceKinds || descriptor.indexedByCell != (key.cellIndex != null)) return null
    val series = detail.series[key]?.takeIf { it.key == key } ?: return null
    return HistoryMapMetricEvidence(series, detail.stats[key] ?: return null)
}

/** Bounded earned-value overlay. The entire neutral recorded route remains underneath. */
fun historyMapColoring(scene: HistoryMapScene, origin: HistoryTick?, series: HistorySeries?, stats: HistoryStats?,
    maxAgeMillis: Long, maxStrokes: Int = HISTORY_MAP_MAX_COLOR_STROKES): HistoryMapColoring? {
    require(maxAgeMillis >= 0 && maxStrokes in 1..HISTORY_MAP_MAX_COLOR_STROKES)
    if (series == null || series.origin != origin) return null
    val descriptor = HistoryMetricRegistry.descriptor(series.key.metricId)
        ?.takeIf { it.indexedByCell == (series.key.cellIndex != null) } ?: return null
    val summary = historyMetricSummary(series.key, stats)
    val low = summary.minimum.value ?: return null
    val high = summary.maximum.value ?: return null
    val span = high - low
    if (!span.isFinite() || span < 0) return null
    val index = HistoryMapValueIndex.create(series) ?: return null
    fun axis(tick: HistoryTick) = origin?.let { historyAxisEpochMillis(it, tick) } ?: tick.epochMillis.takeIf { origin == null }
    val strokes = mutableListOf<HistoryMapColorStroke>()
    var capped = false
    outer@ for (segment in scene.segments) for (i in 1 until segment.fixes.size) {
        val start = segment.fixes[i - 1]; val end = segment.fixes[i]
        val from = axis(start.tick) ?: continue; val to = axis(end.tick) ?: continue
        if (to < from || index.hasUnknown(from, to) || index.hasBoundary(from, to)) continue
        val value = index.valueAt(from, maxAgeMillis)?.times(descriptor.displayMultiplier) ?: continue
        val endValue = index.valueAt(to, maxAgeMillis)?.times(descriptor.displayMultiplier) ?: continue
        if (!value.isFinite() || !endValue.isFinite() || value !in low..high || endValue !in low..high) continue
        if (strokes.size == maxStrokes) { capped = true; break@outer }
        val fraction = if (span == 0.0) 0.5 else ((value - low) / span).coerceIn(0.0, 1.0)
        // The actual start value colors this edge. Neither an average value nor
        // a new GPS/sample point is manufactured between its recorded endpoints.
        strokes += HistoryMapColorStroke(start, end, fraction)
    }
    return HistoryMapColoring(strokes, low, high, capped || series.truncated || stats?.complete != true)
}

/** Build once per bound series, then O(log n) lookups per GPS edge. This does not
 * call the scanning cursor policy for each of up to160000 recorded GPS fixes.
 */
private class HistoryMapValueIndex(private val times: List<Long>, private val values: List<Double?>,
    private val unknowns: IntArray, private val boundaries: List<Long>, private val connected: List<Long>, private val truncated: Boolean) {
    fun valueAt(time: Long, maxAge: Long): Double? {
        val index = upper(times, time) - 1
        if (index < 0) return null
        val value = values[index] ?: return null
        val at = times[index]; val age = time - at
        if (age < 0 || age > maxAge || (truncated && time > times.last())) return null
        if (age > 0 && hasBoundary(at, time)) return null
        return value
    }
    fun hasUnknown(from: Long, to: Long): Boolean = unknowns[upper(times, to)] > unknowns[upper(times, from)]
    fun hasBoundary(from: Long, to: Long): Boolean =
        upper(boundaries, to) > lower(boundaries, from) || upper(connected, to) > upper(connected, from)
    companion object {
        fun create(series: HistorySeries): HistoryMapValueIndex? {
            val times = mutableListOf<Long>(); val values = mutableListOf<Double?>()
            fun axis(tick: HistoryTick): Long? = series.origin?.let { historyAxisEpochMillis(it, tick) }
                ?: tick.epochMillis.takeIf { series.origin == null }
            for (sample in series.samples) {
                if (sample.key != series.key) return null
                val time = axis(sample.tick) ?: return null
                val previous = times.lastOrNull()
                if (previous != null && time < previous) return null
                if (previous == time) values[values.lastIndex] =
                    if (values.last() == null || sample.value == null) null else sample.value
                else { times += time; values += sample.value }
            }
            val unknowns = IntArray(values.size + 1)
            values.forEachIndexed { i, value -> unknowns[i + 1] = unknowns[i] + if (value == null) 1 else 0 }
            val boundaries = mutableListOf<Long>(); val connected = mutableListOf<Long>()
            for (marker in series.markers) {
                if (marker.kind == HistoryMarkerKind.GPS_GAP ||
                    (marker.sourceId != null && marker.sourceId != series.key.sourceId)) continue
                val time = axis(marker.tick) ?: return null
                if (marker.kind == HistoryMarkerKind.CONNECTED) connected += time else boundaries += time
            }
            return HistoryMapValueIndex(times, values, unknowns, boundaries.sorted(), connected.sorted(), series.truncated)
        }
        private fun upper(times: List<Long>, value: Long): Int {
            var low = 0; var high = times.size
            while (low < high) { val middle = (low + high) ushr 1
                if (times[middle] <= value) low = middle + 1 else high = middle }
            return low
        }
        private fun lower(times: List<Long>, value: Long): Int {
            var low = 0; var high = times.size
            while (low < high) { val middle = (low + high) ushr 1
                if (times[middle] < value) low = middle + 1 else high = middle }
            return low
        }
    }
}

package ru.sodovaya.volty.domain.history

import kotlin.math.abs

internal fun requireSingleHistoryKey(samples: List<HistorySample>) {
    val key = samples.firstOrNull()?.key ?: return
    require(samples.all { it.key == key }) { "A history projection must contain one source/metric/cell key" }
}

/** GPS loss does not erase earned BLE telemetry; route continuity handles GPS markers separately. */
internal class HistoryContinuityIndex(markers: List<HistoryMarker>, private val maxIntervalMillis: Long = 2000) {
    init { require(maxIntervalMillis > 0) }
    private val boundaries = markers.filter { it.kind != HistoryMarkerKind.GPS_GAP }
        .groupBy { it.tick.clockGeneration }.mapValues { (_, rows) -> rows.sortedBy { it.tick.elapsedMillis } }
    fun breakTick(previous: HistorySample, next: HistorySample): HistoryTick? {
        if (previous.unknown != null || next.unknown != null) return next.tick
        if (clockBoundary(previous.tick, next.tick)) return next.tick
        val delta = next.tick.elapsedMillis - previous.tick.elapsedMillis
        if (delta > maxIntervalMillis) return next.tick
        val rows = boundaries[next.tick.clockGeneration] ?: return null
        var low = 0
        var high = rows.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (rows[mid].tick.elapsedMillis <= previous.tick.elapsedMillis) low = mid + 1 else high = mid
        }
        return rows.getOrNull(low)?.tick?.takeIf { it.elapsedMillis <= next.tick.elapsedMillis }
    }
}

fun nearestHistorySample(samples: List<HistorySample>, tick: HistoryTick, maxAgeMillis: Long): HistorySample? {
    require(maxAgeMillis >= 0)
    requireSingleHistoryKey(samples)
    val nearest = samples.asSequence().filter { it.tick.clockGeneration == tick.clockGeneration }
        .minWithOrNull(compareBy<HistorySample> { abs(it.tick.elapsedMillis - tick.elapsedMillis) }
            .thenBy { if (it.unknown != null) 0 else 1 }.thenBy { it.tick.elapsedMillis })
        ?: return null
    if (nearest.unknown != null || abs(nearest.tick.elapsedMillis - tick.elapsedMillis) > maxAgeMillis) return null
    return nearest
}

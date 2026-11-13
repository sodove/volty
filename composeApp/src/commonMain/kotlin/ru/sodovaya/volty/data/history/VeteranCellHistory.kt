package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.VeteranCellSweep

/** Read-time recovery for older Leaperkim rides that retained the individual cell pages. */
internal class VeteranCellHistory(private val store: HistoryStore, private val dispatcher: CoroutineDispatcher) {
    private data class Query(val rideId: String, val sourceId: String, val range: HistoryRange)
    private data class Point(val tick: HistoryTick, val summary: VeteranCellSweep.Summary?)
    private val mutex = Mutex()
    private val cache = LinkedHashMap<Query, List<Point>>()

    suspend fun recover(ride: HistoryRide, key: HistorySeriesKey, range: HistoryRange,
        markers: List<HistoryMarker>): List<HistorySample> {
        if (!appliesTo(ride, key)) return emptyList()
        val query = Query(ride.id, key.sourceId, range)
        val points = mutex.withLock {
            cache[query] ?: withContext(dispatcher) { readPoints(ride, query, markers) }.also {
                if (cache.size >= CACHE_SIZE) cache.remove(cache.keys.first())
                cache[query] = it
            }
        }
        return points.map { point ->
            val value = point.summary?.let {
                when (key.metricId) {
                    "cell_min_v" -> it.minimum
                    "cell_max_v" -> it.maximum
                    else -> it.deltaMv
                }
            }
            HistorySample(point.tick, key, value, if (value == null) HistoryUnknownReason.PARTIAL_SOURCE else null)
        }
    }

    private suspend fun readPoints(ride: HistoryRide, query: Query, markers: List<HistoryMarker>): List<Point> {
        val keys = store.recordedKeys(ride.id, query.sourceId)
        val cells = keys.keys.filter { it.metricId == "cell_v" }.sortedBy { it.cellIndex }
        // Older rides carry no declared cell count. Recover only a dense set of the sizes
        // observed on these multi-page wheels, and expose the result as partial history.
        if (keys.truncated || cells.size !in setOf(30, 36, 42) ||
            cells.withIndex().any { (index, key) -> index != key.cellIndex }) return emptyList()
        val range = query.range
        val start = range.startEpochMillis
        val lookback = (start.coerceAtLeast(Long.MIN_VALUE + HISTORY_MAX_INTERVAL_MILLIS) -
            HISTORY_MAX_INTERVAL_MILLIS).coerceAtLeast(ride.started.epochMillis)
        if (lookback > range.endEpochMillis) return emptyList()
        val readRange = HistoryRange(lookback, range.endEpochMillis)
        val pages = HashMap<HistoryTick, RecordedPage>()
        var rowCount = 0
        cells.forEachIndexed { index, cell ->
            var after: HistoryCursor? = null
            do {
                currentCoroutineContext().ensureActive()
                val page = store.samples(ride.id, cell, readRange, after)
                rowCount += page.items.size
                if (rowCount > MAX_ROWS || (page.next != null && page.next == after) ||
                    (page.truncated && page.next == null)) return emptyList()
                page.items.forEach { pages.getOrPut(it.tick, ::RecordedPage).add(index, it.value) }
                after = page.next
            } while (after != null)
        }
        val boundaries = (if (lookback < start) store.markers(ride.id, HistoryRange(lookback, start)) + markers
            else markers).filter { it.kind != HistoryMarkerKind.GPS_GAP && (it.sourceId == null || it.sourceId == query.sourceId) }
            .sortedBy { it.tick.elapsedMillis }
        var boundary = 0
        val sweep = VeteranCellSweep()
        return buildList {
            for ((tick, page) in pages.entries.sortedBy { it.key.elapsedMillis }) {
                currentCoroutineContext().ensureActive()
                while (boundary < boundaries.size && boundaries[boundary].tick.elapsedMillis <= tick.elapsedMillis) {
                    sweep.reset()
                    boundary++
                }
                val result = sweep.offer(page.asSweepPage(), cells.size, tick.elapsedMillis)
                val axis = historyAxisEpochMillis(ride.started, tick) ?: continue
                if (axis in start..range.endEpochMillis && result != null) {
                    add(Point(tick, (result as? VeteranCellSweep.Result.Complete)?.summary))
                }
            }
        }
    }

    private class RecordedPage {
        private var indices = 0L
        private var invalid = false
        private var minimum = Double.POSITIVE_INFINITY
        private var maximum = Double.NEGATIVE_INFINITY

        fun add(index: Int, value: Double?) {
            val bit = 1L shl index
            if (indices and bit != 0L || value == null || value !in 1.5..5.0) invalid = true
            indices = indices or bit
            if (value != null) {
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
            }
        }

        fun asSweepPage(): VeteranCellSweep.Page {
            val first = indices.countTrailingZeroBits()
            val count = indices.countOneBits()
            val contiguous = indices ushr first == (1L shl count) - 1
            return if (contiguous && !invalid) VeteranCellSweep.Page(first, count, minimum, maximum)
            else VeteranCellSweep.Page(first, count, null, null)
        }
    }

    companion object {
        private const val CACHE_SIZE = 6
        private const val MAX_ROWS = 400_000
        private val veteranPack = Regex("native-history-v1:17:(?:[0-9A-F]{2}:){5}[0-9A-F]{2}6:VETERAN7:BATTERY1:[01]11:CONTRIBUTOR0:0:")
        fun appliesTo(ride: HistoryRide, key: HistorySeriesKey): Boolean =
            ride.status != HistoryRideStatus.RECORDING && key.cellIndex == null &&
                key.metricId in setOf("cell_min_v", "cell_max_v", "cell_delta_mv") && veteranPack.matches(key.sourceId)
    }
}

package ru.sodovaya.volty.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.sodovaya.volty.domain.history.*

/** V2 read path only; legacy recording remains on its existing compatibility path. */
class DefaultHistoryReadRepository(
    private val store: HistoryStore,
    private val maxReadPages: Int = 8,
) : HistoryReadRepository {
    init { require(maxReadPages in 1..64) }

    override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
        require(limit in 1..PAGE_SIZE)
        val empty = HistoryRecordedKeys(emptyList(), false)
        if (ride(rideId) == null) return empty
        val source = read { store.sources(rideId) }.singleOrNull { it.id == sourceId } ?: return empty
        val result = read { store.recordedKeys(rideId, sourceId, limit) }
        check(result.keys.size <= limit && result.keys.distinct().size == result.keys.size) { "Invalid recorded key bound/duplicates" }
        for (key in result.keys) {
            val metric = HistoryMetricRegistry.descriptor(key.metricId)
            check(key.sourceId == sourceId && metric != null && source.kind in metric.sourceKinds &&
                metric.indexedByCell == (key.cellIndex != null)) { "Unqualified recorded key" }
        }
        return result.copy(keys = result.keys.toList())
    }

    override suspend fun rides(before: HistoryCursor?): HistoryPage<HistoryRide> = read { store.rides(before, 20) }
    override suspend fun ride(rideId: String): HistoryRide? = read { store.ride(rideId) }
    override suspend fun sources(rideId: String): List<HistorySource> =
        if (ride(rideId) == null) emptyList() else read { store.sources(rideId) }.toList()

    override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int): HistorySeries {
        require(maxPoints in 3..600)
        val origin = ride(rideId)?.started ?: return emptySeries(key, null)
        if (!available(rideId, key)) return emptySeries(key, origin)
        val allMarkers = read { store.markers(rideId, range) }.toList()
        check(allMarkers.size <= PAGE_SIZE) { "History marker read exceeded its bound" }
        val markers = allMarkers.filter { it.sourceId == null || it.sourceId == key.sourceId }
        val overview = HistoryOverviewAccumulator(markers, maxPoints)
        var after: HistoryCursor? = null
        var previousAxis: Long? = null
        repeat(maxReadPages) { pageIndex ->
            val page = read { store.samples(rideId, key, range, after, PAGE_SIZE) }
            previousAxis = checkSamplePage(origin, key, range, after, PAGE_SIZE, page, previousAxis)
            val next = page.next
            overview.offerPage(page.items)
            if (next == null || pageIndex == maxReadPages - 1) {
                return HistorySeries(key, overview.snapshot(), markers, page.truncated || next != null, origin)
            }
            after = next
        }
        error("Unreachable history read budget")
    }

    override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int):
        HistoryPage<HistorySample>? {
        require(limit in 1..PAGE_SIZE)
        val empty = HistoryPage<HistorySample>(emptyList(), null, false)
        val origin = ride(rideId)?.started ?: return empty
        if (!available(rideId, key)) return empty
        val page = read { store.samples(rideId, key, range, after, limit) }
        checkSamplePage(origin, key, range, after, limit, page, null)
        return page.copy(items = page.items.toList())
    }

    override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryPage<HistoryGpsFix> =
        if (ride(rideId) == null) HistoryPage(emptyList(), null, false)
        else read { store.gps(rideId, range, after, PAGE_SIZE) }

    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats =
        if (ride(rideId) == null || !available(rideId, key)) HistoryStats(null, null, null, false)
        else read { store.stats(rideId, key, range) }

    override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?): HistoryRoutePage {
        val snapshot = read { store.route(rideId, range, after, PAGE_SIZE) }
        check(snapshot.fixes.items.size <= PAGE_SIZE && snapshot.markers.size <= PAGE_SIZE) { "Route snapshot exceeds read bounds" }
        check(snapshot.origin != null || (snapshot.fixes.items.isEmpty() && snapshot.markers.isEmpty() && snapshot.fixes.next == null))
        return snapshot.copy(fixes = snapshot.fixes.copy(items = snapshot.fixes.items.toList()), markers = snapshot.markers.toList())
    }

    private suspend fun available(rideId: String, key: HistorySeriesKey): Boolean {
        val descriptor = HistoryMetricRegistry.descriptor(key.metricId) ?: return false
        if (descriptor.indexedByCell != (key.cellIndex != null)) return false
        val source = read { store.sources(rideId) }.singleOrNull { it.id == key.sourceId } ?: return false
        return source.kind in descriptor.sourceKinds
    }

    private fun emptySeries(key: HistorySeriesKey, origin: HistoryTick?) =
        HistorySeries(key, emptyList(), emptyList(), false, origin)

    /** The checks every sample page passes, whether it feeds an overview or a whole-ride walk. Returns the last axis seen. */
    private fun checkSamplePage(origin: HistoryTick, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?,
        limit: Int, page: HistoryPage<HistorySample>, previous: Long?): Long? {
        check(page.items.size <= limit) { "History sample read exceeded its bound" }
        var previousAxis = previous
        for (sample in page.items) {
            check(sample.key == key) { "History sample belongs to another series" }
            val axis = checkNotNull(historyAxisEpochMillis(origin, sample.tick)) { "History sample has another clock origin" }
            check(axis in range.startEpochMillis..range.endEpochMillis &&
                (previousAxis == null || axis >= checkNotNull(previousAxis))) { "History sample order/range is invalid" }
            previousAxis = axis
        }
        val next = page.next
        if (next != null) {
            check(page.items.isNotEmpty() && next.epochMillis == previousAxis) { "History cursor does not end its page" }
            checkCursorProgress(after, next)
        }
        return previousAxis
    }

    private fun checkCursorProgress(after: HistoryCursor?, next: HistoryCursor) {
        val sequence = next.stableId.toLongOrNull()
        check(sequence != null && sequence >= 0) { "History cursor is not a stored sequence" }
        if (after != null) {
            check(next.epochMillis > after.epochMillis ||
                (next.epochMillis == after.epochMillis && sequence > checkNotNull(after.stableId.toLongOrNull()))) {
                "History cursor did not advance"
            }
        }
    }

    /** Some stores finish an in-flight read despite cancellation. Do not consume/publish it. */
    private suspend fun <T> read(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = block()
        currentCoroutineContext().ensureActive()
        return result
    }

    private companion object { const val PAGE_SIZE = HISTORY_DETAIL_PAGE_SIZE }
}

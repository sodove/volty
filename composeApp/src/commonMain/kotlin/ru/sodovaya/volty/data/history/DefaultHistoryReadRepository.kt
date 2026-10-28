package ru.sodovaya.volty.data.history

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.history.*

/** V2 read path only; legacy recording remains on its existing compatibility path. */
class DefaultHistoryReadRepository(
    private val store: HistoryStore,
    private val maxReadPages: Int = 8,
    /** Where the multi-page energy repair computes (final review I1); the stores do their own IO. */
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Pages of detail the unknown-or-gap walk may read per series; beyond it the series is partial, never guessed. */
    private val maxWalkPages: Int = 10,
) : HistoryReadRepository {
    init { require(maxReadPages in 1..64); require(maxWalkPages in 1..64) }

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

    override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
        val ride = ride(rideId)
        if (ride == null || !available(rideId, key)) return HistoryStats(null, null, null, false)
        var stored = read { store.stats(rideId, key, range) }
        if (stored.pendingWalk) {
            val (complete, gap) = settledCompleteness(ride, key, range)
            stored = stored.copy(complete = complete, hasGap = gap, pendingWalk = false)
        }
        if (stored.energyWh != null || ride.status == HistoryRideStatus.RECORDING ||
            !bridgesPartialSourceArtefact(key.metricId)) return stored
        val energy = repairedEnergy(rideId, key, range) ?: return stored
        // Approximate by construction (thinned detail), so never `complete`: the tile renders it as partial.
        return stored.copy(energyWh = energy, complete = false)
    }

    /** Cheapest honest "has a known value": the store's stats (a maximum query), never the unknown-or-gap walk. */
    override suspend fun hasKnownValue(rideId: String, key: HistorySeriesKey, range: HistoryRange): Boolean {
        if (ride(rideId) == null || !available(rideId, key)) return false
        return read { store.stats(rideId, key, range) }.maximum != null
    }

    private val walkCache = HashMap<Triple<String, HistorySeriesKey, HistoryRange>, Pair<Boolean, Boolean>>()

    /**
     * Settles a series the store's cheap proof could not call exact (review I1): pages its detail with ordinary short
     * reads (never inside the store's read transaction, which the live recorder's append must not wait on), measures its
     * unknown-or-gap time ([HistoryBrokenAccumulator]) against [historyBrokenIsPartial], and stops as soon as the verdict
     * is certain. Returns (complete, hasGap). Cached per (ride, key, range) for a finished ride only; the rules are the
     * energy repair's: a definitive answer is cached, a cancelled caller propagates, a failed read is neither cached nor
     * guessed at (partial). Reading more than [maxWalkPages] pages is partial, not a guess.
     */
    private suspend fun settledCompleteness(ride: HistoryRide, key: HistorySeriesKey, range: HistoryRange): Pair<Boolean, Boolean> {
        val cacheKey = Triple(ride.id, key, range)
        val cacheable = ride.status != HistoryRideStatus.RECORDING
        if (cacheable) repairMutex.withLock { walkCache[cacheKey]?.let { return it } }
        val answer = try { walk(ride, key, range) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { println("[VOLTY] history completeness walk failed: $e"); return false to false }
        if (cacheable) repairMutex.withLock {
            if (walkCache.size >= REPAIR_CACHE_SIZE) walkCache.clear()
            walkCache[cacheKey] = answer
        }
        return answer
    }

    private suspend fun walk(ride: HistoryRide, key: HistorySeriesKey, range: HistoryRange): Pair<Boolean, Boolean> {
        val origin = ride.started
        fun elapsedAt(axis: Long) = origin.elapsedMillis + (axis - origin.epochMillis)
        val startElapsed = elapsedAt(maxOf(range.startEpochMillis, origin.epochMillis))
        val endElapsed = elapsedAt(range.endEpochMillis)
        // The ride's end, not the series': see HistoryBrokenAccumulator.partial.
        val rideEnd = minOf(ride.ended?.elapsedMillis ?: Long.MAX_VALUE, endElapsed)
        val markers = read { store.markers(ride.id, range) }.toList()
            .filter { it.sourceId == null || it.sourceId == key.sourceId }
        val broken = HistoryBrokenAccumulator(markers)
        var after: HistoryCursor? = null
        var pages = 0
        while (true) {
            val page = read { store.samples(ride.id, key, range, after, PAGE_SIZE) }
            for (sample in page.items) broken.offer(sample)
            if (broken.surelyPartial(endElapsed - startElapsed)) return false to true
            after = page.next ?: break
            if (++pages >= maxWalkPages) return false to false
        }
        val partial = broken.partial(rideEnd) ?: return false to false
        return !partial to (broken.gapMillis > 0)
    }

    private val repairMutex = Mutex()
    private val repairCache = HashMap<Triple<String, HistorySeriesKey, HistoryRange>, Double?>()

    /**
     * Energy of a finished ride recorded BEFORE the Begode 0x00 fix (Task 6), where the stored buckets hold none.
     *
     * Why here and not in the SQL reader: the store runs `stats` inside one read transaction, and the live recorder's
     * append must never wait on a multi-page recompute; this layer pages through ordinary short reads instead. The
     * trigger is narrow on purpose: the stored energy is null (a ride whose buckets carry energy keeps its exact
     * stored Wh) AND the retained detail holds PARTIAL_SOURCE unknowns (the proven artefact; a ride with only
     * genuine unknowns is not repaired). A ride still recording is never repaired.
     *
     * Integrated from the retained detail (first/min/max/last per 500 ms window), with the artefact bridged
     * ([HistoryPartialSourceBridge]): an approximation, which is why the caller forces `complete = false`. Cached per
     * (ride, key, range), including a negative answer: a finished ride's detail is immutable, so the paging happens
     * once however often the totals or the detail screen ask. The cache is small and cleared wholesale when full.
     * Reading more than [REPAIR_MAX_PAGES] pages is not guessed at (null).
     */
    private suspend fun repairedEnergy(rideId: String, key: HistorySeriesKey, range: HistoryRange): Double? {
        val cacheKey = Triple(rideId, key, range)
        repairMutex.withLock { if (repairCache.containsKey(cacheKey)) return repairCache[cacheKey] }
        // Only a definitive answer is cached. A failed read (or a cancelled caller, which must propagate) leaves the
        // ride to be computed on the next ask: an earlier revision cached that null, so a screen cancelled mid-read
        // lost the ride's energy until the process restarted.
        val computed = try {
            withContext(computeDispatcher) { computeRepairedEnergy(rideId, key, range) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[VOLTY] history energy repair failed: $e")
            return null
        }
        repairMutex.withLock {
            if (repairCache.size >= REPAIR_CACHE_SIZE) repairCache.clear()
            repairCache[cacheKey] = computed
        }
        return computed
    }

    private suspend fun computeRepairedEnergy(rideId: String, key: HistorySeriesKey, range: HistoryRange): Double? {
        val multiplier = HistoryMetricRegistry.descriptor(key.metricId)?.consumptionMultiplier ?: return null
        val rows = ArrayList<HistorySample>()
        var after: HistoryCursor? = null
        var pages = 0
        while (true) {
            val page = read { store.samples(rideId, key, range, after, PAGE_SIZE) }
            rows += page.items
            after = page.next ?: break
            if (++pages >= REPAIR_MAX_PAGES) return null
        }
        if (rows.none { it.unknown == HistoryUnknownReason.PARTIAL_SOURCE }) return null
        val markers = read { store.markers(rideId, range) }.toList()
            .filter { it.sourceId == null || it.sourceId == key.sourceId }
        return historyStats(bridgePartialSourceArtefact(rows), markers, multiplier).energyWh
    }

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

    private companion object {
        const val PAGE_SIZE = HISTORY_DETAIL_PAGE_SIZE
        const val REPAIR_MAX_PAGES = 50
        const val REPAIR_CACHE_SIZE = 64
    }
}

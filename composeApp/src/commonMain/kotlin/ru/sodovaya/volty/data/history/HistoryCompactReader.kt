package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.HistorySampleV3
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.*

/**
 * The `HistoryStore` readers of a compact (V3) ride (compact-history-storage plan, Task 6). [HistoryStoreReader]
 * dispatches here when the ride has a `HistoryRideKeyV3`, and reads V2 otherwise. Every answer is the one V2 gives for
 * the same recording, read through [expandForReaders]: a run reaches a reader as exactly its `count` members.
 *
 * ## A range that cuts a run (Ruling 4)
 * Every reader expands FIRST and clips SECOND: a run that starts before the range or ends after it contributes the
 * members of its expansion that lie in the range, never its stored end and never the range bound. For an evenly spaced
 * run that is exactly V2's answer; for an uneven one the in-range members are interpolated, so the first or last of
 * them differs from V2 by less than one inter-sample step at each cut edge. A whole-ride read cuts nothing and is exact.
 *
 * An earlier revision of the plan (Task 6 brief) clamped a run that ends after `to` to `to` itself in SQL
 * (`MIN(runEndElapsed, :to)`) in `stats` and in the other-sources probe. That was wrong: no sample lies at `to`, so
 * the clamp invents a `lastElapsed` V2 never reports, while expand-then-clip reports the member V2 does report (exactly,
 * for an even run). Both readers below therefore clip by expansion.
 */
internal class HistoryCompactReader(database: VoltyDatabase) {
    private val queries = database.rideHistoryCompactQueries

    fun rideNo(rideId: String): Long? = queries.rideNoV3(rideId).executeAsOneOrNull()

    private fun seriesId(rideNo: Long, key: HistorySeriesKey): Long? =
        queries.seriesForV3(rideNo, key.sourceId, key.metricId, key.cellIndex?.toLong() ?: -1L).executeAsOneOrNull()

    private fun members(row: HistorySampleV3, key: HistorySeriesKey, origin: HistoryTick) =
        HistoryRunMembers(HistoryCompactCodec.decode(row, key, origin))

    /**
     * One page of a series in `(elapsed, sequence)` order: `HistoryCursor.stableId` is the member's sequence.
     *
     * ## A page may end inside a run (deviation from the brief, stated)
     * The brief said a run is never split across pages and the cursor after a run is its `(runEndElapsed,
     * runEndSequence)`. That was written when a run expanded to TWO samples. Since Ruling 2 a run expands to `count`
     * samples (2,752 for the T4's dead series), more than a page of 1..20000 can hold, so a page must be able to end on
     * an interior member. The cursor of an interior member is its tick and its interpolated sequence
     * ([HistoryRunMembers.sequence]); `(elapsed, sequence)` strictly increases along the run, so the next page resumes
     * at the next member: it finds the row at or before the cursor (one backwards seek, `latestAtOrBeforeKeyV3`), and if
     * that row is a run reaching past the cursor, emits its remaining members first, then pages the rows after it. No
     * row of the same series lies inside a run (the writer refuses one), so nothing is skipped or repeated, and the
     * concatenation of pages equals one whole read at every page size. A run with non-distinct sequences (corrupt; the
     * writer cannot produce one) fails loudly instead of yielding a cursor that does not advance.
     *
     * Re-reading a RECORDING ride between pages while its open run grows moves the interpolated interior members; the
     * pager then resumes at the first member after the cursor of the longer run, never repeating or going back. V2's
     * pages of a live ride also change between reads.
     */
    fun samples(rideNo: Long, origin: HistoryTick, key: HistorySeriesKey, bounds: LongRange, cursor: Pair<Long, Long>?,
        limit: Int, cursorOf: (HistoryTick, Long) -> HistoryCursor): HistoryPage<HistorySample> {
        val id = seriesId(rideNo, key) ?: return HistoryPage(emptyList(), null, false)
        val items = ArrayList<HistorySample>()
        var last: Pair<HistoryTick, Long>? = null
        var reachedEnd = false
        // Emits members of one row from [start]; false once a member lies past the range (nothing later can be in it).
        fun emit(m: HistoryRunMembers, start: Int): Boolean {
            check(m.hasDistinctSequences) { "A stored unknown run has fewer sequences than members" }
            for (index in start until m.size) {
                if (items.size > limit) return true
                val tick = m.tick(index)
                if (tick.elapsedMillis > bounds.last) { reachedEnd = true; return false }
                items += m.sample(index)
                if (items.size <= limit) last = tick to m.sequence(index)
            }
            return true
        }
        val carry = if (cursor == null) queries.latestBeforeV3(id, bounds.first).executeAsOneOrNull()
            else queries.latestAtOrBeforeKeyV3(id, cursor.first, cursor.second).executeAsOneOrNull()
        if (carry?.runCount != null) {
            val m = members(carry, key, origin)
            val start = m.firstIndex { index ->
                val elapsed = m.elapsed(index)
                elapsed >= bounds.first && (cursor == null || elapsed > cursor.first ||
                    (elapsed == cursor.first && m.sequence(index) > cursor.second))
            }
            emit(m, start)
        }
        if (!reachedEnd && items.size <= limit) {
            val low = maxOf(bounds.first, cursor?.first ?: Long.MIN_VALUE)
            val rows = queries.pageSamplesV3(id, low, bounds.last, cursor?.first, cursor?.second ?: 0,
                (limit + 1 - items.size).toLong()).executeAsList()
            for (row in rows) if (!emit(members(row, key, origin), 0) || items.size > limit) break
        }
        val more = items.size > limit
        val page = if (more) items.subList(0, limit).toList() else items
        return HistoryPage(page, if (more) checkNotNull(last).let { (tick, sequence) -> cursorOf(tick, sequence) } else null, more)
    }

    data class Detail(val minimum: Double?, val maximum: Double?, val unknownCount: Long, val lastElapsed: Long?)

    /**
     * V2's `rangeDetailStats` over the expanded series: extrema of the known rows in range, the number of unknown
     * MEMBERS in range (callers compare it with zero only, but it is exact), and the last in-range member's elapsed.
     * SQL reduces the rows that start in range; the run that starts before `from` and the run that ends after `to` (at
     * most one each, possibly the same) are clipped by expansion here (class KDoc).
     */
    fun detail(rideNo: Long, origin: HistoryTick, key: HistorySeriesKey, bounds: LongRange): Detail? {
        val id = seriesId(rideNo, key) ?: return null
        val sql = queries.rangeDetailStatsV3(id, bounds.first, bounds.last).executeAsOne()
        var unknown = sql.unknownCount
        var lastElapsed = sql.lastElapsed
        // A run ending after `to` is the last row starting at or before it; it is in the SQL rows when it starts in range.
        queries.latestAtOrBeforeV3(id, bounds.last).executeAsOneOrNull()
            ?.takeIf { it.runCount != null && it.elapsedMillis >= bounds.first && checkNotNull(it.runEndElapsed) > bounds.last }
            ?.let { row ->
                val m = members(row, key, origin)
                val inRange = m.lastIndexAtOrBefore(bounds.last) + 1
                unknown += inRange - m.size
                // Every other row in range ends before this run starts, so its last in-range member is the maximum.
                lastElapsed = m.elapsed(inRange - 1)
            }
        // A run starting before `from` that reaches into the range; not among the SQL rows.
        queries.latestBeforeV3(id, bounds.first).executeAsOneOrNull()
            ?.takeIf { it.runCount != null && checkNotNull(it.runEndElapsed) >= bounds.first }
            ?.let { row ->
                val m = members(row, key, origin)
                val first = m.firstIndex { m.elapsed(it) >= bounds.first }
                val end = m.lastIndexAtOrBefore(bounds.last)
                if (end >= first) {
                    unknown += end - first + 1
                    if (lastElapsed == null) lastElapsed = m.elapsed(end)
                }
            }
        return Detail(sql.minimum, sql.maximum, unknown, lastElapsed)
    }

    /**
     * The newest member of any OTHER source's series at or before [toElapsed], EXACTLY: one backwards seek per series,
     * a run ending after [toElapsed] clipped by expansion.
     *
     * Retraction: V2 (`lastElapsedOfOtherSources`, RideHistorySample.sq) took the maximum among the last 200 rows BY
     * SEQUENCE, an approximation chosen because it was cheap there and because V3 has no sequence index to repeat it on.
     * That window could miss an out-of-order row (a source whose late sample was committed early, buried under 200 newer
     * rows of other series). This answer is therefore >= V2's, and equal whenever the true maximum lay in those 200 rows.
     * The Task 9 fingerprint diff shows every ride equal or lists the rides where V2's window missed.
     */
    fun lastElapsedOfOtherSources(rideNo: Long, origin: HistoryTick, exceptSourceId: String, toElapsed: Long): Long? {
        var best: Long? = null
        for (series in queries.seriesOfOtherSourcesV3(rideNo, exceptSourceId).executeAsList()) {
            val row = queries.latestAtOrBeforeV3(series.seriesId, toElapsed).executeAsOneOrNull() ?: continue
            val end = row.runEndElapsed
            val elapsed = if (end == null || end <= toElapsed) end ?: row.elapsedMillis else {
                val key = HistorySeriesKey(series.sourceId, series.metricId, series.cellIndex.takeIf { it >= 0 }?.toInt())
                val m = members(row, key, origin)
                m.elapsed(m.lastIndexAtOrBefore(toElapsed))
            }
            if (best == null || elapsed > best) best = elapsed
        }
        return best
    }
}

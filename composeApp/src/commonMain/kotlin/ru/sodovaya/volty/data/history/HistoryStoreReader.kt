package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.HistoryBucketV2
import ru.sodovaya.volty.domain.history.*

internal class HistoryStoreReader(private val database: VoltyDatabase) {
    fun buckets(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryBucket> {
        require(limit in 1..20000)
        val origin = origin(rideId) ?: return emptyPage()
        val bounds = bounds(origin, range) ?: return emptyPage()
        val anchor = after?.let {
            require(it.epochMillis >= origin.epochMillis)
            val slot = it.stableId.toLongOrNull()
            require(slot != null && slot >= 0 && slot <= (Long.MAX_VALUE - origin.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS)
            val start = origin.elapsedMillis + slot * HISTORY_SUMMARY_BUCKET_MILLIS
            require(elapsed(origin, it.epochMillis) == start)
            start
        }
        val rows = database.rideHistoryBucketQueries.pageBuckets(rideId, key.sourceId, key.metricId,
            key.cellIndex?.toLong() ?: -1, bounds.first, bounds.last, anchor, limit.toLong() + 1,
            ::HistoryBucketV2).executeAsList()
        return page(rows, limit, { it.toHistoryBucket(origin) }, {
            HistoryCursor(checkNotNull(historyAxisEpochMillis(origin,
                HistoryTick(origin.epochMillis, it.startElapsedMillis, origin.clockGeneration))),
                ((it.startElapsedMillis - origin.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS).toString())
        })
    }

    fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange): HistoryStats {
        val unknown = HistoryStats(null, null, null, false)
        val origin = origin(rideId) ?: return unknown
        val bounds = bounds(origin, range) ?: return unknown
        val summary = database.rideHistoryBucketQueries.rangeStats(bounds.first, bounds.last,
            rideId, key.sourceId, key.metricId, key.cellIndex?.toLong() ?: -1).executeAsOne()
        if (summary.bucketCount == 0L) return unknown
        val detail = samples(rideId, key, range, null, 20000)
        val values = detail.items.mapNotNull { it.value }
        val minimum = listOfNotNull(summary.minimum, values.minOrNull()).minOrNull()
        val maximum = listOfNotNull(summary.maximum, values.maxOrNull()).maxOrNull()
        val energy = summary.energyWh?.takeIf { it.isFinite() }
        val gap = database.rideHistoryMarkerQueries.hasEvidenceGap(rideId, bounds.first, bounds.last).executeAsOne()
        return HistoryStats(minimum, maximum, energy, summary.complete == 1L && !gap &&
            !detail.truncated && detail.items.none { it.value == null } &&
            (summary.energyWh == null || energy != null))
    }

    fun rides(before: HistoryCursor?, limit: Int): HistoryPage<HistoryRide> {
        require(limit in 1..20)
        val rows = database.rideHistoryV2Queries.pageRides(before?.epochMillis,
            before?.stableId.orEmpty(), limit.toLong() + 1).executeAsList()
        return page(rows, limit, { it.toHistoryRide() }, { HistoryCursor(it.startedEpochMillis, it.id) })
    }

    fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> {
        require(limit in 1..20000)
        val origin = origin(rideId) ?: return emptyPage()
        val bounds = bounds(origin, range) ?: return emptyPage()
        val cursor = cursor(origin, after)
        val rows = database.rideHistorySampleQueries.pageSamples(rideId, key.sourceId, key.metricId,
            key.cellIndex?.toLong() ?: -1, bounds.first, bounds.last, cursor?.first,
            cursor?.second ?: 0, limit.toLong() + 1).executeAsList()
        return page(rows, limit, {
            HistorySample(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration), key,
                it.reading, it.unknownReason?.let(HistoryUnknownReason::valueOf))
        }, { HistoryCursor(checkNotNull(historyAxisEpochMillis(origin,
            HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration))), it.sequence.toString()) })
    }

    fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?, limit: Int): HistoryPage<HistoryGpsFix> {
        require(limit in 1..20000)
        val origin = origin(rideId) ?: return emptyPage()
        val bounds = bounds(origin, range) ?: return emptyPage()
        val cursor = cursor(origin, after)
        val rows = database.rideHistoryGpsQueries.pageGps(rideId, bounds.first, bounds.last,
            cursor?.first, cursor?.second ?: 0, limit.toLong() + 1).executeAsList()
        return page(rows, limit, {
            HistoryGpsFix(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration),
                it.latitude, it.longitude, it.accuracyMeters, it.headingDegrees)
        }, { HistoryCursor(checkNotNull(historyAxisEpochMillis(origin,
            HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration))), it.sequence.toString()) })
    }

    fun markers(rideId: String, range: HistoryRange): List<HistoryMarker> {
        val origin = origin(rideId) ?: return emptyList()
        val bounds = bounds(origin, range) ?: return emptyList()
        val rows = database.rideHistoryMarkerQueries.markersForRide(rideId, bounds.first, bounds.last, 20001).executeAsList()
        check(rows.size <= 20000) { "Marker range exceeds read bound; narrow the range" }
        return rows.map { HistoryMarker(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration), HistoryMarkerKind.valueOf(it.kind)) }
    }

    private fun origin(id: String) = database.rideHistoryV2Queries.getRide(id).executeAsOneOrNull()?.toHistoryRide()?.started

    private fun bounds(origin: HistoryTick, range: HistoryRange): LongRange? {
        if (range.endEpochMillis < origin.epochMillis) return null
        return elapsed(origin, maxOf(range.startEpochMillis, origin.epochMillis))..elapsed(origin, range.endEpochMillis)
    }

    private fun cursor(origin: HistoryTick, cursor: HistoryCursor?): Pair<Long, Long>? = cursor?.let {
        require(it.epochMillis >= origin.epochMillis)
        val sequence = it.stableId.toLongOrNull()
        require(sequence != null && sequence >= 0)
        elapsed(origin, it.epochMillis) to sequence
    }

    /** Saturates query bounds only; captured timestamps are never rewritten. */
    private fun elapsed(origin: HistoryTick, axis: Long): Long {
        if (origin.epochMillis < 0 && axis > Long.MAX_VALUE + origin.epochMillis) return Long.MAX_VALUE
        val delta = axis - origin.epochMillis
        return if (delta > Long.MAX_VALUE - origin.elapsedMillis) Long.MAX_VALUE else origin.elapsedMillis + delta
    }

    private fun <T> emptyPage(): HistoryPage<T> = HistoryPage(emptyList(), null, false)
    private fun <R, T> page(rows: List<R>, limit: Int, map: (R) -> T, cursor: (R) -> HistoryCursor): HistoryPage<T> {
        val selected = rows.take(limit)
        val more = rows.size > limit
        return HistoryPage(selected.map(map), if (more) cursor(selected.last()) else null, more)
    }
}

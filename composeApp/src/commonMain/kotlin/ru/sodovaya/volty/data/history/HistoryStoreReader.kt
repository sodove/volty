package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.HistoryBucketV2
import ru.sodovaya.volty.domain.history.*

private const val REPAIR_MAX_PAGES = 50

internal class HistoryStoreReader(private val database: VoltyDatabase) {
    fun recordedKeys(rideId: String, sourceId: String, limit: Int): HistoryRecordedKeys {
        require(limit in 1..20000)
        val empty = HistoryRecordedKeys(emptyList(), false)
        if (origin(rideId) == null) return empty
        val source = database.rideHistorySourceQueries.sourceForRide(rideId, sourceId).executeAsOneOrNull() ?: return empty
        val allowed = HistoryMetricRegistry.forSource(HistorySourceKind.valueOf(source.kind))
        val rows = database.rideHistorySeriesQueries.recordedKeysForSource(rideId, sourceId,
            allowed.filterNot { it.indexedByCell }.map { it.id },
            allowed.filter { it.indexedByCell }.map { it.id }, limit.toLong() + 1).executeAsList()
        return HistoryRecordedKeys(rows.take(limit).map {
            HistorySeriesKey(sourceId, it.metricId, it.cellIndex.takeIf { index -> index >= 0 }?.toInt())
        }, rows.size > limit)
    }

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
        val ride = database.rideHistoryV2Queries.getRide(rideId).executeAsOneOrNull()?.toHistoryRide() ?: return unknown
        val origin = ride.started
        val bounds = bounds(origin, range) ?: return unknown
        val summary = database.rideHistoryBucketQueries.rangeStats(bounds.first, bounds.last,
            rideId, key.sourceId, key.metricId, key.cellIndex?.toLong() ?: -1).executeAsOne()
        if (summary.bucketCount == 0L) return unknown
        // Retraction: a single detail page made healthy long rides incomplete
        // and omitted explicit tail extrema. Reduce the full indexed range to
        // one SQL row instead of materializing every detail sample in memory.
        val detail = database.rideHistorySampleQueries.rangeDetailStats(rideId, key.sourceId,
            key.metricId, key.cellIndex?.toLong() ?: -1, bounds.first, bounds.last).executeAsOne()
        val minimum = listOfNotNull(summary.minimum, detail.minimum).minOrNull()
        val maximum = listOfNotNull(summary.maximum, detail.maximum).maxOrNull()
        val energy = summary.energyWh?.takeIf { it.isFinite() }
        val gap = database.rideHistoryMarkerQueries.hasEvidenceGap(rideId, bounds.first, bounds.last, key.sourceId).executeAsOne()
        // Every bucket of the range must carry its sum: a mean over part of the stream is not a mean.
        val mean = summary.valueCount?.toDouble()?.takeIf { it > 0 && summary.valuesKnown?.toLong() == 1L }
            ?.let { count -> summary.valueSum?.toDouble()?.div(count) }?.takeIf { it.isFinite() }
        if (detail.unknownCount > 0L && bridgesPartialSourceArtefact(key.metricId)) {
            repairedControllerPowerEnergy(rideId, key, range, ride.status, gap)?.let {
                return HistoryStats(minimum, maximum, it.energyWh, it.complete, mean)
            }
        }
        return HistoryStats(minimum, maximum, energy, ride.status != HistoryRideStatus.INTERRUPTED && summary.complete == 1L && !gap &&
            detail.unknownCount == 0L &&
            (summary.energyWh == null || energy != null), mean)
    }

    /**
     * Energy for a controller-power series recorded BEFORE the Begode 0x00 fix. Stored buckets integrate at write time
     * and an artefact unknown between every pair of values meant no pair was ever usable, so those buckets hold no
     * energy and cannot be repaired in place without rewriting them (a migration). The retained detail rows (first,
     * min, max, last of each 500 ms window plus every explicit unknown) are enough to integrate again here, with the
     * PARTIAL_SOURCE artefact bridged ([HistoryPartialSourceBridge]); genuine unknowns, markers and long gaps still
     * break the integral and clear `complete`. This is an approximation by construction: the interior points the
     * detail gate thinned away are not integrated, so it is exact for no ride, but its error is bounded by the
     * min/max retention. A ride too large to read back within the page budget is not guessed at (null).
     * New recordings never reach this path: they carry no artefact unknowns, and the caller only comes here when the stored detail holds an unknown.
     */
    private fun repairedControllerPowerEnergy(rideId: String, key: HistorySeriesKey, range: HistoryRange,
        status: HistoryRideStatus, evidenceGap: Boolean): RepairedEnergy? {
        val multiplier = HistoryMetricRegistry.descriptor(key.metricId)?.consumptionMultiplier ?: return null
        val rows = ArrayList<HistorySample>()
        var after: HistoryCursor? = null
        var pages = 0
        while (true) {
            val page = samples(rideId, key, range, after, 20000)
            rows += page.items
            after = page.next ?: break
            if (++pages >= REPAIR_MAX_PAGES) return null
        }
        val markers = runCatching { markers(rideId, range) }.getOrNull()
            ?.filter { it.sourceId == null || it.sourceId == key.sourceId } ?: return null
        val stats = historyStats(bridgePartialSourceArtefact(rows), markers, multiplier)
        val energy = stats.energyWh ?: return null
        return RepairedEnergy(energy, stats.complete && !evidenceGap && status != HistoryRideStatus.INTERRUPTED)
    }

    private class RepairedEnergy(val energyWh: Double, val complete: Boolean)

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
                it.latitude, it.longitude, it.accuracyMeters, it.headingDegrees, it.provider?.let(HistoryGpsProvider::valueOf))
        }, { HistoryCursor(checkNotNull(historyAxisEpochMillis(origin,
            HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration))), it.sequence.toString()) })
    }

    fun markers(rideId: String, range: HistoryRange): List<HistoryMarker> {
        val origin = origin(rideId) ?: return emptyList()
        val bounds = bounds(origin, range) ?: return emptyList()
        val rows = database.rideHistoryMarkerQueries.markersForRide(rideId, bounds.first, bounds.last, 20001).executeAsList()
        check(rows.size <= 20000) { "Marker range exceeds read bound; narrow the range" }
        return rows.map { HistoryMarker(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration), HistoryMarkerKind.valueOf(it.kind), it.sourceId) }
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

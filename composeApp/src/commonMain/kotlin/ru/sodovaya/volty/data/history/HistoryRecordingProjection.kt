package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.history.*

internal data class PreparedHistoryProjection(val batch: HistoryBatch, val next: HistoryRecordingProjection)

/** Actor-owned state; preparing never advances the acknowledged stream. */
internal class HistoryRecordingProjection(
    private val ride: HistoryRide,
    private val sources: List<HistorySource>,
    private val maxSeries: Int = 2048,
) {
    private val sourceKinds = sources.associate { it.id to it.kind }
    private data class Series(val summary: HistoryBucketAccumulator, val detail: HistoryDetailGate) {
        fun fork() = Series(summary.fork(), detail.fork())
    }
    private val series = linkedMapOf<HistorySeriesKey, Series>()
    private val lastTicks = mutableMapOf<HistorySeriesKey, HistoryTick>()
    init {
        require(maxSeries > 0 && sources.size <= maxSeries && sourceKinds.size == sources.size)
    }

    fun prepare(batch: HistoryBatch, flushDetail: Boolean): PreparedHistoryProjection {
        require(batch.rideId == ride.id && batch.buckets.isEmpty())
        val next = HistoryRecordingProjection(ride, sources, maxSeries)
        series.forEach { (key, value) -> next.series[key] = value.fork() }
        next.lastTicks.putAll(lastTicks)
        return PreparedHistoryProjection(next.apply(batch, flushDetail), next)
    }

    private fun apply(batch: HistoryBatch, flushDetail: Boolean): HistoryBatch {
        // Validate source-local arrival order before merging streams by time; sorting must not hide regressions.
        batch.samples.forEach { sample ->
            val descriptor = checkNotNull(HistoryMetricRegistry.descriptor(sample.key.metricId))
            require(sourceKinds[sample.key.sourceId] in descriptor.sourceKinds)
            require(descriptor.indexedByCell == (sample.key.cellIndex != null))
            require(historyAxisEpochMillis(ride.started, sample.tick) != null)
            require(lastTicks[sample.key]?.elapsedMillis?.let { it <= sample.tick.elapsedMillis } != false)
            lastTicks[sample.key] = sample.tick
        }
        batch.markers.forEach {
            require(historyAxisEpochMillis(ride.started, it.tick) != null)
            require(it.sourceId == null || it.sourceId in sourceKinds) { "Unregistered marker source" }
        }
        batch.gps.forEach { require(historyAxisEpochMillis(ride.started, it.tick) != null) }
        val detail = mutableListOf<HistorySample>()
        val summaries = linkedMapOf<Pair<HistorySeriesKey, Long>, HistoryBucket>()
        fun retain(bucket: HistoryBucket) { summaries[bucket.key to bucket.slot] = bucket }
        var markerIndex = 0
        val markers = batch.markers.sortedBy { it.tick.elapsedMillis }
        fun mark(marker: HistoryMarker) {
            if (marker.kind == HistoryMarkerKind.GPS_GAP) return
            series.forEach { (key, value) ->
                if (marker.sourceId == null || marker.sourceId == key.sourceId) {
                    detail += value.detail.flush()
                    value.summary.mark(marker)?.let(::retain)
                }
            }
        }
        batch.samples.sortedBy { it.tick.elapsedMillis }.forEach { sample ->
            // Within one batch, a marker at exactly a sample's tick is applied AFTER that sample (retraction: it used to come
            // first, `<=`). The ride-ending link-loss marker is stamped at the loss tick, which can equal the last sample's
            // tick; applied first it made that last sample cross a barrier and flagged a bucket whose every sample was
            // recorded. This is batch-dependent, not a global rule: a marker in batch N whose same-tick sample arrives in
            // batch N+1 is already applied before it (review M2). Harmless for energy: no interval over the limit is ever
            // integrated, and a real gap is longer than the limit and breaks on that alone.
            while (markerIndex < markers.size && markers[markerIndex].tick.elapsedMillis < sample.tick.elapsedMillis) {
                mark(markers[markerIndex++])
            }
            val current = series[sample.key] ?: run {
                require(series.size < maxSeries) { "History series capacity exceeded" }
                Series(HistoryBucketAccumulator(ride.started, sample.key), HistoryDetailGate(ride.started, sample.key))
                    .also { series[sample.key] = it }
            }
            current.summary.offer(sample).forEach(::retain)
            detail += current.detail.offer(sample)
        }
        while (markerIndex < markers.size) mark(markers[markerIndex++])
        if (flushDetail) series.values.forEach { detail += it.detail.flush() }
        return batch.copy(samples = detail.sortedBy { it.tick.elapsedMillis }, buckets = summaries.values.toList())
    }
}

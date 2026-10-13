package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.HistoryBucketV2
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.*

internal class HistoryBucketStorage(private val database: VoltyDatabase) {
    /** Invoked in the same transaction as detail and the immutable append receipt. */
    fun append(batch: HistoryBatch, origin: HistoryTick, sources: Set<String>): HistoryTick? {
        require(batch.buckets.isEmpty() || batch.receiptId != null) { "Bucket updates require a receipt" }
        var last: HistoryTick? = null
        batch.buckets.forEach { bucket ->
            require(bucket.key.sourceId in sources) { "Unregistered source" }
            require(historyAxisEpochMillis(origin, bucket.first) != null)
            require(historyAxisEpochMillis(origin, bucket.last) != null)
            require((bucket.last.elapsedMillis - origin.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS == bucket.slot)
            val anchor = origin.elapsedMillis + bucket.slot * HISTORY_SUMMARY_BUCKET_MILLIS
            val key = bucket.key
            val old = database.rideHistoryBucketQueries.getBucket(batch.rideId, key.sourceId,
                key.metricId, key.cellIndex?.toLong() ?: -1, anchor).executeAsOneOrNull()
            if (old != null) {
                require(bucket.sampleCount >= old.sampleCount && bucket.last.elapsedMillis >= old.endElapsedMillis) {
                    "Bucket snapshot regressed"
                }
                require(old.firstElapsedMillis == null || bucket.first.elapsedMillis == old.firstElapsedMillis) {
                    "Bucket coverage origin changed"
                }
            }
            database.rideHistoryBucketQueries.putBucket(batch.rideId, key.sourceId, key.metricId,
                key.cellIndex?.toLong() ?: -1, anchor, bucket.last.elapsedMillis,
                bucket.minimum?.value, bucket.maximum?.value, bucket.energyWh, bucket.sampleCount,
                if (bucket.complete) 1 else 0, bucket.first.epochMillis, bucket.first.elapsedMillis,
                bucket.last.epochMillis, bucket.minimum?.tick?.epochMillis, bucket.minimum?.tick?.elapsedMillis,
                bucket.maximum?.tick?.epochMillis, bucket.maximum?.tick?.elapsedMillis)
            if (last == null || bucket.last.elapsedMillis >= checkNotNull(last).elapsedMillis) last = bucket.last
        }
        return last
    }
}

internal fun HistoryBucketV2.toHistoryBucket(origin: HistoryTick): HistoryBucket {
    val key = HistorySeriesKey(sourceId, metricId, cellIndex.takeIf { it >= 0 }?.toInt())
    fun tick(epoch: Long?, elapsed: Long?) = HistoryTick(checkNotNull(epoch), checkNotNull(elapsed), origin.clockGeneration)
    fun peak(value: Double?, epoch: Long?, elapsed: Long?) = value?.let { HistorySample(tick(epoch, elapsed), key, it) }
    return HistoryBucket(key, (startElapsedMillis - origin.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS,
        tick(firstEpochMillis, firstElapsedMillis), tick(lastEpochMillis, endElapsedMillis),
        peak(minimum, minEpochMillis, minElapsedMillis), peak(maximum, maxEpochMillis, maxElapsedMillis),
        energyWh, sampleCount, complete == 1L)
}

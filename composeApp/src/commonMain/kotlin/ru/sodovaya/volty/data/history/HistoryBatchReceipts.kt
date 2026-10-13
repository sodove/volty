package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.*

/** Called inside append's transaction. Receipts reference original rows, avoiding duplicate payload storage. */
internal class HistoryBatchReceipts(private val database: VoltyDatabase) {
    fun verifyCommitted(batch: HistoryBatch): Boolean {
        val token = batch.receiptId ?: return false
        val receipt = database.rideHistoryReceiptQueries.getReceipt(batch.rideId, token).executeAsOneOrNull()
            ?: return false
        val samples = database.rideHistorySampleQueries.samplesForReceipt(batch.rideId,
            receipt.firstSequence, receipt.nextSequence).executeAsList().map {
            HistorySample(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration),
                HistorySeriesKey(it.sourceId, it.metricId, it.cellIndex.takeIf { index -> index >= 0 }?.toInt()),
                it.reading, it.unknownReason?.let(HistoryUnknownReason::valueOf))
        }
        val gps = database.rideHistoryGpsQueries.gpsForReceipt(batch.rideId,
            receipt.firstSequence, receipt.nextSequence).executeAsList().map {
            HistoryGpsFix(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration),
                it.latitude, it.longitude, it.accuracyMeters, it.headingDegrees)
        }
        val markers = database.rideHistoryMarkerQueries.markersForReceipt(batch.rideId,
            receipt.firstSequence, receipt.nextSequence).executeAsList().map {
            HistoryMarker(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration), HistoryMarkerKind.valueOf(it.kind))
        }
        require(samples.size == batch.samples.size && samples.zip(batch.samples).all { (a, b) ->
            a.tick == b.tick && a.key == b.key && a.unknown == b.unknown && sameReading(a.value, b.value)
        } && gps.size == batch.gps.size && gps.zip(batch.gps).all { (a, b) ->
            a.tick == b.tick && a.lat == b.lat && a.lon == b.lon && a.accuracyMeters == b.accuracyMeters && sameReading(a.heading, b.heading)
        } && markers == batch.markers) {
            "Receipt reused with a different payload"
        }
        return true
    }

    fun record(batch: HistoryBatch, firstSequence: Long, nextSequence: Long) {
        batch.receiptId?.let {
            database.rideHistoryReceiptQueries.insertReceipt(batch.rideId, it, firstSequence, nextSequence)
        }
    }

    /** SQLite canonicalizes negative zero; numeric equality remains zero, never unknown. */
    private fun sameReading(a: Double?, b: Double?): Boolean =
        if (a == null || b == null) a == null && b == null else a == b
}

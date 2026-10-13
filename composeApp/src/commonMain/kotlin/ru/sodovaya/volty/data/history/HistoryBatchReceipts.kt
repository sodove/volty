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
        require(samples == batch.samples && gps == batch.gps && markers == batch.markers) {
            "Receipt reused with a different payload"
        }
        return true
    }

    fun record(batch: HistoryBatch, firstSequence: Long, nextSequence: Long) {
        batch.receiptId?.let {
            database.rideHistoryReceiptQueries.insertReceipt(batch.rideId, it, firstSequence, nextSequence)
        }
    }
}

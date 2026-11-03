package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.HistoryBatch

/**
 * Called inside append's transaction. A receipt certifies a batch by its fingerprint.
 *
 * Retraction: receipts without a fingerprint used to be verified by comparing the rows they covered. A run keeps only its
 * first and last member, so rows cannot certify a payload any more, and 28.sqm deleted every such receipt (each certified a
 * retry of a process that no longer exists). `record` always writes a fingerprint, so one without is refused.
 */
internal class HistoryBatchReceipts(private val database: VoltyDatabase) {
    fun verifyCommitted(batch: HistoryBatch): Boolean {
        val token = batch.receiptId ?: return false
        val receipt = database.rideHistoryReceiptQueries.getReceipt(batch.rideId, token).executeAsOneOrNull()
            ?: return false
        val fingerprint = checkNotNull(receipt.fingerprint) { "A receipt without a fingerprint cannot certify a batch" }
        require(fingerprint == historyBatchFingerprint(batch)) { "Receipt reused with a different payload" }
        return true
    }

    fun record(batch: HistoryBatch, firstSequence: Long, nextSequence: Long) {
        batch.receiptId?.let {
            database.rideHistoryReceiptQueries.insertReceipt(batch.rideId, it, firstSequence, nextSequence,
                historyBatchFingerprint(batch))
        }
    }
}

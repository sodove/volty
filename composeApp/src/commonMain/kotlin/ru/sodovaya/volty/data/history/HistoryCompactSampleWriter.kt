package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.db.HistorySampleV3
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.domain.history.*

/**
 * How one sample is encoded in the compact layout (`RideHistoryCompact.sq`, compact-history-storage plan Task 5). The
 * writer and the readers (Task 6) share it, so there is one mapping.
 *
 *  - `unknownCode` is a FIXED table, not `ordinal + 1`: reordering [HistoryUnknownReason] must not relabel stored rows.
 *    The upper bound lives here and not in a SQL CHECK (see the .sq header); a code this build does not know decodes as
 *    [HistoryUnknownReason.INVALID_VALUE] ("reads back as invalid data") and never extends a run.
 *  - `epochSkew` / `clockDelta` are relative to the ride origin, so `epochMillis` is reconstructed EXACTLY.
 *  - A reading must be finite. SQLite stores NaN as NULL, which would turn a known row into one with neither a reading
 *    nor a code and hit the CHECK. [HistorySample] already refuses a non-finite value, and the telemetry adapter maps
 *    an invalid reading to INVALID_VALUE before it gets here (as V2 always did: V2 never stored a NaN either), so this is
 *    a guard with a message, not a second mapping. `-0.0` is stored as SQLite's `0.0`, which the receipts already accept.
 */
internal object HistoryCompactCodec {
    const val MAX_UNKNOWN_CODE = 5L

    fun code(reason: HistoryUnknownReason): Long = when (reason) {
        HistoryUnknownReason.UNAVAILABLE -> 1L
        HistoryUnknownReason.PARTIAL_SOURCE -> 2L
        HistoryUnknownReason.INVALID_VALUE -> 3L
        HistoryUnknownReason.GAP -> 4L
        HistoryUnknownReason.LEGACY -> 5L
    }

    /** Null for a code this build does not know (a newer writer's reason); readers show it as invalid data. */
    fun reasonOrNull(code: Long): HistoryUnknownReason? = when (code) {
        1L -> HistoryUnknownReason.UNAVAILABLE
        2L -> HistoryUnknownReason.PARTIAL_SOURCE
        3L -> HistoryUnknownReason.INVALID_VALUE
        4L -> HistoryUnknownReason.GAP
        5L -> HistoryUnknownReason.LEGACY
        else -> null
    }

    fun reason(code: Long): HistoryUnknownReason = reasonOrNull(code) ?: HistoryUnknownReason.INVALID_VALUE

    fun epochSkew(origin: HistoryTick, tick: HistoryTick): Long =
        (tick.epochMillis - tick.elapsedMillis) - (origin.epochMillis - origin.elapsedMillis)

    fun clockDelta(origin: HistoryTick, tick: HistoryTick): Long = tick.clockGeneration - origin.clockGeneration

    fun tick(origin: HistoryTick, elapsed: Long, epochSkew: Long, clockDelta: Long): HistoryTick =
        HistoryTick(elapsed + (origin.epochMillis - origin.elapsedMillis) + epochSkew, elapsed,
            origin.clockGeneration + clockDelta)

    fun reading(value: Double): Double {
        require(value.isFinite()) { "A non-finite reading must be recorded as INVALID_VALUE, not stored" }
        return value
    }

    /**
     * One stored row as the pure run model sees it. A run's END tick carries the FIRST member's epoch skew: the end's own
     * epoch is not stored (plan, "Without loss" 2), and the writer compares a new member against exactly this tick, so the
     * stored skew never drifts more than [HISTORY_RUN_SKEW_TOLERANCE_MILLIS] from any member it stands for.
     */
    fun decode(row: HistorySampleV3, key: HistorySeriesKey, origin: HistoryTick): HistoryStoredSample {
        val tick = tick(origin, row.elapsedMillis, row.epochSkew, row.clockDelta)
        val code = row.unknownCode
        val sample = if (code == null) HistorySample(tick, key, checkNotNull(row.reading))
            else HistorySample(tick, key, null, reason(code))
        val count = row.runCount ?: return HistoryStoredSample(sample, row.sequence)
        val end = tick(origin, checkNotNull(row.runEndElapsed), row.epochSkew, row.clockDelta)
        return HistoryStoredSample(sample, row.sequence,
            HistoryUnknownRunEnd(end, checkNotNull(row.runEndSequence), count.toInt()))
    }
}

/**
 * Writes the samples of a compact (V3) ride. Every call runs inside the caller's `append` transaction.
 *
 * ## Runs are extended in the database, never held in memory
 * There is no open-run state in this class or in the recorder. The "open" run of a series is simply its newest stored
 * row (`newestSampleOfSeriesV3`: one backwards seek on the primary key). A new unknown either extends that row with
 * one `UPDATE` (`runCount + 1`, run end = the new tick and sequence) or is inserted as its own row. Consequences:
 *  - **Crash / kill mid-ride.** Each batch is one transaction, so the database holds either the run as it was before the
 *    batch or the run extended by the whole batch, never half. What survives is always a valid row: a lone unknown, or
 *    a run with `runCount >= 2` whose span satisfies the Task 3 bound (checked below before every extension). Known
 *    samples are written row by row and are never deferred by run building, so no known sample is lost that the V2
 *    writer would have kept. After a restart `recoverInterrupted` closes the ride with an INTERRUPTED marker exactly as
 *    for V2; nothing needs repairing.
 *  - **Retry of a committed batch** is answered by its receipt fingerprint before this class runs (a compact ride never
 *    has a legacy, fingerprint-less receipt; `HistoryBatchReceipts` refuses one), so a retried batch never extends a
 *    run twice.
 *  - **A marker after an open run.** `historyRunContinues` is given the batch's own markers plus every committed marker
 *    after the run end, so a gap marker committed in an earlier batch (or earlier in this one) splits the run.
 *
 * ## Continuation rule
 * `historyRunContinues(end, sample, markers)`, the single shared rule (Task 3), where `end` is the stored run end with
 * the first member's skew (see [HistoryCompactCodec.decode]). Comparing against the first member's skew is slightly
 * STRICTER than the per-step rule of `collapseUnknownRuns` (which compares neighbours): a run splits once the wall clock
 * drifted more than 15 ms from its first member. That bounds the parked Task 3 concern M6 (unbounded drift inside a
 * run) for new rides; on the rider's database the drift from a chain's first row never exceeded 14 ms, so compaction is
 * unchanged. A split run expands exactly, so readers cannot tell.
 */
internal class HistoryCompactSampleWriter(private val database: VoltyDatabase) {
    private val queries = database.rideHistoryCompactQueries

    fun rideNo(rideId: String): Long? = queries.rideNoV3(rideId).executeAsOneOrNull()

    /** Gives a new ride its key: from now on it is a compact ride and every reader dispatches on that (Task 6). */
    fun begin(rideId: String) {
        val next = queries.maxRideNoV3().executeAsOne() + 1
        queries.insertRideKeyV3(next, rideId)
    }

    /**
     * Samples of a ride with a key, its series and the key, in the caller's transaction. With MAX+1 id allocation a
     * deleted highest id is reused, which is safe only because all three go together.
     */
    fun delete(rideId: String) {
        val no = rideNo(rideId) ?: return
        queries.deleteSamplesOfRideV3(no)
        queries.deleteSeriesOfRideV3(no)
        queries.deleteRideKeyV3(no)
    }

    /** One per append transaction: the series map must never outlive it (a rolled-back insert would leave a dangling id). */
    fun session(rideId: String, rideNo: Long, origin: HistoryTick, batchMarkers: List<HistoryMarker>) =
        Session(rideId, rideNo, origin, batchMarkers)

    inner class Session internal constructor(
        private val rideId: String,
        private val rideNo: Long,
        private val origin: HistoryTick,
        private val batchMarkers: List<HistoryMarker>,
    ) {
        private val series = HashMap<HistorySeriesKey, Long>()

        private fun seriesId(key: HistorySeriesKey): Long = series.getOrPut(key) {
            val cell = key.cellIndex?.toLong() ?: -1L
            queries.seriesForV3(rideNo, key.sourceId, key.metricId, cell).executeAsOneOrNull()
                ?: (queries.maxSeriesIdV3().executeAsOne() + 1).also {
                    queries.insertSeriesV3(it, rideNo, key.sourceId, key.metricId, cell)
                }
        }

        fun write(sample: HistorySample, sequence: Long) {
            val id = seriesId(sample.key)
            val tick = sample.tick
            val skew = HistoryCompactCodec.epochSkew(origin, tick)
            val delta = HistoryCompactCodec.clockDelta(origin, tick)
            val reason = sample.unknown
            if (reason == null) {
                queries.insertSampleV3(id, tick.elapsedMillis, sequence, skew, delta,
                    HistoryCompactCodec.reading(checkNotNull(sample.value)), null, null, null, null)
                return
            }
            if (extend(id, sample, sequence)) return
            queries.insertSampleV3(id, tick.elapsedMillis, sequence, skew, delta, null,
                HistoryCompactCodec.code(reason), null, null, null)
        }

        private fun extend(id: Long, sample: HistorySample, sequence: Long): Boolean {
            val newest = queries.newestSampleOfSeriesV3(id).executeAsOneOrNull() ?: return false
            val code = newest.unknownCode ?: return false
            val reason = HistoryCompactCodec.reasonOrNull(code) ?: return false
            val endElapsed = newest.runEndElapsed ?: newest.elapsedMillis
            val endSequence = newest.runEndSequence ?: newest.sequence
            val count = newest.runCount ?: 1L
            val next = sample.tick.elapsedMillis
            if (sequence <= endSequence || next < endElapsed || count >= Int.MAX_VALUE) return false
            // Task 3: a stored run must be a chain of steps within the interval limit.
            if (next - newest.elapsedMillis > HISTORY_MAX_INTERVAL_MILLIS * count) return false
            val end = HistorySample(HistoryCompactCodec.tick(origin, endElapsed, newest.epochSkew, newest.clockDelta),
                sample.key, null, reason)
            val markers = if (next > endElapsed) batchMarkers + committedMarkers(sample.key.sourceId, endElapsed, next)
                else batchMarkers
            if (!historyRunContinues(end, sample, markers)) return false
            queries.extendRunV3(count + 1, next, sequence, id, newest.elapsedMillis, newest.sequence)
            return true
        }

        private fun committedMarkers(sourceId: String, after: Long, to: Long): List<HistoryMarker> =
            queries.markersAfterV3(rideId, after, to, sourceId).executeAsList().map {
                HistoryMarker(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration),
                    HistoryMarkerKind.valueOf(it.kind), it.sourceId)
            }
    }
}

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
     * One stored row as the pure run model sees it. A run's end tick is exact: it carries the end member's own skew
     * (`runEndEpochSkew`). An earlier revision rebuilt it from the FIRST member's skew; that was wrong (the end epoch read
     * back up to 14 ms off on real data, and the `samples` reader and the fingerprint print it).
     */
    fun decode(row: HistorySampleV3, key: HistorySeriesKey, origin: HistoryTick): HistoryStoredSample {
        val tick = tick(origin, row.elapsedMillis, row.epochSkew, row.clockDelta)
        val code = row.unknownCode
        val sample = if (code == null) HistorySample(tick, key, checkNotNull(row.reading))
            else HistorySample(tick, key, null, reason(code))
        val count = row.runCount ?: return HistoryStoredSample(sample, row.sequence)
        val end = tick(origin, checkNotNull(row.runEndElapsed), checkNotNull(row.runEndEpochSkew), row.clockDelta)
        return HistoryStoredSample(sample, row.sequence,
            HistoryUnknownRunEnd(end, checkNotNull(row.runEndSequence), count.toInt()))
    }

    /** The newest member of a stored row: the run end, or the row itself. */
    fun last(row: HistoryStoredSample): HistorySample =
        row.run?.let { HistorySample(it.tick, row.sample.key, null, row.sample.unknown) } ?: row.sample
}

/**
 * Cuts a stored run where a gap marker at [elapsed] lies inside it (`first.elapsed < elapsed <= end.elapsed`), or null
 * when it does not. The members are those [expandForReaders] gives (interior ticks were not kept), so members before
 * [elapsed] stay in the first part and the rest form the second. Interior sequences are interpolated the same way
 * between the first and the end sequence; they stay strictly increasing because a run's sequences are distinct.
 *
 * Not exact, stated: the two members at the cut are the expansion's interpolated ticks and sequences, not the real ones,
 * so the cut differs from what `collapseUnknownRuns` makes of the full rows by less than one inter-sample step at each new
 * edge (the bound Ruling 4 names for a range that cuts a run). Each part keeps the Task 3 span bound.
 */
internal fun splitStoredRunAt(row: HistoryStoredSample, elapsed: Long): Pair<HistoryStoredSample, HistoryStoredSample>? {
    val run = row.run ?: return null
    if (run.count < 2 || elapsed <= row.sample.tick.elapsedMillis || elapsed > run.tick.elapsedMillis) return null
    val members = HistoryRunMembers(row)
    val cut = members.firstIndex { members.elapsed(it) >= elapsed }
    check(cut in 1 until members.size)
    fun part(from: Int, to: Int): HistoryStoredSample {
        val count = to - from + 1
        val end = if (count >= 2) HistoryUnknownRunEnd(members.tick(to), members.sequence(to), count) else null
        return HistoryStoredSample(members.sample(from), members.sequence(from), end)
    }
    return part(0, cut - 1) to part(cut, members.size - 1)
}

/**
 * Writes the samples of a compact (V3) ride. Every call runs inside the caller's `append` transaction.
 *
 * ## Runs are extended in the database, never held in memory
 * There is no open-run state in this class or in the recorder. The "open" run of a series is simply its newest stored
 * row (`latestAtOrBeforeV3`: one backwards seek on the primary key). A new unknown either extends that row with one
 * `UPDATE` (`runCount + 1`, run end = the new tick, sequence and skew) or is inserted as its own row. Consequences:
 *  - **Crash / kill mid-ride.** Each batch is one transaction, so the database holds either the run as it was before the
 *    batch or the run extended by the whole batch, never half. What survives is always a valid row: a lone unknown, or
 *    a run with `runCount >= 2` whose span satisfies the Task 3 bound (checked below before every extension). Known
 *    samples are written row by row and are never deferred by run building, so no known sample is lost that the V2
 *    writer would have kept. After a restart `recoverInterrupted` closes the ride with an INTERRUPTED marker exactly as
 *    for V2 (and cuts a run that marker lands in, see below); nothing else needs repairing.
 *  - **Retry of a committed batch** is answered by its receipt fingerprint before this class runs (a compact ride never
 *    has a legacy, fingerprint-less receipt; `HistoryBatchReceipts` refuses one), so a retried batch never extends a
 *    run twice.
 *  - **A marker after an open run.** `historyRunContinues` is given the batch's own markers plus every committed marker
 *    after the run end, so a gap marker committed in an earlier batch (or earlier in this one) splits the run.
 *
 * ## Continuation rule: the one `collapseUnknownRuns` uses
 * `historyRunContinues(end, sample, markers)` with `end` the run's EXACT last member, so every step is judged neighbour
 * to neighbour, as `collapseUnknownRuns` judges it, and a live-recorded ride stores the runs a conversion of the same
 * rows would. An earlier revision compared against the end rebuilt with the FIRST member's skew and called that
 * "stricter"; that was wrong: neither rule contains the other (skews 0, 10, 20, 30 join under one and split under the
 * other; 0, -10, +10 the reverse). Tested against `collapseUnknownRuns` on both fixtures.
 *
 * ## A gap marker that arrives after a run grew past it (measured, 2026-10-29)
 * Markers are committed in their own lanes (the OVERFLOW `loss` marker after the pending packets, CONNECTED from the
 * recording policy, INTERRUPTED by recovery at the ride's last tick), so a gap marker can be committed with a tick
 * BELOW unknowns that are already stored. On the rider's database (read-only): 19 global CONNECTED markers are
 * back-dated 250-1000 ms behind earlier-stored unknowns, but each lies before its series' first sample or after a gap of
 * 6 s or more, so none cuts a chain; ONE INTERRUPTED marker lands on the last tick of an unknown chain (recovery puts it
 * at the ride's last elapsed). `collapseUnknownRuns` splits a chain at such a marker; a live run that already grew past
 * it cannot be rebuilt from interior ticks it no longer has. [splitRunsAt] therefore cuts the run with
 * [splitStoredRunAt], whose cut members are interpolated (less than one inter-sample step off, stated there), so a run
 * never straddles a gap marker, which every Task 3 proof assumes.
 *
 * ## Order
 * A sample whose elapsed falls inside a stored run (`first <= elapsed < end`) is refused: it would sit between members
 * that no longer exist, and `expandForReaders` would emit elapsed going backwards. Production never sends one
 * (`HistoryRecordingProjection` requires per-series order); a conversion that did would fail loudly here.
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

    /**
     * Cuts every stored run of an affected series that [marker] lies inside (see the class KDoc) and returns how many
     * were cut. A non-gap marker, or one outside every run, changes nothing.
     */
    fun splitRunsAt(rideNo: Long, origin: HistoryTick, marker: HistoryMarker): Int {
        if (marker.kind !in HISTORY_GAP_MARKERS) return 0
        val at = marker.tick.elapsedMillis
        var cut = 0
        queries.seriesOfRideV3(rideNo).executeAsList()
            .filter { marker.sourceId == null || marker.sourceId == it.sourceId }
            .forEach { series ->
                val row = queries.latestBeforeV3(series.seriesId, at).executeAsOneOrNull() ?: return@forEach
                val code = row.unknownCode ?: return@forEach
                if (row.runCount == null) return@forEach
                val key = HistorySeriesKey(series.sourceId, series.metricId, series.cellIndex.takeIf { it >= 0 }?.toInt())
                val (head, tail) = splitStoredRunAt(HistoryCompactCodec.decode(row, key, origin), at) ?: return@forEach
                setEnd(series.seriesId, row, origin, head.run)
                val first = tail.sample.tick
                val end = tail.run
                queries.insertSampleV3(series.seriesId, first.elapsedMillis, tail.sequence,
                    HistoryCompactCodec.epochSkew(origin, first), HistoryCompactCodec.clockDelta(origin, first), null, code,
                    end?.tick?.elapsedMillis, end?.sequence, end?.tick?.let { HistoryCompactCodec.epochSkew(origin, it) },
                    end?.count?.toLong())
                cut++
            }
        return cut
    }

    /** Sets (or, with null, clears) the run end of [row]. */
    private fun setEnd(seriesId: Long, row: HistorySampleV3, origin: HistoryTick, end: HistoryUnknownRunEnd?) {
        queries.extendRunV3(end?.count?.toLong(), end?.tick?.elapsedMillis, end?.sequence,
            end?.tick?.let { HistoryCompactCodec.epochSkew(origin, it) }, seriesId, row.elapsedMillis, row.sequence)
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
            // For an in-order sample this is the series' newest row; for a late one, the only run it could fall inside.
            val previous = queries.latestAtOrBeforeV3(id, tick.elapsedMillis).executeAsOneOrNull()
            previous?.runEndElapsed?.let { end ->
                require(tick.elapsedMillis >= end) { "A sample inside a stored unknown run is out of order" }
            }
            val reason = sample.unknown
            if (reason == null) {
                queries.insertSampleV3(id, tick.elapsedMillis, sequence, skew, delta,
                    HistoryCompactCodec.reading(checkNotNull(sample.value)), null, null, null, null, null)
                return
            }
            if (previous != null && extend(id, previous, sample, sequence)) return
            queries.insertSampleV3(id, tick.elapsedMillis, sequence, skew, delta, null,
                HistoryCompactCodec.code(reason), null, null, null, null)
        }

        private fun extend(id: Long, row: HistorySampleV3, sample: HistorySample, sequence: Long): Boolean {
            val code = row.unknownCode ?: return false
            // A code this build does not know never absorbs a sample, whatever reason it decodes as.
            HistoryCompactCodec.reasonOrNull(code) ?: return false
            val stored = HistoryCompactCodec.decode(row, sample.key, origin)
            val end = HistoryCompactCodec.last(stored)
            val count = stored.run?.count ?: 1
            val endSequence = stored.run?.sequence ?: stored.sequence
            val next = sample.tick.elapsedMillis
            if (sequence <= endSequence || count == Int.MAX_VALUE) return false
            // Task 3: a stored run must be a chain of steps within the interval limit.
            if (next - row.elapsedMillis > HISTORY_MAX_INTERVAL_MILLIS * count) return false
            val markers = if (next > end.tick.elapsedMillis)
                batchMarkers + committedMarkers(sample.key.sourceId, end.tick.elapsedMillis, next) else batchMarkers
            if (!historyRunContinues(end, sample, markers)) return false
            setEnd(id, row, origin, HistoryUnknownRunEnd(sample.tick, sequence, count + 1))
            return true
        }

        private fun committedMarkers(sourceId: String, after: Long, to: Long): List<HistoryMarker> =
            queries.markersAfterV3(rideId, after, to, sourceId).executeAsList().map {
                HistoryMarker(HistoryTick(it.epochMillis, it.elapsedMillis, it.clockGeneration),
                    HistoryMarkerKind.valueOf(it.kind), it.sourceId)
            }
    }
}

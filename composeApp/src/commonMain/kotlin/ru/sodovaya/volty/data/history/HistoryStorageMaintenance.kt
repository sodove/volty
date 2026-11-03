package ru.sodovaya.volty.data.history

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.sodovaya.volty.data.db.HistorySampleV3
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*

/**
 * Background upkeep of the compact history (compact-history-storage plan, Task 11): fold chains of stored unknown rows into
 * runs, and reclaim free pages with VACUUM when that is worth it. Never inside a migration, never while recording.
 *
 * ## What is actually left to do (measured, 2026-11-03)
 * The brief was written when the plan expected `28.sqm` to store every migrated unknown as its own row (518,192 rows on the
 * rider's database) and to leave the dropped V2 pages on the freelist. Neither happened. `28.sqm` detects runs itself
 * (5,708 unknown rows, 353 runs), and on the rider's copy migrated through it the rule below finds exactly ZERO rows to fold:
 * the only two unknown pairs that pass every other clause are split by an INTERRUPTED marker. The database runs with
 * `auto_vacuum = FULL`, so the commit already returns the V2 pages to the file system: `freelist_count` is 0 after the
 * migration (82.2 MB file). A manual VACUUM still shrinks that file to 74.7 MB, because it repacks half-filled pages, but
 * that is not free pages, and the brief's rule (32 MiB on the freelist) is deliberately not bent to force it. So on the
 * rider's phone this class is a no-op that reads the unknown rows ONCE per schema version (33 ms warm on a PC for the
 * whole file; a full scan of each series' key range, no writes), kept because it is the only thing that would repair a
 * chain of lone rows if any producer ever leaves one. Retraction: the first revision read them at every app start; the
 * review (I4) and the owner's ruling replaced that with the `history_compaction_done_schema` preference.
 *
 * ## Compaction rule
 * Two stored unknown rows of one series join when they are NEIGHBOURS in reader order (no row of the series between their
 * primary keys) and the step from the first row's LAST member to the second row's first member continues a run exactly as
 * the live writer judges it (`HistoryCompactSampleWriter.extend`): [historyRunContinues] with the ride's gap markers, plus
 * the writer's sequence rule (sequences advance, so the pager's cursors stay distinct and `runEndSequence > sequence`
 * holds), and a code this build knows. On lone rows that is [collapseUnknownRuns] plus the sequence rule, i.e. what a fresh
 * recording of the same samples stores (tested row for row). It also joins a run to a neighbour, because a fresh recording
 * would have; the merged run keeps the Task 3 span bound, since each part kept it and the joining step is within the limit.
 * Interior ticks of the joined chain are then interpolated, the difference Global Constraint 2.3 accepts for every run.
 *
 * The brief said "apply `collapseUnknownRuns`": it folds raw samples with their index as the sequence and cannot take a
 * stored run as input, so the fold here is over stored rows with the same [historyRunContinues]; the equality with a fresh
 * recording is the test of that.
 *
 * ## Transactions
 * Each ride is planned with reads only (its unknown rows, a neighbour probe per candidate pair), with no write lock, so a
 * recorder's `begin`/`append` never queues behind the scan. Only a ride with something to fold opens ONE short write
 * transaction, which re-checks that the ride still exists and is not RECORDING and redoes the plan before it UPDATEs each
 * head and DELETEs each absorbed row. A kill loses at most the current ride's work and leaves it exactly as it was; a
 * compacted ride has nothing left to fold, so a re-run is a no-op. Between rides the pass yields and asks again whether a
 * ride is being recorded. Since fix round 1 the whole pass runs once per schema version (app preference flag), so the scan
 * is paid once, not at every start.
 *
 * A reader paging a ride while it is compacted (a ride open on screen at launch) may hold a cursor on a member whose row
 * the commit folds into a run; its next page is computed from the new rows, so one boundary sample can repeat or be
 * skipped once. That is inside the interpolated-interior trade-off Global Constraint 2.3 already accepts.
 */
sealed interface HistoryMaintenanceStep {
    /** Compact the next ride. Planned one ride at a time, so recording is asked again before each. */
    data object Compact : HistoryMaintenanceStep
    data object Vacuum : HistoryMaintenanceStep
}

/** VACUUM is worth its full rewrite only when this much sits on the freelist. */
const val HISTORY_VACUUM_MIN_FREELIST_BYTES: Long = 32L * 1024 * 1024

/**
 * The whole decision. Nothing while recording. Compaction first, one ride per step, while [ridesLeft] > 0. VACUUM only
 * when compaction is done, at least [HISTORY_VACUUM_MIN_FREELIST_BYTES] are free pages, and the volume has twice the live
 * size free: VACUUM writes a full copy of the live pages to a temporary file and then a journal (or WAL) of the same size,
 * so it needs about twice the FINAL size, which is the live size.
 */
fun maintenancePlan(recording: Boolean, freelistBytes: Long, liveBytes: Long, freeBytes: Long, ridesLeft: Int): List<HistoryMaintenanceStep> {
    if (recording) return emptyList()
    if (ridesLeft > 0) return listOf(HistoryMaintenanceStep.Compact)
    if (freelistBytes >= HISTORY_VACUUM_MIN_FREELIST_BYTES && freeBytes >= 2 * liveBytes) return listOf(HistoryMaintenanceStep.Vacuum)
    return emptyList()
}

/** A chain of stored rows that becomes one run: [head] keeps its primary key, [absorbed] rows are deleted. */
internal data class HistoryRunMerge(val head: HistoryStoredSample, val absorbed: List<HistoryStoredSample>, val end: HistoryUnknownRunEnd)

/**
 * Folds the stored rows of ONE series ([rows] in reader order; known rows may be left out when [neighbours] says which
 * consecutive entries are adjacent in the store) into the merges the compaction rule asks for. [breaks] are the series'
 * gap-marker ticks ([historyRunBreakTicks]). [neighbours] is asked only for a pair the rule would join, because the store
 * answers it with a query.
 */
internal fun planHistoryRunMerges(rows: List<HistoryStoredSample>, breaks: LongArray,
    neighbours: (index: Int) -> Boolean = { true }): List<HistoryRunMerge> {
    val merges = ArrayList<HistoryRunMerge>()
    var head = -1
    var count = 0L
    fun flush(last: Int) {
        if (head >= 0 && last > head) {
            val tail = rows[last]
            val end = HistoryUnknownRunEnd(HistoryCompactCodec.last(tail).tick, tail.run?.sequence ?: tail.sequence, count.toInt())
            merges += HistoryRunMerge(rows[head], rows.subList(head + 1, last + 1).toList(), end)
        }
        head = -1; count = 0
    }
    for (index in rows.indices) {
        val row = rows[index]
        val members = (row.run?.count ?: 1).toLong()
        if (row.sample.unknown == null) { flush(index - 1); continue }
        if (head >= 0) {
            val prev = rows[index - 1]
            val prevEndSequence = prev.run?.sequence ?: prev.sequence
            val joins = row.sequence > prevEndSequence && count + members <= Int.MAX_VALUE &&
                historyRunContinues(HistoryCompactCodec.last(prev), row.sample, breaks) && neighbours(index)
            if (joins) { count += members; continue }
            flush(index - 1)
        }
        head = index; count = members
    }
    flush(rows.lastIndex)
    return merges
}

/** What one ride's compaction found. */
internal sealed interface HistoryRideCompaction {
    data class Folded(val rowsRemoved: Long) : HistoryRideCompaction
    /** Deleted since the list was taken: nothing left to compact, so it does not hold the pass back. */
    data object Gone : HistoryRideCompaction
    /** RECORDING (left open by a killed process, or reopened): the writer's; the pass is not complete. */
    data object Recording : HistoryRideCompaction
}

/** Compacts one ride at a time; see the file comment. */
internal class HistoryRunCompactor(private val database: VoltyDatabase) {
    private val queries = database.rideHistoryCompactQueries

    /** Closed compact rides, by key. */
    fun closedRides(): List<Long> = queries.compactionRidesV3().executeAsList()

    /** Compact rides still RECORDING: not listed, and each keeps the pass from completing. */
    fun openRides(): Long = queries.compactionOpenRidesV3().executeAsOne()

    /**
     * Plans [rideNo] with reads only (no write lock), and opens ONE write transaction only when there is something to fold;
     * inside it the ride is re-checked and the plan redone, so a change between the two reads is never written over.
     * [inside] runs inside the write transaction after the writes, or after the read-only plan when there is nothing to
     * fold (a test's kill and race point).
     */
    fun compactRide(rideNo: Long, inside: (rideId: String) -> Unit = {},
        beforeWrite: (rideId: String) -> Unit = {}): HistoryRideCompaction {
        val first = plan(rideNo)
        if (first !is Plan.Ready) return (first as Plan.Skip).answer
        if (first.merges.isEmpty()) { inside(first.rideId); return HistoryRideCompaction.Folded(0) }
        // [beforeWrite]: a test's race point between the read-only plan and the write transaction.
        beforeWrite(first.rideId)
        return database.transactionWithResult {
            val again = plan(rideNo)
            if (again !is Plan.Ready) return@transactionWithResult (again as Plan.Skip).answer
            var removed = 0L
            for ((seriesId, merges) in again.merges) for (merge in merges) {
                merge.absorbed.forEach { queries.deleteSampleV3(seriesId, it.sample.tick.elapsedMillis, it.sequence) }
                val end = merge.end
                queries.extendRunV3(end.count.toLong(), end.tick.elapsedMillis, end.sequence,
                    HistoryCompactCodec.epochSkew(again.origin, end.tick), seriesId,
                    merge.head.sample.tick.elapsedMillis, merge.head.sequence)
                removed += merge.absorbed.size
            }
            inside(again.rideId)
            HistoryRideCompaction.Folded(removed)
        }
    }

    private sealed interface Plan {
        class Skip(val answer: HistoryRideCompaction) : Plan
        class Ready(val rideId: String, val origin: HistoryTick, val merges: Map<Long, List<HistoryRunMerge>>) : Plan
    }

    private fun plan(rideNo: Long): Plan {
        val ride = queries.compactionRideV3(rideNo).executeAsOneOrNull() ?: return Plan.Skip(HistoryRideCompaction.Gone)
        if (ride.status == HistoryRideStatus.RECORDING.name) return Plan.Skip(HistoryRideCompaction.Recording)
        val origin = HistoryTick(ride.startedEpochMillis, ride.startedElapsedMillis, ride.clockGeneration)
        val gapNames = HISTORY_GAP_MARKERS.map { it.name }.toSet()
        val markers = queries.compactionMarkersV3(ride.rideId).executeAsList().filter { it.kind in gapNames }
        val merges = LinkedHashMap<Long, List<HistoryRunMerge>>()
        for (series in queries.seriesOfRideV3(rideNo).executeAsList()) {
            val raw = queries.unknownRowsOfSeriesV3(series.seriesId, ::HistorySampleV3).executeAsList()
            if (raw.size < 2) continue
            val key = HistorySeriesKey(series.sourceId, series.metricId, series.cellIndex.takeIf { it >= 0 }?.toInt())
            val rows = raw.map { HistoryCompactCodec.decode(it, key, origin) }
            val breaks = markers.filter { it.sourceId == null || it.sourceId == series.sourceId }
                .map { it.elapsedMillis }.sorted().toLongArray()
            val found = planHistoryRunMerges(rows, breaks) { index -> adjacent(series.seriesId, raw[index - 1], raw[index]) }
            if (found.isNotEmpty()) merges[series.seriesId] = found
        }
        return Plan.Ready(ride.rideId, origin, merges)
    }

    /** Neighbours in reader order, both carrying a code this build knows (an unknown code never joins, as in the writer). */
    private fun adjacent(seriesId: Long, prev: HistorySampleV3, next: HistorySampleV3): Boolean {
        if (HistoryCompactCodec.reasonOrNull(checkNotNull(prev.unknownCode)) == null ||
            HistoryCompactCodec.reasonOrNull(checkNotNull(next.unknownCode)) == null) return false
        return queries.rowsBetweenV3(seriesId, prev.elapsedMillis, next.elapsedMillis, prev.sequence, next.sequence)
            .executeAsOne() == 0L
    }
}

/**
 * Runs [maintenancePlan] step by step on [dispatcher] once the database is Ready (VoltyApplication starts it after the
 * gate, through [runUntilSettled]).
 *
 * Compaction runs ONCE per schema version: [compactionDone] / [markCompactionDone] are the app preference
 * `history_compaction_done_schema`, set only when a pass ends [Status.COMPLETED]. The VACUUM plan is still derived on
 * every start (three pragmas), as ruled.
 *
 * A ride that fails is logged with its cause and skipped; the pass goes on and ends [Status.FAILED], so the flag stays
 * unset and the next start retries. A ride still RECORDING in the database (a killed process whose ride
 * `recoverInterrupted` has not closed yet), or a recording seen at a ride boundary, ends the pass [Status.DEFERRED].
 */
class HistoryStorageMaintenance(
    private val provider: VoltyDatabaseProvider,
    private val freeBytes: () -> Long,
    private val isRecording: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val compactionDone: suspend () -> Boolean = { false },
    private val markCompactionDone: suspend () -> Unit = {},
    private val log: (message: String, failure: Throwable?) -> Unit = { _, _ -> },
) {
    enum class Status { COMPLETED, DEFERRED, FAILED }

    /**
     * [stoppedByRecording]: a live recording was seen at a ride boundary (the pass can resume when it ends).
     * [openRides]: rides RECORDING in the database, not compactable until closed (picked up at a later start).
     * [skippedAsDone]: the flag said compaction already completed for this schema; only the VACUUM plan ran.
     */
    data class Outcome(val status: Status, val ridesExamined: Int, val rowsRemoved: Long, val vacuumed: Boolean,
        val stoppedByRecording: Boolean = false, val openRides: Long = 0, val failedRides: List<Long> = emptyList(),
        val vacuumFailed: Boolean = false, val skippedAsDone: Boolean = false)

    private val compactor = HistoryRunCompactor(provider.database)

    /** A test's kill and race point inside each ride's compaction. */
    internal var insideRideTransaction: (rideId: String) -> Unit = {}

    /**
     * [run], and while a pass is stopped by a live recording, [awaitIdle] (suspends until no ride is being recorded) and
     * run again. Any other outcome is final for this process; the next start plans again from the flag and the data.
     */
    suspend fun runUntilSettled(awaitIdle: suspend () -> Unit): Outcome {
        while (true) {
            val outcome = run()
            if (!outcome.stoppedByRecording) return outcome
            awaitIdle()
        }
    }

    suspend fun run(): Outcome = withContext(dispatcher) {
        val done = compactionDone()
        var left = if (done) emptyList() else compactor.closedRides()
        var examined = 0; var removed = 0L; var vacuumed = false; var vacuumFailed = false
        var stopped = false; var skippedOpen = 0L
        val failed = ArrayList<Long>()
        while (true) {
            val recording = isRecording()
            val pages = pages()
            val step = maintenancePlan(recording, pages.freelistBytes, pages.liveBytes, freeBytes(), left.size).firstOrNull()
            if (step == null) { stopped = recording && left.isNotEmpty(); break }
            when (step) {
                HistoryMaintenanceStep.Compact -> {
                    val rideNo = left.first()
                    try {
                        when (val result = compactor.compactRide(rideNo, insideRideTransaction)) {
                            is HistoryRideCompaction.Folded -> removed += result.rowsRemoved
                            HistoryRideCompaction.Gone -> Unit
                            HistoryRideCompaction.Recording -> skippedOpen++
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                    catch (failure: Exception) {
                        failed += rideNo
                        log("History compaction failed on ride key $rideNo; skipped, retried at the next start", failure)
                    }
                    left = left.drop(1); examined++
                    yield()
                }
                HistoryMaintenanceStep.Vacuum -> {
                    // VACUUM holds the write lock for the whole rewrite: ask once more right before it.
                    if (!isRecording()) {
                        // Outside any transaction (SQLite refuses VACUUM inside one).
                        try { provider.driver.execute(null, "VACUUM", 0); vacuumed = true; log("History database vacuumed", null) }
                        catch (failure: Exception) { vacuumFailed = true; log("VACUUM failed; retried at the next start", failure) }
                    }
                    break
                }
            }
        }
        val open = if (done) 0L else compactor.openRides().coerceAtLeast(skippedOpen)
        val status = when {
            done -> Status.COMPLETED
            failed.isNotEmpty() -> Status.FAILED
            stopped || open > 0 -> Status.DEFERRED
            else -> Status.COMPLETED
        }
        if (!done && status == Status.COMPLETED) markCompactionDone()
        if (removed > 0) log("History compaction folded $removed rows in $examined rides", null)
        Outcome(status, examined, removed, vacuumed, stopped, open, failed, vacuumFailed, skippedAsDone = done)
    }

    private class Pages(val freelistBytes: Long, val liveBytes: Long)

    private fun pages(): Pages {
        fun pragma(name: String) = provider.driver.executeQuery(null, "PRAGMA $name",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) }, 0).value
        val size = pragma("page_size")
        val count = pragma("page_count")
        val free = pragma("freelist_count")
        return Pages(free * size, (count - free) * size)
    }
}

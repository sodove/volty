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
 * rider's phone this class is a no-op that reads the unknown rows once per app start (33 ms warm on a PC for the whole
 * file; a full scan of each series' key range, no writes), kept because it is the only thing that would repair a chain of
 * lone rows if any producer ever leaves one, and because it is cheap.
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
 * One short transaction per ride (its unknown rows, a neighbour probe per candidate pair, an UPDATE of each head and a
 * DELETE of each absorbed row), re-checking inside it that the ride still exists and is not RECORDING. A kill loses at most
 * the current ride's work and leaves it exactly as it was; a compacted ride has nothing left to fold, so a re-run is a
 * no-op. Between rides the pass yields and asks again whether a ride is being recorded.
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

/** Compacts one ride at a time; see the file comment. */
internal class HistoryRunCompactor(private val database: VoltyDatabase) {
    private val queries = database.rideHistoryCompactQueries

    /** Closed compact rides, by key. */
    fun closedRides(): List<Long> = queries.compactionRidesV3().executeAsList()

    /**
     * Folds every chain of [rideNo] in ONE transaction and returns the number of rows removed (0 for a ride that is gone,
     * RECORDING, or already compact). [inside] runs in the transaction after the writes (a test's kill point).
     */
    fun compactRide(rideNo: Long, inside: (rideId: String) -> Unit = {}): Long = database.transactionWithResult {
        val ride = queries.compactionRideV3(rideNo).executeAsOneOrNull() ?: return@transactionWithResult 0L
        if (ride.status == HistoryRideStatus.RECORDING.name) return@transactionWithResult 0L
        val origin = HistoryTick(ride.startedEpochMillis, ride.startedElapsedMillis, ride.clockGeneration)
        val gapNames = HISTORY_GAP_MARKERS.map { it.name }.toSet()
        val markers = queries.compactionMarkersV3(ride.rideId).executeAsList().filter { it.kind in gapNames }
        var removed = 0L
        for (series in queries.seriesOfRideV3(rideNo).executeAsList()) {
            val raw = queries.unknownRowsOfSeriesV3(series.seriesId, ::HistorySampleV3).executeAsList()
            if (raw.size < 2) continue
            val key = HistorySeriesKey(series.sourceId, series.metricId, series.cellIndex.takeIf { it >= 0 }?.toInt())
            val rows = raw.map { HistoryCompactCodec.decode(it, key, origin) }
            val breaks = markers.filter { it.sourceId == null || it.sourceId == series.sourceId }
                .map { it.elapsedMillis }.sorted().toLongArray()
            val merges = planHistoryRunMerges(rows, breaks) { index -> adjacent(series.seriesId, raw[index - 1], raw[index]) }
            for (merge in merges) {
                merge.absorbed.forEach {
                    queries.deleteSampleV3(series.seriesId, it.sample.tick.elapsedMillis, it.sequence)
                }
                val end = merge.end
                queries.extendRunV3(end.count.toLong(), end.tick.elapsedMillis, end.sequence,
                    HistoryCompactCodec.epochSkew(origin, end.tick), series.seriesId,
                    merge.head.sample.tick.elapsedMillis, merge.head.sequence)
                removed += merge.absorbed.size
            }
        }
        inside(ride.rideId)
        removed
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
 * gate). A failure is logged and the pass stops; the next app start plans again from the data, which is the retry.
 */
class HistoryStorageMaintenance(
    private val provider: VoltyDatabaseProvider,
    private val freeBytes: () -> Long,
    private val isRecording: suspend () -> Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val log: (message: String, failure: Throwable?) -> Unit = { _, _ -> },
) {
    data class Outcome(val ridesExamined: Int, val rowsRemoved: Long, val vacuumed: Boolean, val stoppedByRecording: Boolean)

    private val compactor = HistoryRunCompactor(provider.database)

    /** A test's kill point inside each ride's transaction. */
    internal var insideRideTransaction: (rideId: String) -> Unit = {}

    suspend fun run(): Outcome = withContext(dispatcher) {
        var left = compactor.closedRides()
        var examined = 0; var removed = 0L; var vacuumed = false; var stopped = false
        while (true) {
            val recording = isRecording()
            val pages = pages()
            val step = maintenancePlan(recording, pages.freelistBytes, pages.liveBytes, freeBytes(), left.size).firstOrNull()
            if (step == null) { stopped = recording; break }
            when (step) {
                HistoryMaintenanceStep.Compact -> {
                    try { removed += compactor.compactRide(left.first(), insideRideTransaction) }
                    catch (failure: Exception) {
                        log("History compaction stopped; the next start resumes it", failure)
                        return@withContext Outcome(examined, removed, vacuumed, stoppedByRecording = false)
                    }
                    left = left.drop(1); examined++
                    yield()
                }
                HistoryMaintenanceStep.Vacuum -> {
                    // Outside any transaction (SQLite refuses VACUUM inside one).
                    try { provider.driver.execute(null, "VACUUM", 0); vacuumed = true; log("History database vacuumed", null) }
                    catch (failure: Exception) { log("VACUUM failed; retried at the next start", failure) }
                    break
                }
            }
        }
        if (removed > 0) log("History compaction folded $removed rows in $examined rides", null)
        Outcome(examined, removed, vacuumed, stopped)
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

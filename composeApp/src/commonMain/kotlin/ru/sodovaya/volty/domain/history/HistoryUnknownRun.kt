package ru.sodovaya.volty.domain.history

/**
 * Unknown readings stored as RUNS (compact-history-storage plan, Task 3). This file is the pure model and holds no
 * storage; Tasks 4 to 6 persist and read it.
 *
 * A run is a maximal chain of consecutive unknown samples of ONE series that [historyRunContinues] joins. It keeps its
 * first sample, its last tick and sequence, and its member count. The exact tick of each INTERIOR member is not kept
 * (the rider accepted that on 2026-10-29). The measured case is a dead series on a Begode T4: 2,752 UNAVAILABLE rows in
 * 856 s become one row.
 *
 * ## Rule 2: where a run must split
 * A step from `prev` to `next` continues a run only when ALL hold, and any failure starts a new run:
 *  - both are unknown with the SAME reason (a changed reason is information: PARTIAL_SOURCE feeds the controller-power
 *    bridge, the others do not);
 *  - no clock boundary ([clockBoundary]: another generation, a backwards step, or wall time that drifted more than a
 *    second from elapsed time) and an epoch skew (`epoch - elapsed`) that moved by at most
 *    [HISTORY_RUN_SKEW_TOLERANCE_MILLIS] since the previous member. EXACT skew was the first rule and it was wrong; see
 *    "Skew tolerance" below;
 *  - `0 <= next.elapsed - prev.elapsed <= HISTORY_MAX_INTERVAL_MILLIS`;
 *  - no gap marker (a kind in [HISTORY_GAP_MARKERS], global or scoped to this series' source) lies in
 *    `(prev.elapsed, next.elapsed]`.
 * A known row is never part of a run, so a chain ends at it. The fingerprint tool's collapsed form (Task 2) calls this
 * very function; there is one definition.
 *
 * The marker window is closed on the right although [HistoryBrokenAccumulator] looks strictly inside `(prev, next)`.
 * That is deliberately one-sided safe: it splits a run in a case the accumulator would not have, and a split run
 * expands to the same two neighbouring samples the full series holds.
 *
 * ## Skew tolerance (measured, retraction)
 * `HistoryRecordingClock` reads the wall clock and the monotonic clock separately, so `epoch - elapsed` wobbles by a
 * millisecond or so. Measured read-only on the rider's database (518,192 unknown rows, 2026-10-29): the per-step change
 * of the skew inside an unknown chain was 0 for 62.9 % of steps, 1 ms for 36.5 %, <= 2 ms for 99.98 %, and never more
 * than 14 ms (the drift from a chain's first row was also at most 14 ms). Splitting on EXACT skew gave 192,469 runs for
 * those rows; tolerating the wobble gives 375 chains (tolerance 1 ms: 709, 3 ms: 417, 5 ms: 405, 10 ms: 390, and 375 from
 * 14 ms up). The constant is 15 ms, the smallest round value above the measured maximum; a real wall-clock step (NTP
 * jump, user change) is seconds and still splits, and `clockBoundary` catches anything over 1 s per step.
 *
 * ## Per-reader proofs
 * [expandForReaders] gives a reader exactly `count` samples for a run: its first, its end, and `count - 2` evenly spaced
 * samples between them (elapsed and epoch interpolated linearly). For every reader below,
 * `reader(full) == reader(expandForReaders(collapseUnknownRuns(full)))` is tested in `HistoryUnknownRunEquivalenceTest`.
 * Inside a run, every step is unknown to unknown, within the interval limit, with no marker and no clock boundary.
 *
 *  - `HistoryBrokenAccumulator.brokenMillis`: each interior step is unknown and not genuine, so it adds its delta to
 *    `brokenMillis` only. The interior deltas sum to `end - first`, which is the one step the expanded pair adds.
 *    `lastElapsed` is the run end in both forms, so the ride-end tail rule sees the same last tick and
 *    `partial(rideEnd)` is equal.
 *  - `HistoryContinuityIndex.breakTick` treats any unknown as a break, so interior unknowns draw no point, and the
 *    break entering and the break leaving the stretch lie on retained samples. Energy integration (`historyStats`)
 *    breaks at every unknown, so it is equal.
 *  - `HistoryPartialSourceBridge`: a PARTIAL_SOURCE unknown is dropped when it is within 2000 ms of the previous known
 *    value and that known value is within 2000 ms of the next one. The END sample is the latest unknown, so if it is
 *    close, every earlier member was; the bridged stretch disappears in both forms, and a stretch that is not bridged
 *    is kept in both (the surviving unknowns differ only in interior members, which no reader distinguishes).
 *  - Distance (`CounterStepGate` runs), moving time without GPS, battery used, robust peaks and `hasKnownValue` read
 *    known values, or end a run at an unknown, and known rows are untouched. The logui export writes nothing for an
 *    unknown. A run never spans a known row.
 *  - `rangeDetailStats.unknownCount` is compared only with zero; a run row counts at least 1.
 *  - [nearestHistorySample] (chart cursor): any cursor tick inside `[first, end]` of a run is nearer to the run's first
 *    or end sample than to any known row outside it, and an unknown wins a tie, so the answer is `null` as it was.
 *    An earlier revision of the plan expected the new nearest to be a known value up to `maxAge` away and prescribed a
 *    `runCovers(tick)` probe; the test sweeps every tick and finds none, because the endpoints are kept.
 *  - `HistoryOverview`: point selection marks unknowns and their neighbours mandatory and buckets by index. Because the
 *    expansion has the same row count at the same positions, the budget decision and the chosen known points are equal
 *    within and above the budget (an earlier revision called the over-budget difference accepted; it is gone).
 *
 * ## Why exactly `count` samples (retraction)
 * Two earlier revisions were wrong. Expanding to first and end only made the pair one step as long as the whole run, so
 * `gapMillis` and moving time with GPS changed. Expanding to fillers at most 2000 ms apart fixed those but not the
 * readers that count INDICES: `historyNeedleIndices` takes its noise median over the nearest 8 steps by array index, and
 * unknown steps are skipped, so the number of rows a run occupies decides how far the window reaches into known data
 * (a reviewer's fixture turned a tile from 10 into 13). The overview's mandatory-point budget and bucket choice are
 * index based too. A run therefore keeps its member count, and the expansion has the same number of rows at the same
 * positions relative to every known row. The spacing is at most 2000 ms because every step of the original chain was
 * (a larger step splits the run), so `span <= 2000 * (count - 1)`; [expandForReaders] checks that and fails loudly on a
 * stored row that violates it (a corrupt run), instead of feeding a reader a gap that never existed.
 *
 * ## Epoch of an interior sample
 * Interior samples are synthesised, so their wall epoch is interpolated. No history reader looks at the epoch of a
 * sample except through [historyAxisEpochMillis] (origin plus elapsed, which ignores it) and [clockBoundary] (the
 * interpolated skew moves by less than the 15 ms per step the run allowed, far below its 1 s tolerance). The one other use,
 * the chart cursor's fallback when a series has no origin, reads a tick the cursor picked.
 *
 * ## A range that cuts a run
 * Readers are range-bound. A range that starts or ends inside a run reads the EXPANDED samples and clips them; the first
 * or last in-range member then differs from the original by less than one inter-sample step. This can move `brokenMillis`
 * of a range by up to one step per cut edge. A WHOLE-ride read never cuts a run and is exact. Tested in
 * `aRangeCuttingARunShiftsAReaderByLessThanOneStepPerEdge`.
 */
data class HistoryUnknownRunEnd(val tick: HistoryTick, val sequence: Long, val count: Int) {
    init { require(count >= 1) }
}

/** The most the epoch skew may move between two members of one run; measured, see "Skew tolerance". */
const val HISTORY_RUN_SKEW_TOLERANCE_MILLIS = 15L

/** [run] is null for a known sample and for a lone unknown. A run's [sample] is its first member. */
data class HistoryStoredSample(val sample: HistorySample, val sequence: Long, val run: HistoryUnknownRunEnd? = null)

/** The sorted elapsed ticks of every marker that interrupts a series of [sourceId]. */
internal fun historyRunBreakTicks(markers: List<HistoryMarker>, sourceId: String): LongArray =
    markers.filter { it.kind in HISTORY_GAP_MARKERS && (it.sourceId == null || it.sourceId == sourceId) }
        .map { it.tick.elapsedMillis }.sorted().toLongArray()

internal fun historyRunContinues(prev: HistorySample, next: HistorySample, breaks: LongArray,
    maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): Boolean {
    if (prev.unknown == null || next.unknown == null || prev.unknown != next.unknown) return false
    if (prev.tick.clockGeneration != next.tick.clockGeneration || clockBoundary(prev.tick, next.tick)) return false
    val skewMove = (next.tick.epochMillis - next.tick.elapsedMillis) - (prev.tick.epochMillis - prev.tick.elapsedMillis)
    if (kotlin.math.abs(skewMove) > HISTORY_RUN_SKEW_TOLERANCE_MILLIS) return false
    val step = next.tick.elapsedMillis - prev.tick.elapsedMillis
    if (step !in 0..maxIntervalMillis) return false
    // First break strictly after prev; it splits the run when it is not after next.
    var low = 0; var high = breaks.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (breaks[mid] <= prev.tick.elapsedMillis) low = mid + 1 else high = mid
    }
    return !(low < breaks.size && breaks[low] <= next.tick.elapsedMillis)
}

/** Rule 2 for one step of one series; [markers] are all the ride's markers. */
fun historyRunContinues(prev: HistorySample, next: HistorySample, markers: List<HistoryMarker>): Boolean =
    historyRunContinues(prev, next, historyRunBreakTicks(markers, prev.key.sourceId))

/**
 * Folds the unknown chains of ONE series (ordered as the store pages it) into runs. [HistoryStoredSample.sequence] is the
 * index in [samples]. Known samples and lone unknowns pass through unchanged.
 */
fun collapseUnknownRuns(samples: List<HistorySample>, markers: List<HistoryMarker>,
    maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): List<HistoryStoredSample> {
    requireSingleHistoryKey(samples)
    val breaks = historyRunBreakTicks(markers, samples.firstOrNull()?.key?.sourceId ?: return emptyList())
    val result = ArrayList<HistoryStoredSample>()
    var first = -1
    var last = -1
    fun flush() {
        if (first < 0) return
        val count = last - first + 1
        val end = if (count >= 2) HistoryUnknownRunEnd(samples[last].tick, last.toLong(), count) else null
        result += HistoryStoredSample(samples[first], first.toLong(), end)
        first = -1; last = -1
    }
    samples.forEachIndexed { index, sample ->
        if (sample.unknown == null) { flush(); result += HistoryStoredSample(sample, index.toLong()); return@forEachIndexed }
        if (first < 0 || !historyRunContinues(samples[last], sample, breaks, maxIntervalMillis)) { flush(); first = index }
        last = index
    }
    flush()
    return result
}

/**
 * What a reader that needs ticks is given: for a run exactly `count` samples, the first, `count - 2` evenly spaced
 * between, and the end. Never stored. Fails (`IllegalArgumentException`) when a stored run could not have come from a
 * chain of steps within [maxIntervalMillis]: end before first, another clock generation, or a span over
 * `maxIntervalMillis * (count - 1)`.
 */
fun expandForReaders(stored: List<HistoryStoredSample>, maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): List<HistorySample> = buildList {
    for (row in stored) {
        val members = HistoryRunMembers(row, maxIntervalMillis)
        for (index in 0 until members.size) add(members.sample(index))
    }
}

/**
 * The members of one stored row exactly as [expandForReaders] gives them, addressable by index, so a reader can start
 * in the middle of a run without expanding it from its first member (the `samples` pager, Task 6). [size] is 1 for a
 * known row and a lone unknown, else the run's count.
 *
 * [sequence] of an interior member is interpolated between the first and the end sequence the way its tick is
 * (`first + round(spread * index / (count - 1))`, as `splitStoredRunAt` cuts), so a page cursor can stand on any member:
 * `(elapsed, sequence)` strictly increases along a run whenever its sequences are distinct integers
 * (`end - first >= count - 1`, [hasDistinctSequences]). These sequences are not stored ones and may coincide with
 * another series' rows: nothing may order ACROSS series by them.
 */
class HistoryRunMembers(val row: HistoryStoredSample, maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS) {
    private val end = row.run?.takeIf { it.count >= 2 }
    val size: Int = end?.count ?: 1
    private val parts = (size - 1).toLong()

    init {
        if (end != null) {
            val first = row.sample.tick
            val span = end.tick.elapsedMillis - first.elapsedMillis
            require(span >= 0 && end.tick.clockGeneration == first.clockGeneration &&
                span <= maxIntervalMillis * (end.count - 1)) { "A stored unknown run is not a chain of steps within the interval limit" }
        }
    }

    /** True when every member gets its own sequence, i.e. a cursor on a member is unambiguous. */
    val hasDistinctSequences: Boolean get() = end == null || end.sequence - row.sequence >= parts

    fun tick(index: Int): HistoryTick {
        require(index in 0 until size)
        val run = end ?: return row.sample.tick
        if (index == 0) return row.sample.tick
        if (index == size - 1) return run.tick
        val first = row.sample.tick
        val elapsed = first.elapsedMillis + ((run.tick.elapsedMillis - first.elapsedMillis) * index + parts / 2).floorDiv(parts)
        val epoch = first.epochMillis + ((run.tick.epochMillis - first.epochMillis) * index + parts / 2).floorDiv(parts)
        return HistoryTick(epoch, elapsed, first.clockGeneration)
    }

    fun elapsed(index: Int): Long = tick(index).elapsedMillis

    fun sequence(index: Int): Long {
        require(index in 0 until size)
        val run = end ?: return row.sequence
        return row.sequence + ((run.sequence - row.sequence) * index + parts / 2).floorDiv(parts)
    }

    fun sample(index: Int): HistorySample =
        if (index == 0) row.sample else HistorySample(tick(index), row.sample.key, null, row.sample.unknown)

    /** The first index at or after [from] for which [predicate] holds, [predicate] being monotone (false, then true); else [size]. */
    fun firstIndex(from: Int = 0, predicate: (Int) -> Boolean): Int {
        var low = from; var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (predicate(mid)) high = mid else low = mid + 1
        }
        return low
    }

    /** The last index whose elapsed is at most [elapsed], or -1. */
    fun lastIndexAtOrBefore(elapsed: Long): Int = firstIndex { elapsed(it) > elapsed } - 1
}

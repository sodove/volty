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
 *  - the same clock generation and the same epoch skew (`epoch - elapsed`), so a clock jump is never folded away;
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
 * ## Per-reader proofs
 * [expandForReaders] gives a reader a run's FIRST sample, plus its END sample when `count >= 2`. For every reader below,
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
 *  - `HistoryOverview`: the one accepted difference. Point selection marks unknowns and their neighbours mandatory, so
 *    dropping interior unknowns frees budget. Within the budget the drawn known points and the break stretches are
 *    equal. Above it the expanded series may choose more real points; none is invented.
 *
 * ## Fillers (retraction)
 * An earlier revision expanded a run to just its first and end samples. That was wrong: the pair is one step as long as
 * the whole run, so `HistoryBrokenAccumulator` called it a genuine gap (`gapMillis`, hence the walk's `hasGap`, differed)
 * and `historyMovingMillis` met a pair more than 5 s apart and credited GPS-moving time. Both answers changed.
 * [expandForReaders] therefore inserts in-memory FILLER unknowns, at most [HISTORY_MAX_INTERVAL_MILLIS] apart, between
 * the first and the end sample (same reason, clock generation and epoch skew). Storage stays runs. Every step is then
 * unknown to unknown within the interval limit, exactly the shape of the full series, so each reader above is equal,
 * including `gapMillis` and moving time with GPS. A run no longer than the limit adds no filler.
 */
data class HistoryUnknownRunEnd(val tick: HistoryTick, val sequence: Long, val count: Int) {
    init { require(count >= 1) }
}

/** [run] is null for a known sample and for a lone unknown. A run's [sample] is its first member. */
data class HistoryStoredSample(val sample: HistorySample, val sequence: Long, val run: HistoryUnknownRunEnd? = null)

/** The sorted elapsed ticks of every marker that interrupts a series of [sourceId]. */
internal fun historyRunBreakTicks(markers: List<HistoryMarker>, sourceId: String): LongArray =
    markers.filter { it.kind in HISTORY_GAP_MARKERS && (it.sourceId == null || it.sourceId == sourceId) }
        .map { it.tick.elapsedMillis }.sorted().toLongArray()

internal fun historyRunContinues(prev: HistorySample, next: HistorySample, breaks: LongArray,
    maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): Boolean {
    if (prev.unknown == null || next.unknown == null || prev.unknown != next.unknown) return false
    if (prev.tick.clockGeneration != next.tick.clockGeneration) return false
    if (prev.tick.epochMillis - prev.tick.elapsedMillis != next.tick.epochMillis - next.tick.elapsedMillis) return false
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
 * What a reader that needs ticks is given: a run's first sample, fillers at most [maxIntervalMillis] apart, and its end
 * sample when it has two or more members. Fillers are never stored; see the class comment.
 */
fun expandForReaders(stored: List<HistoryStoredSample>, maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS): List<HistorySample> = buildList {
    for (row in stored) {
        add(row.sample)
        val end = row.run ?: continue
        if (end.count < 2) continue
        val first = row.sample.tick
        var elapsed = first.elapsedMillis + maxIntervalMillis
        while (elapsed < end.tick.elapsedMillis) {
            add(HistorySample(HistoryTick(first.epochMillis + (elapsed - first.elapsedMillis), elapsed, first.clockGeneration),
                row.sample.key, null, row.sample.unknown))
            elapsed += maxIntervalMillis
        }
        add(HistorySample(end.tick, row.sample.key, null, row.sample.unknown))
    }
}

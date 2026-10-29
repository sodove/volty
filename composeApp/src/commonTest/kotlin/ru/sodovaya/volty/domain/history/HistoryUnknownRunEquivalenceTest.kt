package ru.sodovaya.volty.domain.history

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import ru.sodovaya.volty.data.history.StreamingHistoryExportEncoder
import ru.sodovaya.volty.domain.plausibility.historyRobustPeak
import ru.sodovaya.volty.presentation.history.historyBatteryUsed
import ru.sodovaya.volty.presentation.history.historyMovingMillis
import ru.sodovaya.volty.presentation.history.historyRideWalk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compare `reader(full)` with `reader(expandForReaders(collapseUnknownRuns(full)))` for every reader that sees a series
 * of unknowns (compact-history-storage plan, Task 3). The fixtures are built to stress run boundaries, several of them
 * deliberately incoherent (a run that crosses a clock generation, a reason that changes mid-chain).
 *
 * Two readers do NOT agree and the tests pin the difference instead of hiding it: `gapMillis` of the broken-time
 * accumulator, and moving time when GPS is supplied. See the KDoc of [HistoryUnknownRunEnd].
 */
class HistoryUnknownRunEquivalenceTest {
    private val key = HistorySeriesKey("bms", "current_a")
    private val powerKey = HistorySeriesKey("ctl", "controller_power_w")
    private val dead = HistoryUnknownReason.UNAVAILABLE
    private val partialSource = HistoryUnknownReason.PARTIAL_SOURCE

    private fun tick(elapsed: Long, generation: Long = 1) = HistoryTick(1_000_000 + elapsed, elapsed, generation)
    private fun known(elapsed: Long, value: Double, k: HistorySeriesKey = key, generation: Long = 1) =
        HistorySample(tick(elapsed, generation), k, value)
    private fun unknown(elapsed: Long, reason: HistoryUnknownReason = dead, k: HistorySeriesKey = key, generation: Long = 1) =
        HistorySample(tick(elapsed, generation), k, null, reason)
    private fun gap(elapsed: Long, source: String? = null, kind: HistoryMarkerKind = HistoryMarkerKind.BLE_GAP) =
        HistoryMarker(tick(elapsed), kind, source)

    private fun viaRuns(full: List<HistorySample>, markers: List<HistoryMarker> = emptyList()) =
        expandForReaders(collapseUnknownRuns(full, markers))

    private fun runsOf(full: List<HistorySample>, markers: List<HistoryMarker> = emptyList()) =
        collapseUnknownRuns(full, markers).filter { it.run != null }

    /** Unknowns at `from, from + step, ...` up to and including `to`. */
    private fun unknownsBetween(from: Long, to: Long, step: Long = 300, reason: HistoryUnknownReason = dead,
        k: HistorySeriesKey = key) = generateSequence(from) { it + step }.takeWhile { it <= to }.map { unknown(it, reason, k) }.toList()

    private fun accumulate(samples: List<HistorySample>, markers: List<HistoryMarker> = emptyList()) =
        HistoryBrokenAccumulator(markers).also { acc -> samples.forEach(acc::offer) }

    // ---- the broken-time accumulator and the walk's tail rule ----------------------------------------------------

    @Test fun brokenAccumulatorInvariantOverDeadSeries() {
        var at = 0L
        val full = List(2752) { index -> unknown(at).also { at += 279 + (index * 13L) % 45 } }
        assertTrue(full.zipWithNext().all { (a, b) -> b.tick.elapsedMillis - a.tick.elapsedMillis in 279..323 })
        val stored = collapseUnknownRuns(full, emptyList())
        assertEquals(1, stored.size, "a dead series is one run")
        assertEquals(2752, stored.single().run?.count)
        val expanded = expandForReaders(stored)
        assertTrue(expanded.size > 2 && expanded.first() == full.first() && expanded.last() == full.last())

        val before = accumulate(full)
        val after = accumulate(expanded)
        assertEquals(before.brokenMillis, after.brokenMillis)
        assertEquals(before.lastElapsed, after.lastElapsed)
        assertEquals(true, before.partial(856_000))
        assertEquals(before.partial(856_000), after.partial(856_000))
        assertEquals(0L, before.gapMillis)
        assertEquals(before.gapMillis, after.gapMillis)
    }

    @Test fun gapMillisIsEqualForARunWithinTheIntervalLimit() {
        val full = listOf(known(0, 1.0)) + unknownsBetween(300, 1500) + known(1800, 2.0)
        val after = accumulate(viaRuns(full))
        val before = accumulate(full)
        assertEquals(1, runsOf(full).size)
        assertEquals(before.brokenMillis, after.brokenMillis)
        assertEquals(0L, before.gapMillis)
        assertEquals(before.gapMillis, after.gapMillis)
    }

    /**
     * A 4.5 s run inside a tolerance-exact series: with only first and end the expanded pair was a "genuine gap" and
     * `hasGap` flipped. Fillers keep every step within the limit, so `gapMillis` is equal.
     */
    @Test fun gapMillisIsEqualForRunsLongerThanTheInterval() {
        val full = (0..333L).map { known(it * 300, 10.0 + it % 5) } +   // 0..99.9 s known
            unknownsBetween(100_200, 104_700) +                          // a 4.5 s unknown run
            (0..333L).map { known(105_000 + it * 300, 10.0 + it % 5) }
        val rideEnd = full.last().tick.elapsedMillis
        val before = accumulate(full)
        val after = accumulate(viaRuns(full))
        assertEquals(1, runsOf(full).size)
        assertEquals(before.brokenMillis, after.brokenMillis)
        assertEquals(false, before.partial(rideEnd), "tolerated: well under max(2 %, 10 s)")
        assertEquals(before.partial(rideEnd), after.partial(rideEnd))
        assertEquals(0L, before.gapMillis)
        assertEquals(before.gapMillis, after.gapMillis)
        assertEquals(before.gapMillis > 0, after.gapMillis > 0, "the walk's hasGap")
    }

    @Test fun fillersNeverExceedTheIntervalLimitAndShortRunsAddNone() {
        // A 4.5 s run: first, two fillers, end.
        val run = unknownsBetween(100, 4_600)
        val expanded = viaRuns(run)
        assertEquals(listOf(100L, 2_100L, 4_100L, 4_600L), expanded.map { it.tick.elapsedMillis })
        assertTrue(expanded.zipWithNext().all { (a, b) -> b.tick.elapsedMillis - a.tick.elapsedMillis in 1..HISTORY_MAX_INTERVAL_MILLIS })
        assertTrue(expanded.all { it.unknown == dead && it.tick.clockGeneration == 1L && !clockBoundary(expanded.first().tick, it.tick) })
        // Exactly the limit, and shorter: no filler.
        assertEquals(listOf(0L, 2_000L), viaRuns(unknownsBetween(0, 2_000, step = 1_000)).map { it.tick.elapsedMillis })
        assertEquals(listOf(0L, 600L), viaRuns(unknownsBetween(0, 600)).map { it.tick.elapsedMillis })
        // Over a whole dead series the spacing holds, the first and end are exact and the reason survives.
        var at = 0L
        val dead2752 = List(2752) { index -> unknown(at).also { at += 279 + (index * 13L) % 45 } }
        val big = viaRuns(dead2752)
        assertEquals(dead2752.first(), big.first()); assertEquals(dead2752.last(), big.last())
        assertTrue(big.zipWithNext().all { (a, b) -> b.tick.elapsedMillis - a.tick.elapsedMillis in 1..HISTORY_MAX_INTERVAL_MILLIS })
        // Fillers keep a run's epoch skew and reason (a mixed-reason pair of runs).
        val mixed = unknownsBetween(0, 5_000, step = 500, reason = partialSource) + unknownsBetween(5_500, 9_000, step = 500)
        val mixedOut = viaRuns(mixed)
        assertTrue(mixedOut.all { it.tick.epochMillis - it.tick.elapsedMillis == 1_000_000L })
        assertEquals(listOf(partialSource, dead), mixedOut.map { it.unknown }.distinct())
    }

    @Test fun brokenAccumulatorKeepsGapInsideUnknowns() {
        val full = listOf(unknown(0), unknown(300), unknown(2600))
        val runs = collapseUnknownRuns(full, emptyList())
        assertEquals(2, runs.size, "the 2300 ms step splits")
        assertEquals(2, runs[0].run?.count)
        assertEquals(null, runs[1].run)
        assertEquals(full, expandForReaders(runs))
        val before = accumulate(full); val after = accumulate(expandForReaders(runs))
        assertEquals(2300L, before.gapMillis)
        assertEquals(before.gapMillis, after.gapMillis)
        assertEquals(before.brokenMillis, after.brokenMillis)
    }

    @Test fun tailRuleSeesRunEnd() {
        // Known over 0-60 s, unknown 60.3 s to 1200 s, a BLE_GAP at 1200 s, ride end 3600 s.
        val known = (0..200L).map { known(it * 300, 5.0) }
        val unknowns = unknownsBetween(60_300, 1_200_000)
        val full = known + unknowns
        val bleGap = listOf(gap(1_200_000))
        val before = accumulate(full, bleGap)
        val after = accumulate(viaRuns(full, bleGap), bleGap)
        assertEquals(1_200_000L, before.lastElapsed)
        assertEquals(before.lastElapsed, after.lastElapsed)
        assertEquals(before.brokenMillis, after.brokenMillis)
        assertEquals(true, before.partial(3_600_000))
        assertEquals(before.partial(3_600_000), after.partial(3_600_000))
        // A marker AFTER the last sample keeps the whole stretch one run, the shape the brief describes.
        val late = listOf(gap(1_200_100))
        assertEquals(1, runsOf(full, late).size)
        assertEquals(accumulate(full, late).partial(3_600_000), accumulate(viaRuns(full, late), late).partial(3_600_000))
        assertEquals(accumulate(full, late).lastElapsed, accumulate(viaRuns(full, late), late).lastElapsed)
        // lastElapsedOfOtherSources: the newest tick of a series is its run end in both forms.
        assertEquals(full.maxOf { it.tick.elapsedMillis }, viaRuns(full, late).maxOf { it.tick.elapsedMillis })
    }

    @Test fun unknownEvidenceKeepsTheRideAliveEvenWithoutAnyKnownTail() {
        // A series that only ever reports unknowns still proves the ride ran to its last tick.
        val full = unknownsBetween(0, 600_000)
        val before = accumulate(full); val after = accumulate(viaRuns(full))
        assertEquals(before.lastElapsed, after.lastElapsed)
        assertEquals(true, before.partial(600_000))
        assertEquals(before.partial(600_000), after.partial(600_000))
        assertEquals(before.partial(900_000), after.partial(900_000))
    }

    // ---- where a run splits ------------------------------------------------------------------------------------

    @Test fun markerInsideChainSplitsRun() {
        val full = listOf(known(0, 5.0)) + unknownsBetween(300, 1800) + known(2100, 6.0)
        val scoped = listOf(gap(750, source = "bms"))
        val runs = runsOf(full, scoped)
        assertEquals(listOf(2, 4), runs.map { it.run!!.count })
        assertEquals(listOf(300L, 900L), runs.map { it.sample.tick.elapsedMillis })
        val expanded = viaRuns(full, scoped)
        // First, end, and (for the 900..1800 run, 900 ms) no filler.
        assertEquals(listOf(0L, 300L, 600L, 900L, 1800L, 2100L), expanded.map { it.tick.elapsedMillis })

        val index = HistoryContinuityIndex(scoped)
        fun breaks(samples: List<HistorySample>) = samples.zipWithNext().mapNotNull { (a, b) -> index.breakTick(a, b) }
        val survivors = expanded.map { it.tick }.toSet()
        assertEquals(breaks(full).filter { it in survivors }, breaks(expanded))

        // A marker scoped to ANOTHER source does not split, and neither does a GPS_GAP.
        assertEquals(listOf(6), runsOf(full, listOf(gap(750, source = "pack"))).map { it.run!!.count })
        assertEquals(listOf(6), runsOf(full, listOf(gap(750, kind = HistoryMarkerKind.GPS_GAP))).map { it.run!!.count })
        // A marker exactly at the next tick splits (the window is closed on the right).
        assertEquals(listOf(2, 4), runsOf(full, listOf(gap(900))).map { it.run!!.count })
    }

    @Test fun incoherentRunAcrossGenerationIsSplit() {
        val two = listOf(unknown(0, generation = 3), unknown(300, generation = 4))
        val stored = collapseUnknownRuns(two, emptyList())
        assertEquals(2, stored.size)
        assertTrue(stored.all { it.run == null })
        // Three and three: each generation keeps its own run, so a clock boundary is never folded.
        val six = (0L..2L).map { unknown(it * 300, generation = 3) } + (3L..5L).map { unknown(it * 300, generation = 4) }
        val runs = collapseUnknownRuns(six, emptyList())
        assertEquals(listOf(3, 3), runs.map { it.run!!.count })
        assertEquals(listOf(0L, 600L, 900L, 1500L), expandForReaders(runs).map { it.tick.elapsedMillis })
        assertEquals(listOf(3L, 3L, 4L, 4L), expandForReaders(runs).map { it.tick.clockGeneration })
        assertEquals(accumulate(six).brokenMillis, accumulate(expandForReaders(runs)).brokenMillis)
    }

    @Test fun incoherentReasonChangeSplits() {
        val full = listOf(unknown(0, dead), unknown(300, partialSource))
        val stored = collapseUnknownRuns(full, emptyList())
        assertEquals(2, stored.size)
        assertEquals(full, expandForReaders(stored))
        assertEquals(listOf(dead, partialSource), expandForReaders(stored).map { it.unknown })
        // Mid-chain: the reason change cuts a long chain in two and each half keeps its own reason.
        val mixed = unknownsBetween(0, 900, reason = dead) + unknownsBetween(1200, 2100, reason = partialSource)
        val runs = collapseUnknownRuns(mixed, emptyList())
        assertEquals(listOf(dead, partialSource), runs.map { it.sample.unknown })
        assertEquals(listOf(4, 4), runs.map { it.run!!.count })
        assertEquals(listOf(dead, dead, partialSource, partialSource), expandForReaders(runs).map { it.unknown })
    }

    @Test fun epochSkewAndBackwardsStepsSplit() {
        val skewed = listOf(unknown(0), unknown(300), HistorySample(HistoryTick(1_000_000 + 600 + 5_000, 600, 1), key, null, dead))
        assertEquals(listOf(2, null), collapseUnknownRuns(skewed, emptyList()).map { it.run?.count })
        val backwards = listOf(unknown(600), unknown(300))
        assertTrue(collapseUnknownRuns(backwards, emptyList()).all { it.run == null })
    }

    @Test fun aLoneUnknownAndAKnownRowAreNeverFolded() {
        val full = listOf(unknown(0), known(300, 1.0), unknown(600))
        val stored = collapseUnknownRuns(full, emptyList())
        assertEquals(full, stored.map { it.sample })
        assertTrue(stored.all { it.run == null })
        assertEquals(full, expandForReaders(stored))
        // A run of two keeps both ends, and a count of one expands to its first sample only (incoherent input).
        val handMade = HistoryStoredSample(unknown(0), 0, HistoryUnknownRunEnd(tick(900), 7, 1))
        assertEquals(listOf(unknown(0)), expandForReaders(listOf(handMade)))
        assertFailsWith<IllegalArgumentException> { HistoryUnknownRunEnd(tick(900), 7, 0) }
    }

    // ---- the PARTIAL_SOURCE bridge -------------------------------------------------------------------------------

    private fun bridged(samples: List<HistorySample>) = bridgePartialSourceArtefact(samples)

    /** Bridge output with the interior of every run removed, so two forms are compared by what a reader can see. */
    private fun normal(samples: List<HistorySample>) = expandForReaders(collapseUnknownRuns(samples, emptyList()))

    @Test fun bridgeInvariantForPartialSourceRun() {
        val values = { second: Long -> listOf(known(0, 1.0, powerKey)) +
            listOf(unknown(300, partialSource, powerKey), unknown(600, partialSource, powerKey)) + known(second, 2.0, powerKey) }
        val close = values(900)
        assertEquals(listOf(close.first(), close.last()), bridged(close))
        assertEquals(bridged(close), bridged(viaRuns(close)))
        val far = values(2400)
        assertEquals(far, bridged(far), "not bridged: the known values are 2400 ms apart")
        assertEquals(bridged(far), bridged(viaRuns(far)))
    }

    @Test fun bridgeAgreesOnLongRunsWhetherBridgedOrNot() {
        // Bridged: ten partial-source unknowns between values 1800 ms apart.
        val bridgedCase = listOf(known(0, 1.0, powerKey)) + unknownsBetween(300, 1500, reason = partialSource, k = powerKey) +
            known(1800, 2.0, powerKey)
        assertEquals(2, bridged(bridgedCase).size)
        assertEquals(bridged(bridgedCase), bridged(viaRuns(bridgedCase)))
        // Not bridged: the run outlasts the 2000 ms bracket, so the unknowns stay (the interior is the only difference).
        val kept = listOf(known(0, 1.0, powerKey)) + unknownsBetween(300, 3000, reason = partialSource, k = powerKey) +
            known(3300, 2.0, powerKey)
        assertEquals(kept.size, bridged(kept).size)
        assertEquals(normal(bridged(kept)), bridged(viaRuns(kept)))
        // Another reason in the same series is never an artefact.
        val otherReason = listOf(known(0, 1.0, powerKey)) + unknownsBetween(300, 900, reason = dead, k = powerKey) +
            known(1200, 2.0, powerKey)
        assertEquals(otherReason, bridged(otherReason))
        assertEquals(normal(bridged(otherReason)), bridged(viaRuns(otherReason)))
    }

    // ---- readers that look only at known values -------------------------------------------------------------------

    @Test fun energyAndStatsAreEqualOverRuns() {
        val multiplier = checkNotNull(HistoryMetricRegistry.descriptor("controller_power_w")?.consumptionMultiplier)
        val full = (0..40L).map { known(it * 300, 100.0 + it, powerKey) } + unknownsBetween(12_300, 20_100, k = powerKey) +
            (0..40L).map { known(20_400 + it * 300, 200.0 + it, powerKey) } +
            unknownsBetween(32_700, 33_000, k = powerKey) + known(33_300, 50.0, powerKey)
        val markers = listOf(gap(25_000, source = "ctl"))
        assertTrue(runsOf(full, markers).isNotEmpty())
        assertEquals(historyStats(full, markers, multiplier), historyStats(viaRuns(full, markers), markers, multiplier))
        assertEquals(false, historyStats(full, markers, multiplier).complete)
    }

    @Test fun distanceWalkIsEqualOverRuns() {
        val origin = tick(0)
        val counterKey = HistorySeriesKey("ctl", "trip_distance_km")
        val full = (0..30L).map { known(it * 300, 0.001 * it, counterKey) } + unknownsBetween(9_300, 15_000, k = counterKey) +
            (0..30L).map { known(15_300 + it * 300, 0.031 + 0.001 * it, counterKey) } +
            listOf(unknown(24_600, k = counterKey), known(24_900, 0.062, counterKey), unknown(25_200, k = counterKey))
        val markers = listOf(gap(30_000, source = "ctl"))
        assertTrue(runsOf(full, markers).isNotEmpty())
        val before = historyRideWalk(full, emptyList(), origin, null, 26_000, markers)
        val after = historyRideWalk(viaRuns(full, markers), emptyList(), origin, null, 26_000, markers)
        assertEquals(before.distance, after.distance)
        assertEquals(before.acceptedM, after.acceptedM)
        assertEquals(before.crossChecked, after.crossChecked)
        assertTrue(before.acceptedM > 0.0, "the fixture earned distance")
    }

    @Test fun movingTimeWithoutGpsIsEqualOverRuns() {
        val speedKey = HistorySeriesKey("ctl", "speed_kmh")
        val full = (0..10L).map { known(it * 300, 12.0, speedKey) } + unknownsBetween(3_300, 13_200, k = speedKey) +
            (0..10L).map { known(13_500 + it * 300, 0.5 + it, speedKey) }
        assertTrue(runsOf(full).isNotEmpty())
        val before = historyMovingMillis(full)
        val after = historyMovingMillis(viaRuns(full))
        assertEquals(before, after)
        assertTrue(before.approximate)
    }

    /**
     * With GPS, a pair of unknowns more than 5 s apart would be the existing "gap" case and credit GPS-moving time, which
     * the full series never did inside unknowns. Fillers (at most 2 s apart) keep the answer equal.
     */
    @Test fun movingTimeWithGpsIsEqualForRunsLongerThanFiveSeconds() {
        val speedKey = HistorySeriesKey("ctl", "speed_kmh")
        val full = (0..3L).map { known(it * 300, 12.0, speedKey) } + unknownsBetween(2_000, 12_000, step = 1_000, k = speedKey) +
            known(13_000, 12.0, speedKey)
        val origin = tick(0)
        // About 40 km/h of GPS for the whole stretch.
        val fixes = (0..13L).map { HistoryGpsFix(tick(it * 1_000), 56.0 + it * 0.0001, 60.0, 3.0, null) }
        assertEquals(1, runsOf(full).size)
        val before = historyMovingMillis(full, fixes, origin)
        val after = historyMovingMillis(viaRuns(full), fixes, origin)
        assertEquals(true, before.approximate)
        assertEquals(true, after.approximate)
        assertEquals(before, after)
    }

    @Test fun batteryUsedIsEqualOverRuns() {
        val socKey = HistorySeriesKey("bms", "soc_percent")
        val soc = (0..300L).map { known(it * 1_000, 80.0 - it * 0.02, socKey) }.toMutableList<HistorySample>().also {
            it.addAll(unknownsBetween(301_000, 330_000, step = 1_000, k = socKey))
            it.addAll((331..400L).map { second -> known(second * 1_000, 73.0 - (second - 331) * 0.02, socKey) })
        }
        val current = (0..800L).map { known(it * 500, 1.0 + (it % 3), key) } + unknownsBetween(400_500, 420_000, step = 500) +
            (841..900L).map { known(it * 500, 1.0, key) }
        assertTrue(runsOf(soc).isNotEmpty() && runsOf(current).isNotEmpty())
        assertEquals(historyBatteryUsed(soc, current, true), historyBatteryUsed(viaRuns(soc), viaRuns(current), true))
        assertEquals(historyBatteryUsed(soc, null, false), historyBatteryUsed(viaRuns(soc), null, false))
    }

    @Test fun robustPeakIsEqualOverRuns() {
        val flat = (0..9L).map { known(it * 300, 10.0 + (it % 2)) }
        // A needle with known neighbours is excluded; a spike beside an unknown run is not (its neighbour cannot vouch).
        val full = flat + known(3_000, 90.0) + known(3_300, 10.0) +
            (0..9L).map { known(3_600 + it * 300, 10.0 + (it % 2)) } +
            unknownsBetween(6_600, 9_000) + known(9_300, 95.0) + known(9_600, 10.0)
        val before = historyRobustPeak(full)
        val after = historyRobustPeak(viaRuns(full))
        assertEquals(1, runsOf(full).size)
        assertEquals(1, checkNotNull(before).needlesExcluded)
        assertEquals(95.0, before.value)
        assertEquals(before.value, checkNotNull(after).value)
        assertEquals(before.needlesExcluded, after.needlesExcluded)
        assertEquals(before.largestExcluded, after.largestExcluded)
    }

    // ---- the chart --------------------------------------------------------------------------------------------

    private fun knownPoints(points: List<HistorySample>) = points.filter { it.value != null }.map { it.tick to it.value }

    /** Each maximal stretch of unknown points as (first tick, last tick): where the line breaks. */
    private fun stretches(points: List<HistorySample>): List<Pair<HistoryTick, HistoryTick>> {
        val result = ArrayList<Pair<HistoryTick, HistoryTick>>()
        var start: HistoryTick? = null
        var end: HistoryTick? = null
        for (point in points) {
            if (point.value == null) { if (start == null) start = point.tick; end = point.tick }
            else if (start != null) { result += start to checkNotNull(end); start = null; end = null }
        }
        if (start != null) result += start to checkNotNull(end)
        return result
    }

    private fun overviewFixture(): List<HistorySample> {
        var at = 0L
        fun nextKnown(count: Int) = List(count) { index -> known(at, 5.0 + (index % 17) * 0.5).also { at += 300 } }
        fun nextUnknown(count: Int) = List(count) { unknown(at).also { at += 300 } }
        return nextKnown(100) + nextUnknown(300) + nextKnown(150) + nextUnknown(400) + nextKnown(150) + nextUnknown(300) +
            nextKnown(100)
    }

    @Test fun overviewEqualWithinBudget() {
        val full = overviewFixture()
        assertEquals(500, full.count { it.value != null })
        assertEquals(1000, full.count { it.value == null })
        assertEquals(3, runsOf(full).size)
        val expanded = viaRuns(full)
        assertTrue(expanded.size < full.size)

        val before = historyOverview(full, emptyList(), 20_000)
        val after = historyOverview(expanded, emptyList(), 20_000)
        assertEquals(knownPoints(before), knownPoints(after))
        assertEquals(stretches(before), stretches(after))
        assertEquals(3, stretches(after).size)

        // The streaming form (pages of the store's size) agrees with itself across the two shapes.
        fun streamed(samples: List<HistorySample>) = HistoryOverviewAccumulator(emptyList(), 20_000).also { acc ->
            samples.chunked(128).forEach(acc::offerPage)
        }.snapshot()
        assertEquals(knownPoints(streamed(full)), knownPoints(streamed(expanded)))
        assertEquals(stretches(streamed(full)), stretches(streamed(expanded)))
        assertEquals(knownPoints(before), knownPoints(streamed(full)))
    }

    /**
     * The accepted change: over the 600-point budget the unknowns of the full series were all mandatory, so the overview
     * fell back to four extrema and the expanded form can show far more of the real points. None is invented.
     */
    @Test fun overviewOverBudgetDrawsMoreRealPointsNeverInvented() {
        val full = overviewFixture()
        val realKnown = full.filter { it.value != null }.map { it.tick to it.value }.toSet()
        val before = knownPoints(historyOverview(full, emptyList()))
        val after = knownPoints(historyOverview(viaRuns(full), emptyList()))
        assertTrue(after.size > before.size, "expanded ${after.size} known points vs full ${before.size}")
        assertTrue(after.all { it in realKnown }, "no point is invented")
        assertTrue(before.all { it in realKnown })
    }

    @Test fun cursorInsideRunReadsUnknown() {
        val full = listOf(known(0, 5.0)) + unknownsBetween(300, 3000) + known(3300, 6.0)
        val expanded = viaRuns(full)
        assertEquals(listOf(0L, 300L, 2300L, 3000L, 3300L), expanded.map { it.tick.elapsedMillis }, "one filler at 2300")
        assertEquals(null, nearestHistorySample(full, tick(1500), 5000))
        assertEquals(null, nearestHistorySample(expanded, tick(1500), 5000))
        // Every cursor tick, not just one: the endpoints stay, so the answer never changes.
        for (cursor in 0L..3_400L step 7) {
            assertEquals(nearestHistorySample(full, tick(cursor), 5000), nearestHistorySample(expanded, tick(cursor), 5000),
                "cursor at $cursor ms")
            assertEquals(nearestHistorySample(full, tick(cursor), 100), nearestHistorySample(expanded, tick(cursor), 100),
                "cursor at $cursor ms, short age")
        }
        // A cursor strictly inside the run is never a known value.
        assertTrue((301L..2_999L).all { nearestHistorySample(expanded, tick(it), 5000) == null })
    }

    // ---- export -------------------------------------------------------------------------------------------------

    @Test fun loguiExportIsByteEqualOverRuns() = runTest {
        val voltage = HistorySeriesKey("ctl", "input_voltage_v")
        val current = HistorySeriesKey("ctl", "motor_current_a")
        val volts = listOf(known(0, 84.0, voltage)) + unknownsBetween(300, 3000, k = voltage) + known(3300, 83.5, voltage)
        val amps = (0..11L).map { known(it * 300, 1.0 + it, current) }
        assertEquals(1, runsOf(volts).size)
        val controller = HistorySource("ctl", HistorySourceKind.CONTROLLER, "ctl")
        val started = tick(0)
        val metadata = HistoryExportMetadata(
            HistoryRide("ride", "vehicle", started, tick(60_000), HistoryRideStatus.COMPLETED),
            HistoryRange(started.epochMillis, started.epochMillis + 60_000), listOf(controller))
        suspend fun export(voltSamples: List<HistorySample>): String {
            val text = StringBuilder()
            val records = (voltSamples + amps).sortedBy { it.tick.elapsedMillis }.map { HistoryExportRecord.Numeric(it) }
            StreamingHistoryExportEncoder(zone = { TimeZone.UTC }, loguiGridMillis = 50)
                .write(HistoryExportFormat.LOGUI, metadata, flowOf(*records.toTypedArray())) { text.append(it) }
            return text.toString()
        }
        val before = export(volts)
        assertEquals(before, export(viaRuns(volts)))
        assertTrue(before.lines().size > 5)
    }
}

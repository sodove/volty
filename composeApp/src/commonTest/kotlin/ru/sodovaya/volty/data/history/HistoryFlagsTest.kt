package ru.sodovaya.volty.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.data.db.VoltyDatabase
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.history.*
import kotlin.test.*

/**
 * History-honesty fixtures modelled on the rider's 0.7.57 ride (17:33, 14 min, wheel switched off at the end): a clean
 * speed series that ends exactly at a BLE_GAP marker, and a controller-power series that opens with a few unknown
 * samples. The write path is the real one (projection, then store), so stored bucket flags are the writer's own.
 */
class HistoryFlagsTest {
    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val origin = HistoryTick(10_000, 100, 1)
    private val ctl = HistorySource("ctl", HistorySourceKind.CONTROLLER, "Controller")
    private val speed = HistorySeriesKey("ctl", "speed_kmh")
    private val power = HistorySeriesKey("ctl", "controller_power_w")
    private fun tick(delta: Long) = HistoryTick(10_000 + delta, 100 + delta, 1)
    private fun range(endMillis: Long) = HistoryRange(10_000, 10_000 + endMillis)
    private fun known(key: HistorySeriesKey, t: Long, v: Double = 20.0) = HistorySample(tick(t), key, v)
    private fun unknown(key: HistorySeriesKey, t: Long) = HistorySample(tick(t), key, null, HistoryUnknownReason.PARTIAL_SOURCE)
    @AfterTest fun closeDrivers() { drivers.forEach { it.close() } }

    /** Stats as the app reads them: the repository settles what the store's cheap proof leaves pending. */
    private fun exact(store: HistoryStore) = DefaultHistoryReadRepository(store)

    private fun fixture(): Pair<JdbcSqliteDriver, SqlDelightHistoryStore> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        drivers += driver
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        VoltyDatabase.Schema.create(driver)
        return driver to SqlDelightHistoryStore(VoltyDatabaseProvider(driver))
    }

    /** Records [samples] and [markers] through the real writer (one batch) and finishes the ride at [endAt]. */
    private suspend fun record(store: SqlDelightHistoryStore, samples: List<HistorySample>, markers: List<HistoryMarker>, endAt: Long) {
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, listOf(ctl))
        val prepared = HistoryRecordingProjection(ride, listOf(ctl)).prepare(
            HistoryBatch("ride", samples, emptyList(), markers, "r"), true)
        store.append(prepared.batch)
        store.finish("ride", tick(endAt), HistoryRideStatus.COMPLETED)
    }
    private fun seconds(key: HistorySeriesKey, from: Long, to: Long, skip: LongRange = LongRange.EMPTY) =
        (from..to).filter { it !in skip }.map { known(key, it * 1000) }
    private fun gap(t: Long) = HistoryMarker(tick(t), HistoryMarkerKind.BLE_GAP)

    // --- 1. the end-of-ride gap marker -------------------------------------------------------------------------

    @Test fun aRideEndingExactlyAtABleGapMarkerIsCompleteBothAtWriteAndAtRead() = runTest {
        val (_, store) = fixture()
        record(store, seconds(speed, 0, 120), listOf(gap(120_000)), 120_000)
        // Write side: the stored last bucket must not carry the marker as "incomplete".
        val buckets = store.buckets("ride", speed, range(120_000), null, 10).items
        assertTrue(buckets.all { it.complete }, "a marker after the last sample must not degrade the bucket: $buckets")
        assertTrue(exact(store).stats("ride", speed, range(120_000)).complete)
    }

    @Test fun aMarkerAfterTheLastSampleDoesNotMakeTheSeriesIncomplete() = runTest {
        val (_, store) = fixture()
        record(store, seconds(speed, 0, 120), listOf(gap(121_500)), 121_500)
        assertTrue(exact(store).stats("ride", speed, range(121_500)).complete)
    }

    @Test fun aRealMidRideGapMarkerStillMakesTheSeriesIncomplete() = runTest {
        val (_, store) = fixture()
        // 40 s without samples in the middle of a 120 s ride, a BLE_GAP inside it, and samples after it.
        record(store, seconds(speed, 0, 120, skip = 41L..79L), listOf(gap(60_000)), 120_000)
        assertFalse(exact(store).stats("ride", speed, range(120_000)).complete)
    }

    @Test fun manyShortMarkedBreaksCountEvenWhenEachIntervalIsUnderTheGapLimit() = runTest {
        val (_, store) = fixture()
        // 15 markers, each inside a 1 s interval (no interval exceeds 2 s): only the marker rule sees them, 15 s > 10 s.
        record(store, seconds(speed, 0, 120), (10L..24L).map { gap(it * 1000 + 500) }, 120_000)
        assertFalse(exact(store).stats("ride", speed, range(120_000)).complete,
            "ignoring the markers would read exact; this kills the ignore-all-BLE_GAP mutant")
    }

    @Test fun aRideStoredByTheOldWriterWithAFlaggedLastBucketReadsCorrectlyWithoutAMigration() = runTest {
        val (_, store) = fixture()
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, listOf(ctl))
        val samples = seconds(speed, 0, 120)
        // The old writer's shape: every bucket complete except the last, flagged by the trailing marker.
        val accumulator = HistoryBucketAccumulator(origin, speed)
        val stored = linkedMapOf<Long, HistoryBucket>()
        samples.forEach { s -> accumulator.offer(s).forEach { stored[it.slot] = it } }
        val last = stored.getValue(stored.keys.max())
        stored[last.slot] = last.copy(complete = false)
        store.append(HistoryBatch("ride", samples, emptyList(), listOf(gap(120_000)), "legacy", stored.values.toList()))
        store.finish("ride", tick(120_000), HistoryRideStatus.COMPLETED)
        assertFalse(store.buckets("ride", speed, range(120_000), null, 10).items.last().complete, "precondition: the stored flag is wrong")
        assertTrue(exact(store).stats("ride", speed, range(120_000)).complete)
    }

    @Test fun theAccumulatorMarkOnlyBlocksTheNextInterval() {
        val a = HistoryBucketAccumulator(origin, speed)
        a.offer(known(speed, 0)); a.offer(known(speed, 1000))
        assertTrue(assertNotNull(a.mark(gap(1500))).complete, "a trailing marker flags nothing")
        // A sample across the barrier flags its own bucket.
        assertFalse(a.offer(known(speed, 2000)).last().complete)
    }

    // --- 2. partial only when the unknown-or-gap time is large -------------------------------------------------

    @Test fun theRidersFiveLeadingUnknownPowerSamplesLeaveEnergyExact() = runTest {
        val (_, store) = fixture()
        val lead = listOf(335L, 600L, 900L, 1200L, 1573L).map { unknown(power, it) }
        val samples = lead + (2L..860L).map { known(power, it * 1000, 500.0) }
        record(store, samples, listOf(gap(861_300)), 861_300)
        val stats = exact(store).stats("ride", power, range(861_300))
        assertTrue(stats.complete, "about 1.7 s of unknowns on 861 s")
        assertNotNull(stats.energyWh)
        val totals = historyRideTotals(listOf(ctl), mapOf(power to stats), 861_300, HistoryRideDistance(3.9, false))
            .associateBy { it.kind }
        assertFalse(assertNotNull(totals[HistoryTotalKind.ENERGY_WH]).approximate)
        assertFalse(assertNotNull(totals[HistoryTotalKind.WH_PER_KM]).approximate)
    }

    @Test fun theUnknownTimeThresholdIsTheLargerOfTwoPercentAndTenSeconds() = runTest {
        // Short ride (120 s): the floor is 10 s. An 8 s hole is exact, a 12 s hole partial.
        fun withHole(count: Long) = (0L..120L).map { if (it in 40 until 40 + count) unknown(speed, it * 1000) else known(speed, it * 1000) }
        for ((hole, expected) in listOf(8L to true, 12L to false)) {
            val (_, store) = fixture()
            record(store, withHole(hole), emptyList(), 120_000)
            assertEquals(expected, exact(store).stats("ride", speed, range(120_000)).complete, "a $hole sample hole on 120 s")
        }
    }

    @Test fun onALongRideTheThresholdIsTwoPercent() = runTest {
        fun withHole(count: Long) = (0L..1000L).map { if (it in 400 until 400 + count) unknown(speed, it * 1000) else known(speed, it * 1000) }
        // 1000 s gives 20 s. A hole of 15 unknown samples spans 16 s between its surrounding known samples; 25 spans 26 s.
        for ((hole, expected) in listOf(15L to true, 25L to false)) {
            val (_, store) = fixture()
            record(store, withHole(hole), emptyList(), 1_000_000)
            assertEquals(expected, exact(store).stats("ride", speed, range(1_000_000)).complete, "a $hole sample hole on 1000 s")
        }
    }

    @Test fun aSummaryOnlySeriesKeepsItsStoredBucketFlag() = runTest {
        val (_, store) = fixture()
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, listOf(ctl))
        val point = known(speed, 10)
        // No detail to measure the flag against: it is not overruled.
        store.append(HistoryBatch("ride", emptyList(), emptyList(), emptyList(), "s",
            listOf(HistoryBucket(speed, 0, point.tick, point.tick, point, point, null, 1, false))))
        store.finish("ride", tick(20), HistoryRideStatus.COMPLETED)
        assertFalse(exact(store).stats("ride", speed, range(1000)).complete)
    }

    @Test fun theThresholdIsMaxOfTwoPercentAndTenSecondsAndNeverExcusesAMostlyBrokenShortSeries() {
        assertEquals(10_000L, historyPartialThresholdMillis(400_000))
        assertEquals(17_220L, historyPartialThresholdMillis(861_000))
        assertEquals(20_000L, historyPartialThresholdMillis(1_000_000))
        assertFalse(historyBrokenIsPartial(17_220, 861_000)); assertTrue(historyBrokenIsPartial(17_221, 861_000))
        assertFalse(historyBrokenIsPartial(0, 0))
        assertTrue(historyBrokenIsPartial(4_000, 4_000), "a 4 s series that is all unknown is not excused by the 10 s floor")
    }

    // --- Fix round: review C1, I1, M1, M3, M5 ------------------------------------------------------------------

    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val packPower = HistorySeriesKey("pack", "power_w")

    private class CountingStore(val inner: HistoryStore) : HistoryStore by inner {
        var samplePages = 0
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange,
            after: HistoryCursor?, limit: Int): HistoryPage<HistorySample> { samplePages++; return inner.samples(rideId, key, range, after, limit) }
    }

    private suspend fun recordTwo(store: SqlDelightHistoryStore, samples: List<HistorySample>, markers: List<HistoryMarker>, endAt: Long) {
        val sources = listOf(ctl, pack)
        val ride = HistoryRide("ride", "bike", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, sources)
        store.append(HistoryRecordingProjection(ride, sources).prepare(HistoryBatch("ride", samples, emptyList(), markers, "r2"), true).batch)
        store.finish("ride", tick(endAt), HistoryRideStatus.COMPLETED)
    }

    @Test fun aSourceThatDiesMidRideWithItsOwnGapMarkerIsPartialAndItsEnergyApproximate() = runTest {
        val (_, store) = fixture()
        // The pack link drops at minute 20 of a 60-minute ride; its scoped BLE_GAP sits at the pack's last sample and
        // the controller records on. Before this fix round that marker was "after the last sample" and read exact.
        val packSamples = (0L..1200L).map { known(packPower, it * 1000, -1000.0) }
        val controller = (0L..3600L).map { known(speed, it * 1000) }
        recordTwo(store, packSamples + controller, listOf(HistoryMarker(tick(1_200_000), HistoryMarkerKind.BLE_GAP, "pack")), 3_600_000)
        val stats = exact(store).stats("ride", packPower, range(3_600_000))
        assertFalse(stats.complete, "40 of 60 minutes missing")
        val total = historyRideTotals(listOf(ctl, pack), mapOf(packPower to stats), 3_600_000, HistoryRideDistance(10.0, false))
            .single { it.kind == HistoryTotalKind.ENERGY_WH }
        assertTrue(total.approximate)
    }

    @Test fun whenEverySourceEndsTogetherTheTrailingMarkerStaysExcused() = runTest {
        val (_, store) = fixture()
        val samples = (0L..120L).flatMap { listOf(known(packPower, it * 1000, -1000.0), known(speed, it * 1000)) }
        // Wheel switched off: the loss is 4 s after the last sample (the rider's export shows about 5 s), and the ride ends at it.
        recordTwo(store, samples, listOf(HistoryMarker(tick(124_000), HistoryMarkerKind.BLE_GAP)), 124_000)
        assertTrue(exact(store).stats("ride", packPower, range(124_000)).complete)
        assertTrue(exact(store).stats("ride", speed, range(124_000)).complete)
    }

    @Test fun twoStatsReadsOfAFinishedRideWalkOnceAndTheStoreItselfNeverWalks() = runTest {
        val (_, store) = fixture()
        val lead = listOf(335L, 600L, 900L).map { unknown(power, it) }
        record(store, lead + (2L..300L).map { known(power, it * 1000, 500.0) }, emptyList(), 300_000)
        val counting = CountingStore(store)
        val direct = counting.stats("ride", power, range(300_000))
        assertTrue(direct.pendingWalk); assertFalse(direct.complete)
        assertEquals(0, counting.samplePages, "the store's stats transaction pages no detail")
        val repository = DefaultHistoryReadRepository(counting)
        val first = repository.stats("ride", power, range(300_000))
        assertTrue(first.complete); assertFalse(first.pendingWalk)
        val pages = counting.samplePages
        assertTrue(pages > 0)
        assertEquals(first, repository.stats("ride", power, range(300_000)))
        assertEquals(pages, counting.samplePages, "the second read of a finished ride is served from the cache")
    }

    @Test fun anAllUnknownSeriesIsPartialWithoutAnyWalk() = runTest {
        val (_, store) = fixture()
        record(store, (0L..120L).map { unknown(speed, it * 1000) } + seconds(power, 0, 120), emptyList(), 120_000)
        val counting = CountingStore(store)
        assertFalse(DefaultHistoryReadRepository(counting).stats("ride", speed, range(120_000)).complete)
        assertEquals(0, counting.samplePages)
    }

    @Test fun theWalkStopsAtTheFirstPageThatSettlesIt() = runTest {
        val (_, store) = fixture()
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, listOf(ctl))
        // 20000 unknown rows (20 s, past the 10 s floor) fill the first page; 1000 known rows sit on a second one.
        val rows = (0L until 20000L).map { unknown(speed, it) } + (20000L until 21000L).map { known(speed, it) }
        val first = known(speed, 20000)
        store.append(HistoryBatch("ride", rows, emptyList(), emptyList(), "stop",
            listOf(HistoryBucket(speed, 0, first.tick, first.tick, first, first, null, 21000, false))))
        store.finish("ride", tick(21_000), HistoryRideStatus.COMPLETED)
        val counting = CountingStore(store)
        assertFalse(DefaultHistoryReadRepository(counting).stats("ride", speed, range(120_000)).complete)
        assertEquals(1, counting.samplePages, "the second page is never read: the verdict was already certain")
    }

    @Test fun theDefaultChartProbeNeverWalksTheDetail() = runTest {
        val (_, store) = fixture()
        record(store, listOf(unknown(power, 0)) + (1L..120L).map { known(power, it * 1000, 500.0) } +
            (0L..120L).map { unknown(speed, it * 1000) }, emptyList(), 120_000)
        val counting = CountingStore(store)
        val repository = DefaultHistoryReadRepository(counting)
        assertTrue(repository.hasKnownValue("ride", power, range(120_000)))
        assertFalse(repository.hasKnownValue("ride", speed, range(120_000)))
        assertEquals(0, counting.samplePages)
    }

    private suspend fun energyTile(samples: List<HistorySample>): HistoryTotal {
        val (_, store) = fixture()
        record(store, samples, emptyList(), 1_000_000)
        val stats = exact(store).stats("ride", power, range(1_000_000))
        assertTrue(stats.complete, "both holes are inside the 20 s tolerance")
        return historyRideTotals(listOf(ctl), mapOf(power to stats), 1_000_000, HistoryRideDistance(5.0, false))
            .single { it.kind == HistoryTotalKind.ENERGY_WH }
    }

    @Test fun aGenuineGapMakesEnergyApproximateEvenInsideTheTolerance() = runTest {
        // 15 s without any sample (a link drop) versus 15 unknown samples (a short unknown run).
        val gap = energyTile((0L..1000L).filter { it !in 400L..415L }.map { known(power, it * 1000, 500.0) })
        val run = energyTile((0L..1000L).map { if (it in 400L..414L) unknown(power, it * 1000) else known(power, it * 1000, 500.0) })
        assertTrue(gap.approximate, "a real gap: the integral skipped real time")
        assertFalse(run.approximate, "a short unknown run stays tolerated")
    }

    @Test fun readingMoreDetailPagesThanTheWalkBudgetIsPartialNotGuessed() = runTest {
        val (_, store) = fixture()
        val ride = HistoryRide("ride", "wheel", origin, null, HistoryRideStatus.RECORDING)
        store.begin(ride, listOf(ctl))
        val point = known(speed, 0)
        val rows = (0L until 20000L).map { known(speed, it) } + unknown(speed, 20000)
        store.append(HistoryBatch("ride", rows, emptyList(), emptyList(), "big",
            listOf(HistoryBucket(speed, 0, point.tick, point.tick, point, point, null, 20000, true))))
        store.finish("ride", tick(20_000), HistoryRideStatus.COMPLETED)
        assertTrue(DefaultHistoryReadRepository(store).stats("ride", speed, range(20_000)).complete, "two pages fit the default budget")
        assertFalse(DefaultHistoryReadRepository(store, maxWalkPages = 1).stats("ride", speed, range(20_000)).complete)
    }

    @Test fun aRangeThatClipsABucketIsNeverCompleteEvenWhenItsDetailLooksClean() = runTest {
        val (_, store) = fixture()
        record(store, listOf(known(speed, 10), known(speed, 20), known(speed, 30)), emptyList(), 30)
        assertFalse(exact(store).stats("ride", speed, HistoryRange(10_015, 10_035)).complete)
        assertTrue(exact(store).stats("ride", speed, range(30)).complete)
    }
}

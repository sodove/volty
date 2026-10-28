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
        assertTrue(store.stats("ride", speed, range(120_000)).complete)
    }

    @Test fun aMarkerAfterTheLastSampleDoesNotMakeTheSeriesIncomplete() = runTest {
        val (_, store) = fixture()
        record(store, seconds(speed, 0, 120), listOf(gap(121_500)), 121_500)
        assertTrue(store.stats("ride", speed, range(121_500)).complete)
    }

    @Test fun aRealMidRideGapMarkerStillMakesTheSeriesIncomplete() = runTest {
        val (_, store) = fixture()
        // 40 s without samples in the middle of a 120 s ride, a BLE_GAP inside it, and samples after it.
        record(store, seconds(speed, 0, 120, skip = 41L..79L), listOf(gap(60_000)), 120_000)
        assertFalse(store.stats("ride", speed, range(120_000)).complete)
    }

    @Test fun manyShortMarkedBreaksCountEvenWhenEachIntervalIsUnderTheGapLimit() = runTest {
        val (_, store) = fixture()
        // 15 markers, each inside a 1 s interval (no interval exceeds 2 s): only the marker rule sees them, 15 s > 10 s.
        record(store, seconds(speed, 0, 120), (10L..24L).map { gap(it * 1000 + 500) }, 120_000)
        assertFalse(store.stats("ride", speed, range(120_000)).complete,
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
        assertTrue(store.stats("ride", speed, range(120_000)).complete)
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
        val stats = store.stats("ride", power, range(861_300))
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
            assertEquals(expected, store.stats("ride", speed, range(120_000)).complete, "a $hole sample hole on 120 s")
        }
    }

    @Test fun onALongRideTheThresholdIsTwoPercent() = runTest {
        fun withHole(count: Long) = (0L..1000L).map { if (it in 400 until 400 + count) unknown(speed, it * 1000) else known(speed, it * 1000) }
        // 1000 s gives 20 s. A hole of 15 unknown samples spans 16 s between its surrounding known samples; 25 spans 26 s.
        for ((hole, expected) in listOf(15L to true, 25L to false)) {
            val (_, store) = fixture()
            record(store, withHole(hole), emptyList(), 1_000_000)
            assertEquals(expected, store.stats("ride", speed, range(1_000_000)).complete, "a $hole sample hole on 1000 s")
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
        assertFalse(store.stats("ride", speed, range(1000)).complete)
    }

    @Test fun theThresholdIsMaxOfTwoPercentAndTenSecondsAndNeverExcusesAMostlyBrokenShortSeries() {
        assertEquals(10_000L, historyPartialThresholdMillis(400_000))
        assertEquals(17_220L, historyPartialThresholdMillis(861_000))
        assertEquals(20_000L, historyPartialThresholdMillis(1_000_000))
        assertFalse(historyBrokenIsPartial(17_220, 861_000)); assertTrue(historyBrokenIsPartial(17_221, 861_000))
        assertFalse(historyBrokenIsPartial(0, 0))
        assertTrue(historyBrokenIsPartial(4_000, 4_000), "a 4 s series that is all unknown is not excused by the 10 s floor")
    }
}

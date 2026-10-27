package ru.sodovaya.volty.presentation.history

import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

/** [readHistoryTotals] against a fake that pages detail samples: what was issued, what was shown. */
class HistoryTotalsReadTest {
    private val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val origin = HistoryTick(1_000_000, 0, 1)
    private val rideMillis = 950_000L
    private val range = HistoryRange(1_000_000, 1_000_000 + rideMillis)
    private val trip = HistorySeriesKey("esc", "trip_distance_km")
    private val odo = HistorySeriesKey("esc", "odometer_km")
    private val speed = HistorySeriesKey("esc", "speed_kmh")

    private fun at(millis: Long, key: HistorySeriesKey, km: Double) =
        HistorySample(HistoryTick(1_000_000 + millis, millis, 1), key, km)
    private fun fix(sec: Long) = HistoryGpsFix(HistoryTick(1_000_000 + sec * 1000, sec * 1000, 1), 55.0 + 6.0 * sec / 111_194.9, 37.0, 5.0, null)

    private class Repo(val rideStarted: HistoryTick?) : HistoryReadRepository {
        var series = mapOf<HistorySeriesKey, List<HistorySample>>()
        var detailSupported = true
        var pageSize = 1000
        var fixes = emptyList<HistoryGpsFix>()
        var stats = mapOf<HistorySeriesKey, HistoryStats>()
        val issued = mutableListOf<Triple<HistorySeriesKey, HistoryCursor?, Int>>()
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(emptyList(), false)
        override suspend fun rides(before: HistoryCursor?) = HistoryPage(emptyList<HistoryRide>(), null, false)
        override suspend fun ride(rideId: String): HistoryRide? =
            rideStarted?.let { HistoryRide(rideId, "v", it, null, HistoryRideStatus.COMPLETED) }
        override suspend fun sources(rideId: String) = emptyList<HistorySource>()
        override suspend fun series(rideId: String, key: HistorySeriesKey, range: HistoryRange, maxPoints: Int) = error("unused")
        override suspend fun gps(rideId: String, range: HistoryRange, after: HistoryCursor?) = error("unused")
        override suspend fun route(rideId: String, range: HistoryRange, after: HistoryCursor?) =
            HistoryRoutePage(rideStarted, HistoryPage(fixes, null, false), emptyList())
        override suspend fun stats(rideId: String, key: HistorySeriesKey, range: HistoryRange) =
            stats[key] ?: HistoryStats(null, null, null, true)
        override suspend fun samples(rideId: String, key: HistorySeriesKey, range: HistoryRange, after: HistoryCursor?, limit: Int):
            HistoryPage<HistorySample>? {
            if (!detailSupported) return null
            issued += Triple(key, after, limit)
            val all = series[key].orEmpty()
            val from = after?.stableId?.toInt() ?: 0
            val to = minOf(all.size, from + pageSize)
            val next = if (to < all.size) HistoryCursor(all[to - 1].tick.epochMillis, to.toString()) else null
            return HistoryPage(all.subList(from, to), next, next != null)
        }
    }

    private fun counterRide(key: HistorySeriesKey, needleAtMillis: Long? = null) = (0..1900).map { i ->
        val t = i * 500L
        at(t, key, if (t == needleAtMillis) 2883.568 else 2816.4 + 6.0 * t / 1_000_000.0)
    }
    private val incoherentStats = mapOf(
        odo to HistoryStats(2816.4, 2883.6, null, true), // complete = true, yet carrying a needle
        speed to HistoryStats(0.0, 55.1, null, true))

    private fun List<HistoryTotal>.of(kind: HistoryTotalKind) = singleOrNull { it.kind == kind }

    @Test fun theRidersRideShowsAnHonestDistanceFromPagedDetail() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo, 272_000)); stats = incoherentStats; pageSize = 500 }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range)
        val d = totals.of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.70, d.value, 0.01); assertTrue(d.approximate)
        assertTrue(totals.of(HistoryTotalKind.AVERAGE_KMH)!!.value < 55.1)
        // Issued: the trip series first (empty), then the odometer in pages of 20 000 at most.
        assertEquals(trip, repo.issued.first().first)
        assertTrue(repo.issued.all { it.third == 20_000 })
        assertTrue(repo.issued.count { it.first == odo } > 1, "paged through the cursor")
    }

    @Test fun theLargerAcceptedSumWinsSoATripThatRoseAFewMetresNeverBeatsAHealthyOdometer() = runTest {
        val repo = Repo(origin).apply {
            series = mapOf(trip to (0..100).map { at(it * 500L, trip, it * 0.003) }, odo to counterRide(odo)); stats = incoherentStats
        }
        val d = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.70, d.value, 0.01)
        assertTrue(repo.issued.any { it.first == trip } && repo.issued.any { it.first == odo }, "both series were walked")
    }

    @Test fun aHealthyTripCounterIsUsedWhenItCarriesTheRide() = runTest {
        val repo = Repo(origin).apply {
            series = mapOf(trip to counterRide(trip), odo to (0..100).map { at(it * 500L, odo, 2816.4 + it * 0.003) })
        }
        assertEquals(5.70, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!.value, 0.01)
    }

    @Test fun aCounterTheCrossCheckJudgedUnearnedIsFinalAndTheStatsMaxMinCannotOverruleIt() = runTest {
        // Incoherent on purpose: the samples carry a 67 km step (the gated sum is impossible at a 55 km/h peak) while the stats
        // of the very same series say a plausible, complete 5.7 km (a reset hid the needle from max - min).
        val needled = listOf(at(0, odo, 2816.4), at(1_000, odo, 2883.6), at(1_500, odo, 2883.603))
        val repo = Repo(origin).apply {
            series = mapOf(odo to needled)
            stats = mapOf(odo to HistoryStats(2816.4, 2822.1, null, true), speed to HistoryStats(0.0, 55.1, null, true))
        }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range)
        assertNull(totals.of(HistoryTotalKind.DISTANCE_KM), "no GPS: no tile, not the stats' 5.7")
        assertNull(totals.of(HistoryTotalKind.AVERAGE_KMH))
        repo.fixes = (0..100L).map(::fix)
        assertEquals(0.60, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!.value, 0.01, "GPS alone")
        // A healthy trip beside the needled odometer is preferred to the unearned one.
        repo.fixes = emptyList()
        repo.series = mapOf(odo to needled, trip to counterRide(trip))
        assertEquals(5.70, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!.value, 0.01)
    }

    @Test fun gpsIsAlwaysReadAndWinsWhereItCovers() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo)); fixes = (0..950L).map(::fix); stats = incoherentStats }
        val d = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.70, d.value, 0.02); assertFalse(d.approximate)
        repo.series = mapOf(odo to counterRide(odo, 272_000))
        val needled = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.70, needled.value, 0.02); assertTrue(needled.approximate, "a rejected step is always disclosed")
    }

    @Test fun aRepositoryThatCannotPageDetailFallsBackToStatsWithTheCrossCheck() = runTest {
        val repo = Repo(origin).apply { detailSupported = false; stats = incoherentStats }
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM), "no GPS: no tile")
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.AVERAGE_KMH))
        repo.fixes = (0..950L).map(::fix)
        val d = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.70, d.value, 0.02); assertTrue(d.approximate)
    }

    @Test fun exceedingThePageBudgetFallsBackToTheCrossCheckedStatsPathAndIsApproximate() = runTest {
        val repo = Repo(origin).apply {
            series = mapOf(odo to counterRide(odo)); pageSize = 100
            stats = mapOf(odo to HistoryStats(2816.4, 2822.1, null, true), speed to HistoryStats(0.0, 55.1, null, true))
        }
        val d = readHistoryTotals(repo, "r", listOf(esc), range, maxDetailPages = 2).of(HistoryTotalKind.DISTANCE_KM)!!
        assertEquals(5.7, d.value, 1e-6)
        assertTrue(d.approximate, "the budget ran out: not exact")
        assertEquals(3, repo.issued.size, "the empty trip series, then two odometer pages, then no more reads")
        // The stats path still carries the cross-check: a needle in the stats shows nothing.
        repo.stats = incoherentStats
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range, maxDetailPages = 2).of(HistoryTotalKind.DISTANCE_KM))
    }

    @Test fun aRideThatIsMissingFromStorageFallsBackToStats() = runTest {
        val repo = Repo(null).apply { series = mapOf(odo to counterRide(odo)); stats = mapOf(odo to HistoryStats(10.0, 12.0, null, true)) }
        assertEquals(2.0, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!.value, 1e-9)
    }
}

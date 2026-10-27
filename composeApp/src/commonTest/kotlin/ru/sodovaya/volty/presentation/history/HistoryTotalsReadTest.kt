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

    private class Repo(val rideStarted: HistoryTick?, var status: HistoryRideStatus = HistoryRideStatus.COMPLETED) : HistoryReadRepository {
        var series = mapOf<HistorySeriesKey, List<HistorySample>>()
        var detailSupported = true
        var pageSize = 1000
        var fixes = emptyList<HistoryGpsFix>()
        var stats = mapOf<HistorySeriesKey, HistoryStats>()
        val issued = mutableListOf<Triple<HistorySeriesKey, HistoryCursor?, Int>>()
        override suspend fun recordedKeys(rideId: String, sourceId: String, limit: Int) = HistoryRecordedKeys(emptyList(), false)
        override suspend fun rides(before: HistoryCursor?) = HistoryPage(emptyList<HistoryRide>(), null, false)
        override suspend fun ride(rideId: String): HistoryRide? =
            rideStarted?.let { HistoryRide(rideId, "v", it, null, status) }
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
        assertEquals(trip, repo.issued.first { it.first == trip || it.first == odo }.first) // peak series are read before the counter
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
        assertEquals(3, repo.issued.count { it.first == trip || it.first == odo }, "the empty trip series, then two odometer pages, then no more reads")
        // The stats path still carries the cross-check: a needle in the stats shows nothing.
        repo.stats = incoherentStats
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range, maxDetailPages = 2).of(HistoryTotalKind.DISTANCE_KM))
    }

    private fun movingSpeed(unknownFrom: Long? = null) = (0..1900L).map { i ->
        val t = i * 500L
        val v = when { unknownFrom != null && t in unknownFrom until unknownFrom + 100_000 -> null
            t < 100_000 -> 0.0; t < 900_000 -> 25.0; else -> 1.5 }
        HistorySample(HistoryTick(1_000_000 + t, t, 1), speed, v, if (v == null) HistoryUnknownReason.UNAVAILABLE else null)
    }

    @Test fun theAverageUsesMovingTimeFromThePagedSpeedSeries() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo), speed to movingSpeed()); pageSize = 500; stats = incoherentStats }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range)
        assertEquals(800_000.0, totals.of(HistoryTotalKind.MOVING_MILLIS)!!.value, 500.0)
        assertEquals(5.70 / (800.0 / 3600.0), totals.of(HistoryTotalKind.AVERAGE_KMH)!!.value, 0.3)
        assertTrue(repo.issued.count { it.first == speed } > 1, "the speed detail went through the shared pager's cursor")
        assertTrue(repo.issued.all { it.third == 20_000 })
    }

    @Test fun withoutAPagedSpeedSeriesTheAverageIsTheWallClockOneAndApproximate() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo)); stats = incoherentStats }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range)
        assertNull(totals.of(HistoryTotalKind.MOVING_MILLIS))
        val average = totals.of(HistoryTotalKind.AVERAGE_KMH)!!
        assertEquals(5.70 / (950.0 / 3600.0), average.value, 0.1); assertTrue(average.approximate)
        val unpaged = Repo(origin).apply { detailSupported = false; fixes = (0..950L).map(::fix); stats = incoherentStats }
        assertNull(readHistoryTotals(unpaged, "r", listOf(esc), range).of(HistoryTotalKind.MOVING_MILLIS))
    }

    @Test fun aSpeedSeriesTheBudgetCutShortIsNotAMovingTime() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo), speed to movingSpeed()); pageSize = 100 }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range, maxDetailPages = 2)
        assertNull(totals.of(HistoryTotalKind.MOVING_MILLIS), "a prefix of the ride is not the ride")
    }

    @Test fun anUnknownStretchMarksTheMovingTimeApproximateEvenWhenTheCounterIsComplete() = runTest {
        val repo = Repo(origin).apply { series = mapOf(odo to counterRide(odo), speed to movingSpeed(unknownFrom = 300_000)); stats = incoherentStats }
        val moving = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MOVING_MILLIS)!!
        assertTrue(moving.approximate); assertEquals(700_000.0, moving.value, 1_500.0)
    }

    @Test fun aFirstSourceWithNoKnownSpeedLeavesTheChoiceToTheNextOne() = runTest {
        val second = HistorySource("esc2", HistorySourceKind.CONTROLLER, "ESC 2")
        val speed2 = HistorySeriesKey("esc2", "speed_kmh")
        val unknownOnly = movingSpeed().map { it.copy(value = null, unknown = HistoryUnknownReason.UNAVAILABLE) }
        val repo = Repo(origin).apply {
            series = mapOf(odo to counterRide(odo), speed to unknownOnly, speed2 to movingSpeed().map { it.copy(key = speed2) }); stats = incoherentStats
        }
        val moving = readHistoryTotals(repo, "r", listOf(esc, second), range).of(HistoryTotalKind.MOVING_MILLIS)!!
        assertEquals(800_000.0, moving.value, 500.0)
        assertTrue(repo.issued.any { it.first == speed2 }, "the second source's speed was read")
    }

    @Test fun aRideThatIsMissingFromStorageFallsBackToStats() = runTest {
        val repo = Repo(null).apply { series = mapOf(odo to counterRide(odo)); stats = mapOf(odo to HistoryStats(10.0, 12.0, null, true)) }
        assertEquals(2.0, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM)!!.value, 1e-9)
    }

    @Test fun batteryUsedIsReadFromPagedChargeAndTheBatterysOwnCurrentAndIsSigned() = runTest {
        val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
        val soc = HistorySeriesKey("pack", "soc_percent"); val amps = HistorySeriesKey("pack", "current_a")
        val repo = Repo(origin).apply {
            pageSize = 300 // several pages through the shared pager
            series = mapOf(
                soc to (0..900).map { t -> HistorySample(HistoryTick(1_000_000L + t * 1000, t * 1000L, 1), soc, if (t == 400) 40.0 else if (t > 840) 79.0 else 80.0) },
                amps to (0..900).map { t -> HistorySample(HistoryTick(1_000_000L + t * 1000, t * 1000L, 1), amps, if (t == 400) -45.0 else -0.5) })
        }
        val used = readHistoryTotals(repo, "r", listOf(esc, pack), range).of(HistoryTotalKind.BATTERY_USED_PERCENT)!!
        assertEquals(1.0, used.value, 1e-9)
        assertFalse(used.approximate)
        assertTrue(repo.issued.count { it.first == soc } > 1, "the charge was paged through the cursor")
        // Without the pack's own current the controller's battery current is read, and the tile is approximate.
        val bat = HistorySeriesKey("esc", "battery_current_a")
        repo.series = repo.series - amps + (bat to repo.series.getValue(amps).map { it.copy(key = bat) })
        val viaController = readHistoryTotals(repo, "r", listOf(esc, pack), range).of(HistoryTotalKind.BATTERY_USED_PERCENT)!!
        assertEquals(1.0, viaController.value, 1e-9); assertTrue(viaController.approximate)
        // A reader that cannot page detail gives no tile rather than the stats' max - min.
        repo.detailSupported = false
        repo.stats = mapOf(soc to HistoryStats(40.0, 80.0, null, true))
        assertNull(readHistoryTotals(repo, "r", listOf(esc, pack), range).of(HistoryTotalKind.BATTERY_USED_PERCENT))
    }

    // ---- Task 10: peak tiles ignore single-sample needles (rule and false-positive risk: HistoryNeedles.kt) ----
    private fun steadySpeed(needleAtMillis: Long? = null) = (0..1900).map { i ->
        val t = i * 500L
        at(t, speed, if (t == needleAtMillis) 1180.0 else 40.0 + (i % 5))
    }

    @Test fun aSpeedNeedleDoesNotBecomeTheTopSpeedEvenWhenTheStatsAreCompleteAndSayOtherwise() = runTest {
        // Incoherent on purpose: complete = true stats whose maximum is the needle, with detail available.
        val repo = Repo(origin).apply { series = mapOf(speed to steadySpeed(500_000)); stats = mapOf(speed to HistoryStats(0.0, 1180.0, null, true)) }
        val top = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_KMH)!!
        assertEquals(44.0, top.value); assertTrue(top.approximate)
    }

    @Test fun aSpeedSeriesWithoutNeedlesKeepsAnExactPeak() = runTest {
        val repo = Repo(origin).apply { series = mapOf(speed to steadySpeed()); stats = mapOf(speed to HistoryStats(0.0, 44.0, null, true)) }
        val top = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_KMH)!!
        assertEquals(44.0, top.value); assertFalse(top.approximate)
    }

    @Test fun theDistanceCrossCheckJudgesAgainstTheRobustSpeedPeakNotTheNeedle() = runTest {
        // 67 km of counter progress in 950 s is only affordable at a 1180 km/h top speed, which is the needle.
        val fast = (0..1900).map { i -> at(i * 500L, odo, 2816.4 + 67.0 * i / 1900.0) }
        val repo = Repo(origin).apply {
            series = mapOf(odo to fast, speed to steadySpeed(500_000))
            stats = mapOf(odo to HistoryStats(2816.4, 2883.4, null, true), speed to HistoryStats(0.0, 1180.0, null, true))
        }
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM), "unearned and no GPS: no tile")
    }

    @Test fun overBudgetDetailKeepsTodaysStatsMaximumAndSaysApproximate() = runTest {
        val repo = Repo(origin).apply {
            series = mapOf(speed to steadySpeed(500_000)); pageSize = 100
            stats = mapOf(speed to HistoryStats(0.0, 1180.0, null, true))
        }
        val top = readHistoryTotals(repo, "r", listOf(esc), range, maxDetailPages = 2).of(HistoryTotalKind.MAX_KMH)!!
        assertEquals(1180.0, top.value); assertTrue(top.approximate)
    }

    @Test fun aReaderThatCannotPageDetailKeepsTheStatsPeakUnchanged() = runTest {
        val repo = Repo(origin).apply { detailSupported = false; stats = mapOf(speed to HistoryStats(0.0, 55.1, null, true)) }
        val top = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_KMH)!!
        assertEquals(55.1, top.value); assertFalse(top.approximate)
    }

    @Test fun controllerPowerArtefactUnknownsDoNotHideANeedleFromItsNeighbours() = runTest {
        // Known values with the proven PARTIAL_SOURCE alternation between them (Task 6); the 5000 W sample is a needle
        // only once the bridge has made its neighbours adjacent.
        val power = HistorySeriesKey("esc", "controller_power_w")
        val values = listOf(1000.0, 1010.0, 990.0, 1000.0, 5000.0, 1005.0, 995.0, 1000.0, 1010.0)
        val samples = values.flatMapIndexed { i, v ->
            val t = i * 1000L
            listOf(HistorySample(HistoryTick(1_000_000 + t, t, 1), power, v),
                HistorySample(HistoryTick(1_000_000 + t + 500, t + 500, 1), power, null, HistoryUnknownReason.PARTIAL_SOURCE))
        }
        val repo = Repo(origin).apply { series = mapOf(power to samples); stats = mapOf(power to HistoryStats(0.0, 5000.0, null, true)) }
        val top = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_POWER_W)!!
        assertEquals(1010.0, top.value); assertTrue(top.approximate)
    }

    @Test fun aRealUnknownBesideAPowerSampleStillBlocksTheNeedleRule() = runTest {
        val power = HistorySeriesKey("esc", "controller_power_w")
        val unavailable = listOf(1000.0, 1010.0, 5000.0, null, 1000.0, 1005.0).mapIndexed { i, v ->
            val t = i * 500L
            HistorySample(HistoryTick(1_000_000 + t, t, 1), power, v, if (v == null) HistoryUnknownReason.UNAVAILABLE else null)
        }
        val repo = Repo(origin).apply { series = mapOf(power to unavailable); stats = mapOf(power to HistoryStats(0.0, 5000.0, null, true)) }
        assertEquals(5000.0, readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_POWER_W)!!.value)
    }

    // ---- Ruling 7: a recording ride pages no peak detail; a finished ride's peaks are read once ----
    private val motor = HistorySeriesKey("esc", "motor_temperature_c")
    private fun motorSeries(needleAtMillis: Long? = null) = (0..1900).map { i ->
        val t = i * 500L
        at(t, motor, if (t == needleAtMillis) 130.0 else 45.0 + (i % 3))
    }

    @Test fun aRecordingRideUsesTheStoredMaximumAndPagesNoPeakDetail() = runTest {
        // Only the motor temperature is a peak-only series (moving time reads speed on its own, as it always did).
        val repo = Repo(origin, HistoryRideStatus.RECORDING).apply {
            series = mapOf(motor to motorSeries(500_000), speed to steadySpeed(500_000))
            stats = mapOf(motor to HistoryStats(40.0, 130.0, null, true), speed to HistoryStats(0.0, 1180.0, null, true))
        }
        val totals = readHistoryTotals(repo, "r", listOf(esc), range, peakCache = HistoryPeakCache())
        assertEquals(130.0, totals.of(HistoryTotalKind.MOTOR_TEMP_C)!!.value, "stored maximum while recording")
        assertEquals(1180.0, totals.of(HistoryTotalKind.MAX_KMH)!!.value, "stored maximum while recording")
        assertTrue(repo.issued.none { it.first == motor }, "no peak detail paged for a recording ride: ${repo.issued.map { it.first }}")
    }

    @Test fun theDistanceCrossCheckStillRunsWhileRecordingAgainstTheStatsPeak() = runTest {
        // 67 km in 950 s against a stats peak of 55.1 km/h: unearned; with no GPS there is no tile.
        val fast = (0..1900).map { i -> at(i * 500L, odo, 2816.4 + 67.0 * i / 1900.0) }
        val repo = Repo(origin, HistoryRideStatus.RECORDING).apply {
            series = mapOf(odo to fast)
            stats = mapOf(odo to HistoryStats(2816.4, 2883.4, null, true), speed to HistoryStats(0.0, 55.1, null, true))
        }
        assertNull(readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.DISTANCE_KM))
    }

    @Test fun twoTotalsReadsOfAFinishedRidePageThePeakDetailOnce() = runTest {
        val repo = Repo(origin).apply {
            series = mapOf(motor to motorSeries(500_000), speed to steadySpeed(500_000))
            stats = mapOf(motor to HistoryStats(40.0, 130.0, null, true), speed to HistoryStats(0.0, 1180.0, null, true))
        }
        val cache = HistoryPeakCache()
        val first = readHistoryTotals(repo, "r", listOf(esc), range, peakCache = cache)
        val pagesAfterFirst = repo.issued.count { it.first == motor }
        assertTrue(pagesAfterFirst > 0, "the first read pages the peak series")
        val second = readHistoryTotals(repo, "r", listOf(esc), range, peakCache = cache)
        assertEquals(pagesAfterFirst, repo.issued.count { it.first == motor }, "the second read is served from the cache")
        for (totals in listOf(first, second)) {
            assertEquals(44.0, totals.of(HistoryTotalKind.MAX_KMH)!!.value)
            assertTrue(totals.of(HistoryTotalKind.MAX_KMH)!!.approximate)
            assertEquals(47.0, totals.of(HistoryTotalKind.MOTOR_TEMP_C)!!.value)
        }
    }

    @Test fun aFinishedRideWithoutACacheIsReadEveryTime() = runTest {
        val repo = Repo(origin).apply { series = mapOf(motor to motorSeries()); stats = mapOf(motor to HistoryStats(40.0, 47.0, null, true)) }
        readHistoryTotals(repo, "r", listOf(esc), range)
        val once = repo.issued.count { it.first == motor }
        readHistoryTotals(repo, "r", listOf(esc), range)
        assertEquals(once * 2, repo.issued.count { it.first == motor })
    }

    @Test fun aSpeedStandstillBlipBelowTheMaximumKeepsTheTileExact() = runTest {
        val blip = (0..1900).map { i ->
            at(i * 500L, speed, if (i < 100) (if (i == 50) 0.5 else 0.0) else 40.0 + (i % 5))
        }
        val repo = Repo(origin).apply { series = mapOf(speed to blip); stats = mapOf(speed to HistoryStats(0.0, 44.0, null, true)) }
        val top = readHistoryTotals(repo, "r", listOf(esc), range).of(HistoryTotalKind.MAX_KMH)!!
        assertEquals(44.0, top.value); assertFalse(top.approximate)
    }

    // --- Task 11: peak PWM -------------------------------------------------------------------------------------------
    private val duty = HistorySeriesKey("esc", "duty_percent")
    private val estimatedDuty = HistorySeriesKey("esc", "duty_estimated_percent")
    /** 600 s of a ride at 50-54 %, with a sustained plateau (not a needle) at [peak] and an optional one-sample [needle]. */
    private fun dutySeries(key: HistorySeriesKey, peak: Double, needle: Double? = null) = (0..600).map { i ->
        val t = i * 1000L
        at(t, key, when { i in 300..305 -> peak; needle != null && i == 450 -> needle; else -> 50.0 + (i % 5) })
    }
    private suspend fun totalsOf(vararg series: Pair<HistorySeriesKey, List<HistorySample>>, stats: Map<HistorySeriesKey, HistoryStats> = emptyMap()):
        List<HistoryTotal> {
        val repo = Repo(origin).apply { this.series = series.toMap(); this.stats = stats }
        return readHistoryTotals(repo, "r", listOf(esc), range)
    }

    @Test fun anEstimatedDutySeriesGivesAMarkedPeak() = runTest {
        val top = totalsOf(estimatedDuty to dutySeries(estimatedDuty, 87.0)).of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(87.0, top.value); assertTrue(top.estimated); assertFalse(top.approximate)
    }

    @Test fun aMeasuredDutySeriesGivesAnUnmarkedPeak() = runTest {
        val top = totalsOf(duty to dutySeries(duty, 92.0)).of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(92.0, top.value); assertFalse(top.estimated)
    }

    @Test fun anOldRideWithOnlyDutyPercentIsNotMarkedEvenIfItsValuesWereOnceAnEstimate() = runTest {
        // Rides recorded before the estimate had its own metric cannot be told apart: unmarked is the honest answer.
        val top = totalsOf(stats = mapOf(duty to HistoryStats(0.0, 63.0, null, true))).of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(63.0, top.value); assertFalse(top.estimated)
    }

    @Test fun theLargerOfTheTwoSeriesWinsAndOnlyAnEstimateWinMarksTheTile() = runTest {
        val estimateWins = totalsOf(duty to dutySeries(duty, 40.0), estimatedDuty to dutySeries(estimatedDuty, 87.0))
            .of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(87.0, estimateWins.value); assertTrue(estimateWins.estimated)
        val measuredWins = totalsOf(duty to dutySeries(duty, 92.0), estimatedDuty to dutySeries(estimatedDuty, 87.0))
            .of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(92.0, measuredWins.value); assertFalse(measuredWins.estimated)
    }

    @Test fun noUpperBoundIsAppliedToDuty() = runTest {
        // Sherman / SV field weakening reports 105 %.
        assertEquals(105.0, totalsOf(duty to dutySeries(duty, 105.0)).of(HistoryTotalKind.MAX_DUTY_PERCENT)!!.value)
    }

    @Test fun aSingleSampleDutyNeedleIsNotThePeakAndSaysApproximate() = runTest {
        // Incoherent on purpose: complete = true stats whose maximum is the needle.
        val top = totalsOf(estimatedDuty to dutySeries(estimatedDuty, 87.0, needle = 400.0),
            stats = mapOf(estimatedDuty to HistoryStats(0.0, 400.0, null, true))).of(HistoryTotalKind.MAX_DUTY_PERCENT)!!
        assertEquals(87.0, top.value); assertTrue(top.approximate); assertTrue(top.estimated)
    }

    @Test fun aRideWithNoDutyAtAllHasNoPeakTile() = runTest {
        assertNull(totalsOf().of(HistoryTotalKind.MAX_DUTY_PERCENT))
    }

    @Test fun aZeroDutyPeakIsNotNews() = runTest {
        val all = historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, 0.0)))
        assertTrue(all.isEmpty())
        assertEquals(1, historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, 1.0, estimated = true))).size)
    }

    @Test fun theTilePrefixSaysEstimatedApproximateBothOrNothing() = runTest {
        fun p(estimated: Boolean, approximate: Boolean) =
            historyTotalPrefix(HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, 80.0, approximate, estimated))
        assertEquals("", p(false, false)); assertEquals("~ ", p(true, false))
        assertEquals("≈ ", p(false, true)); assertEquals("~≈ ", p(true, true))
    }

    private fun total(v: Double, approximate: Boolean = false) = HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, v, approximate)

    @Test fun anEqualPeakGoesToTheMeasurementNotTheEstimate() {
        val tie = historyPeakDuty(listOf(total(80.0)), listOf(total(80.0)))!!
        assertEquals(80.0, tie.value); assertFalse(tie.estimated)
        // The list order is not what decides it: a larger estimate still wins.
        assertTrue(historyPeakDuty(listOf(total(80.0)), listOf(total(81.0)))!!.estimated)
    }

    @Test fun aLosersApproximationLeaksOnlyWhenItsMaximumCouldBeTheWinner() {
        // Exact estimate 87 beats an approximate measured series whose maximum is only 40: the tile stays exact.
        val exact = historyPeakDuty(listOf(total(40.0, approximate = true)), listOf(total(87.0)))!!
        assertEquals(87.0, exact.value); assertFalse(exact.approximate); assertTrue(exact.estimated)
        // A loser at or above the winner may hide the real peak: approximate.
        assertTrue(historyPeakDuty(listOf(total(87.0, approximate = true)), listOf(total(87.0)))!!.approximate)
        // The winner's own flag always counts.
        assertTrue(historyPeakDuty(listOf(total(40.0)), listOf(total(87.0, approximate = true)))!!.approximate)
    }

    @Test fun aSignedProducerIsComparedByMagnitude() {
        assertEquals(90.0, historyPeakDuty(listOf(total(-90.0)), listOf(total(50.0)))!!.value)
    }

    @Test fun theCardLineNeverCarriesBothMarks() {
        fun m(estimated: Boolean, approximate: Boolean) = historyCardMark(HistoryTotal(HistoryTotalKind.MAX_DUTY_PERCENT, 80.0, approximate, estimated))
        assertEquals("~ ", m(true, true)); assertEquals("~ ", m(true, false)); assertEquals("≈ ", m(false, true)); assertEquals("", m(false, false))
    }
}

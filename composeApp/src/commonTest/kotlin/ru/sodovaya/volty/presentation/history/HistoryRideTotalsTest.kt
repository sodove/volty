package ru.sodovaya.volty.presentation.history

import kotlin.test.*
import ru.sodovaya.volty.domain.history.*

class HistoryRideTotalsTest {
    private val controller = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val vehicle = HistorySource("vehicle", HistorySourceKind.VEHICLE_BATTERY, "Vehicle")
    private fun stats(min: Double?, max: Double?, energy: Double? = null, complete: Boolean = true) = HistoryStats(min, max, energy, complete)
    private fun Map<HistoryTotalKind, HistoryTotal>.v(kind: HistoryTotalKind) = getValue(kind).value
    private fun totals(sources: List<HistorySource>, stats: Map<HistorySeriesKey, HistoryStats>,
        duration: Long? = 3_600_000, gps: Double? = null,
        moving: HistoryMovingTime? = duration?.let { HistoryMovingTime(it, false) }, batteryUsed: HistoryBatteryUsed? = null) =
        historyRideTotals(sources, stats, duration, historyStatsDistance(sources, stats, duration, gps), moving, batteryUsed).associateBy { it.kind }

    @Test fun controllerAndPackGiveTheFullSet() {
        val t = totals(listOf(controller, pack), mapOf(
            HistorySeriesKey("esc", "trip_distance_km") to stats(1.0, 21.0),
            HistorySeriesKey("esc", "speed_kmh") to stats(0.0, 48.5),
            HistorySeriesKey("esc", "motor_temperature_c") to stats(20.0, 61.0),
            HistorySeriesKey("esc", "esc_temperature_c") to stats(20.0, 44.0),
            // Battery power is negative while discharging; the stored energy is already consumption.
            HistorySeriesKey("pack", "power_w") to stats(-2500.0, 300.0, energy = 400.0),
            // Incoherent on purpose: stats a max - min would read as 28 %. The tile no longer reads them.
            HistorySeriesKey("pack", "soc_percent") to stats(52.0, 80.0),
            HistorySeriesKey("pack", "battery_temperature_c") to stats(18.0, 31.0),
        ), batteryUsed = HistoryBatteryUsed(3.0, false))
        assertEquals(20.0, t.v(HistoryTotalKind.DISTANCE_KM)); assertEquals(3_600_000.0, t.v(HistoryTotalKind.DURATION_MILLIS))
        assertEquals(20.0, t.v(HistoryTotalKind.AVERAGE_KMH)); assertEquals(48.5, t.v(HistoryTotalKind.MAX_KMH))
        assertEquals(400.0, t.v(HistoryTotalKind.ENERGY_WH)); assertEquals(20.0, t.v(HistoryTotalKind.WH_PER_KM))
        assertEquals(3.0, t.v(HistoryTotalKind.BATTERY_USED_PERCENT)); assertEquals(2500.0, t.v(HistoryTotalKind.MAX_POWER_W))
        assertEquals(61.0, t.v(HistoryTotalKind.MOTOR_TEMP_C)); assertEquals(44.0, t.v(HistoryTotalKind.CONTROLLER_TEMP_C))
        assertEquals(31.0, t.v(HistoryTotalKind.BATTERY_TEMP_C))
        assertTrue(t.values.none { it.approximate })
    }

    private val ride = 950_000L
    private val rideStats = mapOf(HistorySeriesKey("esc", "trip_distance_km") to stats(0.0, 5.556))
    private fun speedAt(millis: Long, value: Double?) =
        HistorySample(HistoryTick(1_000_000 + millis, millis, 1), HistorySeriesKey("esc", "speed_kmh"), value,
            if (value == null) HistoryUnknownReason.UNAVAILABLE else null)
    /** Standing 0-100 s, 25 km/h 100-900 s, creeping at 1.5 km/h 900-950 s, at 2 Hz; seconds strictly inside [unknown] are unknown. */
    private fun rideSpeed(unknown: LongRange? = null) = (0..1900L).map { i ->
        val t = i * 500L
        speedAt(t, when {
            unknown != null && t > unknown.first * 1000 && t < unknown.last * 1000 -> null
            t < 100_000 -> 0.0; t < 900_000 -> 25.0; else -> 1.5
        })
    }

    @Test fun theAverageIsOverMovingTimeOnlyAndMovingTimeIsShown() {
        val moving = historyMovingMillis(rideSpeed())
        assertEquals(800_000.0, moving.millis.toDouble(), 500.0); assertFalse(moving.approximate)
        val t = historyRideTotals(listOf(controller), rideStats, ride, historyStatsDistance(listOf(controller), rideStats, ride, null), moving)
            .associateBy { it.kind }
        assertEquals(800_000.0, t.v(HistoryTotalKind.MOVING_MILLIS), 500.0)
        assertEquals(25.0, t.v(HistoryTotalKind.AVERAGE_KMH), 0.1, "today's wall-clock average would be 21.05")
        assertFalse(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
        assertEquals(listOf(HistoryTotalKind.DISTANCE_KM, HistoryTotalKind.DURATION_MILLIS, HistoryTotalKind.MOVING_MILLIS,
            HistoryTotalKind.AVERAGE_KMH), t.keys.toList(), "moving time is listed right after the duration")
    }

    @Test fun unknownSpeedIsNeitherMovingNorStandingAndMakesTheAverageApproximate() {
        // Incoherent on purpose: the counter keeps rising through the 300-400 s the speed is unknown for.
        val moving = historyMovingMillis(rideSpeed(unknown = 300L..400L))
        assertEquals(700_000L, moving.millis); assertTrue(moving.approximate)
        val t = historyRideTotals(listOf(controller), rideStats, ride, HistoryRideDistance(5.556, false), moving).associateBy { it.kind }
        assertTrue(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
        assertTrue(t.getValue(HistoryTotalKind.MOVING_MILLIS).approximate)
    }

    @Test fun aGapLongerThanFiveSecondsCreditsNothingAndAClockBreakDoesNotEither() {
        val gap = listOf(speedAt(0, 20.0), speedAt(5_000, 20.0), speedAt(10_001, 20.0))
        assertEquals(5_000L, historyMovingMillis(gap).millis); assertTrue(historyMovingMillis(gap).approximate)
        val other = HistorySample(HistoryTick(1_001_000, 1_000, 2), HistorySeriesKey("esc", "speed_kmh"), 20.0)
        assertEquals(0L, historyMovingMillis(listOf(speedAt(0, 20.0), other)).millis)
        assertEquals(0L, historyMovingMillis(listOf(speedAt(0, 3.0), speedAt(500, 3.0), speedAt(1000, 3.0))).millis, "3.0 is not above 3")
    }

    @Test fun anUnknownEndOnEitherSideCreditsNothingExactly() {
        val toNull = historyMovingMillis(listOf(speedAt(0, 20.0), speedAt(1_000, null)))
        assertEquals(0L, toNull.millis); assertTrue(toNull.approximate)
        val fromNull = historyMovingMillis(listOf(speedAt(0, null), speedAt(1_000, 20.0)))
        assertEquals(0L, fromNull.millis); assertTrue(fromNull.approximate)
        val around = historyMovingMillis(listOf(speedAt(0, 20.0), speedAt(1_000, null), speedAt(2_000, 20.0)))
        assertEquals(0L, around.millis); assertTrue(around.approximate)
    }

    @Test fun aSampleThatGoesBackwardsInTimeCreditsNothing() {
        val back = historyMovingMillis(listOf(speedAt(2_000, 20.0), speedAt(1_000, 20.0)))
        assertEquals(0L, back.millis); assertTrue(back.approximate)
    }

    @Test fun anApproximateDistanceStaysApproximateOverAnExactMovingTime() {
        val t = historyRideTotals(listOf(controller), rideStats, ride, HistoryRideDistance(5.556, true), HistoryMovingTime(800_000, false))
            .associateBy { it.kind }
        assertTrue(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
        assertFalse(t.getValue(HistoryTotalKind.MOVING_MILLIS).approximate)
    }

    @Test fun noSpeedSeriesKeepsTheWallClockAverageAndSaysApproximate() {
        val t = historyRideTotals(listOf(controller), rideStats, ride, HistoryRideDistance(5.556, false), null).associateBy { it.kind }
        assertEquals(21.05, t.v(HistoryTotalKind.AVERAGE_KMH), 0.05)
        assertTrue(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
        assertNull(t[HistoryTotalKind.MOVING_MILLIS])
    }

    @Test fun noDistanceTileMeansNoAverageEvenWithMovingTime() {
        val t = historyRideTotals(listOf(controller), rideStats, ride, null, HistoryMovingTime(800_000, false)).associateBy { it.kind }
        assertNull(t[HistoryTotalKind.AVERAGE_KMH]); assertNull(t[HistoryTotalKind.DISTANCE_KM])
        val still = historyRideTotals(listOf(controller), rideStats, ride, HistoryRideDistance(1.0, false), HistoryMovingTime(0, false))
        assertNull(still.firstOrNull { it.kind == HistoryTotalKind.AVERAGE_KMH }, "no moving time: nothing to divide by")
    }

    @Test fun movingTimeBelowOneSecondIsNotShown() {
        val shown = historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.MOVING_MILLIS, 999.0),
            HistoryTotal(HistoryTotalKind.DURATION_MILLIS, 5_000.0))).map { it.kind }
        assertEquals(listOf(HistoryTotalKind.DURATION_MILLIS), shown)
        assertEquals(1, historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.MOVING_MILLIS, 1_000.0))).size)
    }

    @Test fun aVehicleAggregateIsNeverSummedWithThePacksItAggregates() {
        // Deliberately incoherent: no producer emits an aggregate whose energy differs from its pack's.
        val keys = historyTotalsKeys(listOf(vehicle, pack))
        assertTrue(keys.none { it.sourceId == "pack" && it.metricId == "power_w" })
        val t = totals(listOf(vehicle, pack), mapOf(
            HistorySeriesKey("vehicle", "power_w") to stats(-1000.0, 0.0, energy = 100.0),
            HistorySeriesKey("pack", "power_w") to stats(-1000.0, 0.0, energy = 7777.0),
        ))
        assertEquals(100.0, t.v(HistoryTotalKind.ENERGY_WH))
    }

    @Test fun parallelPacksAddEnergyButNotPeakPower() {
        val second = HistorySource("pack2", HistorySourceKind.BATTERY, "Pack 2")
        val t = totals(listOf(pack, second), mapOf(
            HistorySeriesKey("pack", "power_w") to stats(-1000.0, 0.0, energy = 100.0),
            HistorySeriesKey("pack2", "power_w") to stats(-900.0, 0.0, energy = 80.0),
        ))
        assertEquals(180.0, t.v(HistoryTotalKind.ENERGY_WH))
        assertNull(t[HistoryTotalKind.MAX_POWER_W], "the packs' combined peak is not knowable from their separate peaks")
    }

    @Test fun distanceFallsBackToOdometerThenGpsAndMissingDataOmitsTiles() {
        val odo = totals(listOf(controller), mapOf(HistorySeriesKey("esc", "odometer_km") to stats(1000.0, 1012.5)))
        assertEquals(12.5, odo.v(HistoryTotalKind.DISTANCE_KM))
        val gps = totals(listOf(pack), emptyMap(), gps = 7.0)
        assertEquals(7.0, gps.v(HistoryTotalKind.DISTANCE_KM)); assertEquals(7.0, gps.v(HistoryTotalKind.AVERAGE_KMH))
        val none = totals(listOf(pack), emptyMap(), duration = null)
        assertTrue(none.isEmpty(), "nothing measured, nothing shown — not a zero")
    }

    @Test fun incompleteStatsAreMarkedApproximate() {
        val t = totals(listOf(controller), mapOf(HistorySeriesKey("esc", "trip_distance_km") to stats(0.0, 10.0, complete = false),
            HistorySeriesKey("esc", "speed_kmh") to stats(0.0, 30.0, complete = false)))
        assertTrue(t.getValue(HistoryTotalKind.DISTANCE_KM).approximate)
        assertTrue(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
        assertTrue(t.getValue(HistoryTotalKind.MAX_KMH).approximate)
    }

    @Test fun shortRidesHaveNoConsumptionFigure() {
        val t = totals(listOf(controller, pack), mapOf(HistorySeriesKey("esc", "trip_distance_km") to stats(0.0, 0.2),
            HistorySeriesKey("pack", "power_w") to stats(-500.0, 0.0, energy = 5.0)))
        assertNull(t[HistoryTotalKind.WH_PER_KM]); assertEquals(5.0, t.v(HistoryTotalKind.ENERGY_WH))
    }

    @Test fun gpsDistanceSkipsSilencesAndPoorFixes() {
        fun fix(sec: Long, lat: Double, accuracy: Double = 5.0) = HistoryGpsFix(HistoryTick(sec * 1000, sec * 1000, 1), lat, 37.0, accuracy, null)
        // 0.001 deg latitude ~ 111 m.
        val km = historyGpsDistanceKm(listOf(fix(0, 55.000), fix(5, 55.001), fix(600, 55.101), fix(605, 55.102, accuracy = 200.0), fix(610, 55.103)))
        assertEquals(0.111, km, 0.002)
    }

    @Test fun valuesThatWouldPrintAsZeroAreNotShown() {
        val shown = historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.DISTANCE_KM, 0.04), HistoryTotal(HistoryTotalKind.AVERAGE_KMH, 1.2),
            HistoryTotal(HistoryTotalKind.ENERGY_WH, 0.3), HistoryTotal(HistoryTotalKind.BATTERY_USED_PERCENT, 0.2), HistoryTotal(HistoryTotalKind.BATTERY_USED_PERCENT, -0.4),
            HistoryTotal(HistoryTotalKind.DURATION_MILLIS, 120_000.0), HistoryTotal(HistoryTotalKind.MAX_KMH, 1.9))).map { it.kind }
        assertEquals(listOf(HistoryTotalKind.DURATION_MILLIS, HistoryTotalKind.MAX_KMH), shown,
            "an average over a distance too short to show is hidden with it")
        assertEquals(2, historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.DISTANCE_KM, 0.1), HistoryTotal(HistoryTotalKind.AVERAGE_KMH, 6.0))).size)
    }

    @Test fun aChargingSessionsSignedTileIsShownAndTheStatsAloneMakeNoBatteryTile() {
        val stats = mapOf(HistorySeriesKey("pack", "soc_percent") to stats(52.0, 80.0))
        assertNull(totals(listOf(pack), stats)[HistoryTotalKind.BATTERY_USED_PERCENT], "max - min of stats is not what a ride used")
        val shown = historyVisibleTotals(listOf(HistoryTotal(HistoryTotalKind.BATTERY_USED_PERCENT, -12.0)))
        assertEquals(listOf(-12.0), shown.map { it.value })
    }

    @Test fun aRideWithNothingChosenChartsSpeedAndPower() {
        assertEquals(listOf(HistorySeriesKey("esc", "speed_kmh"), HistorySeriesKey("pack", "power_w")),
            historyDefaultMetrics(listOf(controller, pack)))
        // The aggregate, never also its contributing pack.
        assertEquals(listOf(HistorySeriesKey("vehicle", "power_w")), historyDefaultMetrics(listOf(vehicle, pack)))
        assertEquals(listOf(HistorySeriesKey("esc", "speed_kmh"), HistorySeriesKey("esc", "controller_power_w")),
            historyDefaultMetrics(listOf(controller)), "no battery: the controller's power")
        assertTrue(historyDefaultMetrics(emptyList()).isEmpty())
    }

    @Test fun aPackPowerSeriesWithNoKnownValueIsNotTheDefaultChart() {
        // The 17:33 ride: a Begode T4's placeholder BMS made pack power_w all unknown while the Peak power tile used the
        // controller's series. The default chart took the pack's and printed "No data".
        val expected = listOf(HistorySeriesKey("esc", "speed_kmh"), HistorySeriesKey("esc", "controller_power_w"))
        assertEquals(expected, historyDefaultMetrics(listOf(controller, pack)) { false })
        assertEquals(listOf(HistorySeriesKey("esc", "speed_kmh"), HistorySeriesKey("pack", "power_w")),
            historyDefaultMetrics(listOf(controller, pack)) { it == HistorySeriesKey("pack", "power_w") })
        // Nothing to fall back to: the pack's series stays (an empty chart is the honest answer, not another series).
        assertEquals(listOf(HistorySeriesKey("pack", "power_w")), historyDefaultMetrics(listOf(pack)) { false })
        assertEquals(HistorySeriesKey("pack", "power_w"), historyDefaultPackPowerKey(listOf(controller, pack)))
        assertNull(historyDefaultPackPowerKey(listOf(controller)))
    }
}

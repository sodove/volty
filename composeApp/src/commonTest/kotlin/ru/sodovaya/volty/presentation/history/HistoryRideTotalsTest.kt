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
        duration: Long? = 3_600_000, gps: Double? = null) = historyRideTotals(sources, stats, duration, gps).associateBy { it.kind }

    @Test fun controllerAndPackGiveTheFullSet() {
        val t = totals(listOf(controller, pack), mapOf(
            HistorySeriesKey("esc", "trip_distance_km") to stats(1.0, 21.0),
            HistorySeriesKey("esc", "speed_kmh") to stats(0.0, 48.5),
            HistorySeriesKey("esc", "motor_temperature_c") to stats(20.0, 61.0),
            HistorySeriesKey("esc", "esc_temperature_c") to stats(20.0, 44.0),
            // Battery power is negative while discharging; the stored energy is already consumption.
            HistorySeriesKey("pack", "power_w") to stats(-2500.0, 300.0, energy = 400.0),
            HistorySeriesKey("pack", "soc_percent") to stats(52.0, 80.0),
            HistorySeriesKey("pack", "battery_temperature_c") to stats(18.0, 31.0),
        ))
        assertEquals(20.0, t.v(HistoryTotalKind.DISTANCE_KM)); assertEquals(3_600_000.0, t.v(HistoryTotalKind.DURATION_MILLIS))
        assertEquals(20.0, t.v(HistoryTotalKind.AVERAGE_KMH)); assertEquals(48.5, t.v(HistoryTotalKind.MAX_KMH))
        assertEquals(400.0, t.v(HistoryTotalKind.ENERGY_WH)); assertEquals(20.0, t.v(HistoryTotalKind.WH_PER_KM))
        assertEquals(28.0, t.v(HistoryTotalKind.BATTERY_USED_PERCENT)); assertEquals(2500.0, t.v(HistoryTotalKind.MAX_POWER_W))
        assertEquals(61.0, t.v(HistoryTotalKind.MOTOR_TEMP_C)); assertEquals(44.0, t.v(HistoryTotalKind.CONTROLLER_TEMP_C))
        assertEquals(31.0, t.v(HistoryTotalKind.BATTERY_TEMP_C))
        assertTrue(t.values.none { it.approximate })
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
}

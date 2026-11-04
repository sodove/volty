package ru.sodovaya.volty.presentation.history

import kotlin.test.*
import ru.sodovaya.volty.domain.history.*

/** The ride summary and default chart of a battery-only ride that recorded GPS speed (2026-11-04). */
class HistoryGpsSpeedTotalsTest {
    private val pack = HistorySource("pack", HistorySourceKind.BATTERY, "Pack")
    private val esc = HistorySource("esc", HistorySourceKind.CONTROLLER, "ESC")
    private val gps = historyGpsSpeedSource
    private val gpsKey = HistorySeriesKey(gps.id, HISTORY_GPS_SPEED_METRIC)
    private val escKey = HistorySeriesKey("esc", "speed_kmh")
    private fun stats(min: Double, max: Double) = HistoryStats(min, max, null, true)
    private fun totals(sources: List<HistorySource>, stats: Map<HistorySeriesKey, HistoryStats>) =
        historyRideTotals(sources, stats, 3_600_000, HistoryRideDistance(10.0, false), HistoryMovingTime(1_800_000, false))
            .associateBy { it.kind }

    @Test fun a_battery_only_ride_charts_and_peaks_its_gps_speed() {
        assertEquals(listOf(gpsKey, HistorySeriesKey("pack", "power_w")), historyDefaultMetrics(listOf(pack, gps)))
        assertTrue(gpsKey in historyTotalsKeys(listOf(pack, gps)))
        val t = totals(listOf(pack, gps), mapOf(gpsKey to stats(0.0, 27.0)))
        assertEquals(27.0, t.getValue(HistoryTotalKind.MAX_KMH).value)
        assertEquals(20.0, t.getValue(HistoryTotalKind.AVERAGE_KMH).value, "10 km over 30 moving minutes")
        assertFalse(t.getValue(HistoryTotalKind.AVERAGE_KMH).approximate)
    }

    @Test fun a_ride_with_a_controller_never_reads_gps_speed_even_if_both_series_exist() {
        // Incoherent: the recorder never registers GPS speed beside a controller. If a ride had both, a 90 km/h GPS
        // peak must still not become the top speed, nor its series the default chart, nor a stats key.
        val sources = listOf(esc, pack, gps)
        assertEquals(escKey, historyDefaultMetrics(sources).first())
        assertFalse(gpsKey in historyTotalsKeys(sources))
        assertEquals(listOf(escKey), historySpeedKeys(sources))
        val t = totals(sources, mapOf(escKey to stats(0.0, 41.0), gpsKey to stats(0.0, 90.0)))
        assertEquals(41.0, t.getValue(HistoryTotalKind.MAX_KMH).value)
    }

    @Test fun a_battery_only_ride_recorded_before_gps_speed_is_unchanged() {
        assertEquals(listOf(HistorySeriesKey("pack", "power_w")), historyDefaultMetrics(listOf(pack)))
        assertTrue(historySpeedKeys(listOf(pack)).isEmpty())
        assertNull(totals(listOf(pack), emptyMap())[HistoryTotalKind.MAX_KMH])
    }

    @Test fun the_gps_top_speed_is_marked_gps_and_a_controller_one_is_not() {
        assertTrue(totals(listOf(pack, gps), mapOf(gpsKey to stats(0.0, 27.0))).getValue(HistoryTotalKind.MAX_KMH).gps)
        assertFalse(totals(listOf(esc, pack), mapOf(escKey to stats(0.0, 41.0))).getValue(HistoryTotalKind.MAX_KMH).gps)
    }

    @Test fun an_empty_gps_speed_series_is_left_out_of_the_default_chart() {
        // Review M7: a ride recorded without location permission has the source and no value.
        assertEquals(listOf(HistorySeriesKey("pack", "power_w"), gpsKey), historyDefaultValueProbes(listOf(pack, gps)))
        assertEquals(listOf(HistorySeriesKey("pack", "power_w")), historyDefaultMetrics(listOf(pack, gps)) { it != gpsKey })
        assertEquals(listOf(gpsKey, HistorySeriesKey("pack", "power_w")), historyDefaultMetrics(listOf(pack, gps)) { true })
        // A controller ride probes only its pack, as before.
        assertEquals(listOf(HistorySeriesKey("pack", "power_w")), historyDefaultValueProbes(listOf(esc, pack, gps)))
    }
}

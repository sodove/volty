package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryStatisticsTest {
    private val key = HistorySeriesKey("battery", "power_w")
    private fun sample(time: Long, value: Double?) = HistorySample(HistoryTick(10000 + time, time, 1), key, value,
        if (value == null) HistoryUnknownReason.PARTIAL_SOURCE else null)
    @Test fun fullStreamPeakSurvivesDetailCadenceAndEnergyRetainsRegenerationSign() {
        val raw = listOf(sample(0, -100.0), sample(100, -4200.0), sample(500, 100.0), sample(1000, 100.0))
        val stats = historyStats(raw, emptyList(), -1.0)
        assertEquals(-4200.0, stats.minimum)
        assertEquals(100.0, stats.maximum)
        assertEquals((215.0 + 820.0 - 50.0) / 3600, assertNotNull(stats.energyWh), 1e-9)
        assertTrue(stats.complete)
        assertEquals(-100.0 / 3600, historyStats(listOf(sample(0, 100.0), sample(1000, 100.0)), emptyList(), -1.0).energyWh)
    }
    @Test fun unknownAndBleGapPreventIntegratingFabricatedIntervals() {
        val rows = listOf(sample(0, 100.0), sample(1000, 100.0), sample(2000, null), sample(3000, 100.0), sample(4000, 100.0))
        val marker = HistoryMarker(HistoryTick(13500, 3500, 1), HistoryMarkerKind.BLE_GAP)
        val stats = historyStats(rows, listOf(marker), 1.0)
        assertEquals(100.0 / 3600, stats.energyWh)
        assertFalse(stats.complete)
        assertNull(historyStats(listOf(sample(0, 0.0)), emptyList()).energyWh)
        assertEquals(0.0, historyStats(listOf(sample(0, 0.0)), emptyList()).maximum)
    }
    @Test fun cadenceOutageClockRegressionAndOverflowNeverBecomeCompleteEnergy() {
        val rows = listOf(sample(0, 100.0), sample(10000, 100.0))
        assertNull(historyStats(rows, emptyList(), 1.0).energyWh)
        assertFalse(historyStats(rows, emptyList(), 1.0).complete)
        assertFalse(historyStats(listOf(sample(1000, 100.0), sample(0, 100.0)), emptyList(), 1.0).complete)
        val overflow = HistoryMarker(HistoryTick(10500, 500, 1), HistoryMarkerKind.OVERFLOW)
        assertNull(historyStats(listOf(sample(0, 100.0), sample(1000, 100.0)), listOf(overflow), 1.0).energyWh)
        assertFailsWith<IllegalArgumentException> { historyStats(listOf(sample(0, 1.0), sample(10, 2.0).copy(key = key.copy(sourceId = "other"))), emptyList()) }
    }

    @Test fun finiteExtremeReadingsDoNotOverflowBeforeTimeScaling() {
        val stats = historyStats(listOf(sample(0, Double.MAX_VALUE), sample(1000, Double.MAX_VALUE)), emptyList(), 1.0)
        assertTrue(assertNotNull(stats.energyWh).isFinite())
        assertEquals(Double.MAX_VALUE / 3600, stats.energyWh, Double.MAX_VALUE * 1e-14)
        assertTrue(stats.complete)
    }

    @Test fun gpsLossDoesNotEraseBleEnergyButClockJumpBreaksContinuity() {
        val rows = listOf(sample(0, 100.0), sample(1000, 100.0))
        val gpsGap = HistoryMarker(HistoryTick(10500, 500, 1), HistoryMarkerKind.GPS_GAP)
        assertEquals(100.0 / 3600, historyStats(rows, listOf(gpsGap), 1.0).energyWh)
        assertTrue(historyStats(rows, listOf(gpsGap), 1.0).complete)
        val clockJump = rows.last().copy(tick = rows.last().tick.copy(epochMillis = 70000))
        assertNull(historyStats(listOf(rows.first(), clockJump), emptyList(), 1.0).energyWh)
        assertFailsWith<IllegalArgumentException> {
            historyStats(listOf(rows.first().copy(key = key.copy(metricId = "speed_kmh"))), emptyList(), 1.0)
        }
    }
}

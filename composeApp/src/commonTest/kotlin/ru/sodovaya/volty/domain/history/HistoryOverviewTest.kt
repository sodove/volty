package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryOverviewTest {
    private val key = HistorySeriesKey("battery", "power_w")
    private fun rows(count: Int) = (0 until count).map {
        HistorySample(HistoryTick(it * 100L, it * 100L, 1), key, if (it == 501) 4200.0 else if (it == 702) -1500.0 else 10.0)
    }
    @Test fun overviewKeepsNarrowPeakRegenerationAndLastPointWithinBudget() {
        val input = rows(1000); val projected = historyOverview(input, emptyList())
        assertTrue(projected.size <= 600)
        assertEquals(input.first(), projected.first())
        assertEquals(input.last(), projected.last())
        assertEquals(4200.0, projected.mapNotNull { it.value }.maxOrNull())
        assertEquals(-1500.0, projected.mapNotNull { it.value }.minOrNull())
    }
    @Test fun markerBreakIsVisibleAndCannotBecomeContinuousLineAfterProjection() {
        val input = rows(20)
        val gap = HistoryMarker(HistoryTick(950, 950, 1), HistoryMarkerKind.BLE_GAP)
        val projected = historyOverview(input, listOf(gap), 12)
        assertTrue(projected.size <= 12)
        assertTrue(projected.any { it.unknown == HistoryUnknownReason.GAP })
        val gapIndex = projected.indexOfFirst { it.unknown == HistoryUnknownReason.GAP }
        assertTrue(projected.take(gapIndex).all { it.tick.elapsedMillis <= 900 })
        assertTrue(projected.drop(gapIndex + 1).all { it.tick.elapsedMillis >= 1000 })
    }
    @Test fun tooManyBoundariesStayBoundedWithoutInventingConnectedSegments() {
        val input = rows(1000)
        val gaps = (1 until 1000 step 2).map { HistoryMarker(input[it].tick, HistoryMarkerKind.BLE_GAP) }
        val projected = historyOverview(input, gaps, 9)
        assertTrue(projected.size <= 9)
        assertEquals(4200.0, projected.mapNotNull { it.value }.maxOrNull())
        assertEquals(-1500.0, projected.mapNotNull { it.value }.minOrNull())
        assertTrue(projected.zipWithNext().none { (a, b) -> a.value != null && b.value != null })
    }
}

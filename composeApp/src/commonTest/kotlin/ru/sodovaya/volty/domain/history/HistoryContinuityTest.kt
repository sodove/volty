package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryContinuityTest {
    private val key = HistorySeriesKey("pack", "current_a")
    private val known = HistorySample(HistoryTick(10000, 100, 1), key, 0.0)
    @Test fun cursorRequiresNearbyEarnedValueOnSameMonotonicOrigin() {
        assertEquals(known, nearestHistorySample(listOf(known), HistoryTick(99000, 120, 1), 50))
        assertNull(nearestHistorySample(listOf(known), HistoryTick(10100, 1000, 1), 50))
        assertNull(nearestHistorySample(listOf(known), HistoryTick(10000, 100, 2), 50))
    }
    @Test fun closestUnknownCannotBeSkippedInFavorOfKnownFartherSample() {
        val unknown = HistorySample(HistoryTick(10020, 120, 1), key, null, HistoryUnknownReason.UNAVAILABLE)
        assertNull(nearestHistorySample(listOf(known, unknown), unknown.tick, 100))
        assertFailsWith<IllegalArgumentException> { nearestHistorySample(listOf(known), known.tick, -1) }
    }
    @Test fun exactUnknownTieAndDifferentCellKeysCannotShowEarnedValue() {
        val unknown = HistorySample(known.tick, key, null, HistoryUnknownReason.PARTIAL_SOURCE)
        assertNull(nearestHistorySample(listOf(known, unknown), known.tick, 0))
        assertFailsWith<IllegalArgumentException> {
            nearestHistorySample(listOf(known, known.copy(key = key.copy(cellIndex = 1))), known.tick, 100)
        }
    }
}

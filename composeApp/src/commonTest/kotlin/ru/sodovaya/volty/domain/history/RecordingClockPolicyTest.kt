package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordingClockPolicyTest {
    @Test fun axisUsesElapsedTimeWhilePreservingRawWallTime() {
        val origin = HistoryTick(10000, 100, 1)
        val changed = HistoryTick(70000, 200, 1)
        assertEquals(10100L, historyAxisEpochMillis(origin, changed))
        assertEquals(70000L, changed.epochMillis)
        assertEquals(10100L, historyAxisEpochMillis(origin, HistoryTick(1, 200, 1)))
    }

    @Test fun axisRejectsUnrelatedOriginAndOverflow() {
        val origin = HistoryTick(10000, 100, 1)
        assertNull(historyAxisEpochMillis(origin, HistoryTick(10001, 99, 1)))
        assertNull(historyAxisEpochMillis(origin, HistoryTick(10001, 200, 2)))
        assertNull(historyAxisEpochMillis(HistoryTick(Long.MAX_VALUE - 1, 0, 1), HistoryTick(42, 2, 1)))
    }

    @Test fun forwardWallClockJumpDoesNotBridgeTheTimeline() {
        val first = HistoryTick(10000, 100, 1)
        assertTrue(clockBoundary(first, HistoryTick(70000, 200, 1)))
        assertFalse(clockBoundary(first, HistoryTick(11000, 1100, 1)))
    }

    @Test fun backwardsWallTimeOrMonotonicGenerationCreatesBoundary() {
        val first = HistoryTick(2000, 100, 1)
        assertTrue(clockBoundary(first, HistoryTick(1000, 200, 1)))
        assertTrue(clockBoundary(first, HistoryTick(3000, 0, 2)))
        assertTrue(clockBoundary(first, HistoryTick(3000, 90, 1)))
        assertFalse(clockBoundary(first, HistoryTick(3000, 200, 1)))
        assertFalse(clockBoundary(first, first))
    }
}

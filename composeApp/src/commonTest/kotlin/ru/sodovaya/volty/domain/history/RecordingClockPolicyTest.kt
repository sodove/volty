package ru.sodovaya.volty.domain.history

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingClockPolicyTest {
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

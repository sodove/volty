package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistoryRange
import kotlin.test.*

class HistoryWindowPolicyTest {
    private val bounds = HistoryRange(1000, 11000)
    @Test fun zoomKeepsTheSelectedAnchorAtTheSameFraction() {
        assertEquals(HistoryRange(3500, 8500), historyTransformWindow(bounds, bounds, zoom = 2.0))
        assertEquals(HistoryRange(1000, 6000), historyTransformWindow(bounds, bounds, zoom = 2.0, anchor = 0.0))
        assertEquals(HistoryRange(6000, 11000), historyTransformWindow(bounds, bounds, zoom = 2.0, anchor = 1.0))
    }
    @Test fun panUsesCurrentWindowSpanAndClampsWithoutShrinking() {
        val window = HistoryRange(4000, 8000)
        assertEquals(HistoryRange(3000, 7000), historyTransformWindow(window, bounds, pan = 0.25))
        assertEquals(HistoryRange(5000, 9000), historyTransformWindow(window, bounds, pan = -0.25))
        assertEquals(HistoryRange(1000, 5000), historyTransformWindow(window, bounds, pan = 100.0))
        assertEquals(HistoryRange(7000, 11000), historyTransformWindow(window, bounds, pan = -100.0))
    }
    @Test fun limitsPermitFullRideAndPreventEmptyZoom() {
        assertEquals(HistoryRange(5500, 6500), historyTransformWindow(bounds, bounds, zoom = 1e300))
        assertEquals(bounds, historyTransformWindow(HistoryRange(4000, 8000), bounds, zoom = 1e-300))
        assertEquals(bounds, historyTransformWindow(bounds, bounds, pan = 1.0))
    }
    @Test fun shortAndPointRecordingsNeverAcquireInventedDuration() {
        val short = HistoryRange(20, 520)
        assertEquals(short, historyTransformWindow(short, short, zoom = 8.0))
        val point = HistoryRange(42, 42)
        assertEquals(point, historyTransformWindow(point, point, zoom = 2.0, pan = 0.5))
    }
    @Test fun invalidInputsAndStaleBoundsFailClosed() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0))
            assertNull(historyTransformWindow(bounds, bounds, zoom = value))
        assertNull(historyTransformWindow(bounds, bounds, pan = Double.NaN))
        assertNull(historyTransformWindow(bounds, bounds, anchor = Double.POSITIVE_INFINITY))
        assertNull(historyTransformWindow(HistoryRange(0, 5000), bounds))
        assertNull(historyTransformWindow(bounds, bounds, minimumMillis = 0))
    }
    @Test fun relativeArithmeticKeepsLargeEpochEndpointsExact() {
        val start = Long.MAX_VALUE - 10000
        val full = HistoryRange(start, Long.MAX_VALUE)
        assertEquals(HistoryRange(start + 2500, start + 7500), historyTransformWindow(full, full, zoom = 2.0))
        val current = HistoryRange(start + 1001, start + 5001)
        assertEquals(current, historyTransformWindow(current, full))
        val low = HistoryRange(Long.MIN_VALUE, Long.MIN_VALUE + 10000)
        assertEquals(HistoryRange(Long.MIN_VALUE + 2500, Long.MIN_VALUE + 7500), historyTransformWindow(low, low, zoom = 2.0))
    }
    @Test fun overflowingClockSpanIsUnavailableRatherThanWrapping() {
        val full = HistoryRange(Long.MIN_VALUE, Long.MAX_VALUE)
        assertNull(historyTransformWindow(full, full, zoom = 2.0))
    }
    @Test fun finiteOffscreenAnchorAndHugePanRemainWithinEarnedBounds() {
        assertEquals(HistoryRange(1000, 6000), historyTransformWindow(bounds, bounds, zoom = 2.0, anchor = -5.0))
        assertEquals(HistoryRange(6000, 11000), historyTransformWindow(bounds, bounds, zoom = 2.0, anchor = 5.0))
        assertEquals(HistoryRange(1000, 5000), historyTransformWindow(HistoryRange(4000, 8000), bounds, pan = Double.MAX_VALUE))
    }
}

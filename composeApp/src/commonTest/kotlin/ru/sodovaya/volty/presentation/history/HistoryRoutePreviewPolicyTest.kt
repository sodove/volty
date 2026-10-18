package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.math.abs
import kotlin.test.*

class HistoryRoutePreviewPolicyTest {
    private fun fix(lat: Double, lon: Double) = HistoryGpsFix(HistoryTick(1000, 0, 1), lat, lon, 3.0, null)
    private fun segment(vararg fixes: HistoryGpsFix) = HistoryMapSegment(fixes.toList())

    @Test fun emptyRouteAndUnavailableCanvasDoNotInventAPath() {
        assertTrue(historyPreviewPaths(emptyList(), 100.0, 80.0).isEmpty())
        assertTrue(historyPreviewPaths(listOf(segment(fix(56.0,60.0))), 0.0, 80.0).isEmpty())
        assertTrue(historyPreviewPaths(listOf(segment(fix(56.0,60.0))), 10.0, 10.0).isEmpty())
    }

    @Test fun stationaryRideIsCenteredAndAlwaysFinite() {
        val result = historyPreviewPaths(listOf(segment(fix(56.0,60.0), fix(56.0,60.0))), 100.0, 80.0)
        assertEquals(listOf(listOf(HistoryPreviewPoint(50.0,40.0), HistoryPreviewPoint(50.0,40.0))), result)
    }

    @Test fun gpsSegmentsStaySeparateAndFitOneSharedAspectPreservingFrame() {
        val result = historyPreviewPaths(listOf(segment(fix(56.0,60.0),fix(56.001,60.002)),
            segment(fix(56.004,60.006),fix(56.005,60.008))), 100.0, 80.0)
        assertEquals(listOf(2,2), result.map { it.size })
        assertTrue(result.flatten().all { it.x in 8.0..92.0 && it.y in 8.0..72.0 })
        assertTrue(result.first().first().y > result.last().last().y)
        assertTrue(result.first().first().x < result.last().last().x)
    }

    @Test fun longitudeWrapUsesShortArcAndPreservesStoredOrder() {
        val points = historyPreviewPaths(listOf(segment(fix(0.0,170.0),fix(0.0,179.9),fix(0.0,-179.9))), 120.0, 80.0).single()
        assertTrue(points[0].x < points[1].x && points[1].x < points[2].x)
        assertTrue(abs(points[2].x - points[1].x) < 4.0)
    }

    @Test fun polarCoordinatesStayFiniteAndInvalidViewportIsRejected() {
        val result = historyPreviewPaths(listOf(segment(fix(-90.0,0.0),fix(90.0,0.0))), 100.0, 80.0)
        assertTrue(result.flatten().all { it.x.isFinite() && it.y.isFinite() && it.y in 8.0..72.0 })
        assertFailsWith<IllegalArgumentException> { historyPreviewPaths(emptyList(), Double.NaN, 80.0) }
        assertFailsWith<IllegalArgumentException> { historyPreviewPaths(emptyList(), 100.0, 80.0, -1.0) }
    }

    @Test fun durationUsesEarnedClockAndDoesNotBorrowNowForAnOpenOrInvalidRide() {
        val start = HistoryTick(1000, 10, 1)
        val ride = HistoryRide("r", "v", start, HistoryTick(3_602_000,1010,1), HistoryRideStatus.COMPLETED)
        assertEquals(1000L, historyRideDurationMillis(ride))
        assertNull(historyRideDurationMillis(ride.copy(ended = null, status = HistoryRideStatus.RECORDING)))
        assertNull(historyRideDurationMillis(ride.copy(ended = ride.ended!!.copy(clockGeneration = 2))))
        val legacy = ride.copy(status = HistoryRideStatus.LEGACY, ended = HistoryTick(3000,0,0))
        assertEquals(2000L, historyRideDurationMillis(legacy))
        assertNull(historyRideDurationMillis(legacy.copy(ended = HistoryTick(999,0,0))))
        assertNull(historyRideDurationMillis(legacy.copy(started = HistoryTick(Long.MIN_VALUE,0,0), ended = HistoryTick(Long.MAX_VALUE,0,0))))
    }

    @Test fun timelineUsesRelativeFractionInsteadOfLosingShortRideInFloatEpoch() {
        val start = 1_792_281_600_000L
        val range = HistoryRange(start, start + 4000)
        assertEquals(start + 1000, historyTimeAtFraction(range, 0.25f))
        assertEquals(0.25f, historyTimeFraction(range, start + 1000))
        assertEquals(start, historyTimeAtFraction(range, -1f))
        assertEquals(range.endEpochMillis, historyTimeAtFraction(range, 2f))
        assertEquals(0f, historyTimeFraction(range, start - 100))
        assertEquals(1f, historyTimeFraction(range, range.endEpochMillis + 100))
    }

    @Test fun timelineRejectsNonFiniteOrOverflowedInputAndHandlesEarnedZeroInterval() {
        val zero = HistoryRange(1000,1000)
        assertEquals(1000L, historyTimeAtFraction(zero, 0.5f))
        assertEquals(0f, historyTimeFraction(zero,1000))
        assertNull(historyTimeAtFraction(zero,Float.NaN))
        assertNull(historyTimeAtFraction(zero,Float.POSITIVE_INFINITY))
        val overflow = HistoryRange(Long.MIN_VALUE,Long.MAX_VALUE)
        assertNull(historyTimeAtFraction(overflow,0.5f))
        assertNull(historyTimeFraction(overflow,0))
    }
}

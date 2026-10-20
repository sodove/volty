package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistoryRange
import kotlin.test.*

class HistoryChartViewportTest {
    private val points = listOf(HistoryChartPoint(1000, 60.0, 0f, 1f),
        HistoryChartPoint(3000, 70.0, 0.5f, 0.5f), HistoryChartPoint(5000, 80.0, 1f, 0f))
    private val scene = HistoryChartScene(listOf(points), 60.0, 80.0, true)

    @Test fun viewportMovesExistingGeometryWithoutInventingBoundarySamplesOrStats() {
        val result = assertNotNull(historyChartViewport(scene, HistoryRange(2000, 4000)))
        assertEquals(listOf(-0.5f, 0.5f, 1.5f), result.segments.single().map { it.x })
        assertEquals(points.map { it.epochMillis to it.value }, result.segments.single().map { it.epochMillis to it.value })
        assertEquals(points.map { it.y }, result.segments.single().map { it.y })
        assertEquals(60.0, result.minimum); assertEquals(80.0, result.maximum); assertTrue(result.truncated)
        assertEquals(listOf(0f, 0.5f, 1f), scene.segments.single().map { it.x })
    }
    @Test fun gapsAndUnloadedOutsideAreasDoNotAcquireNewPoints() {
        val source = scene.copy(segments = listOf(points.take(1), points.drop(1)))
        val result = assertNotNull(historyChartViewport(source, HistoryRange(0, 10000)))
        assertEquals(listOf(1, 2), result.segments.map { it.size })
        assertEquals(listOf(0.1f, 0.3f, 0.5f), result.segments.flatten().map { it.x })
        assertTrue(assertNotNull(historyChartViewport(scene.copy(segments = emptyList()), HistoryRange(0, 10000))).segments.isEmpty())
    }
    @Test fun zeroWidthRetainsOnlyExactEarnedPointAndLargeEpochOffsetsRemainExact() {
        val point = assertNotNull(historyChartViewport(scene, HistoryRange(3000, 3000)))
        assertEquals(listOf(3000L), point.segments.flatten().map { it.epochMillis })
        assertEquals(0f, point.segments.single().single().x)
        val start = Long.MAX_VALUE - 10
        val large = scene.copy(segments = listOf(listOf(points.first().copy(epochMillis = start + 1))))
        assertEquals(0.1f, assertNotNull(historyChartViewport(large, HistoryRange(start, Long.MAX_VALUE))).segments.single().single().x)
    }
    @Test fun overflowingSpanAndInvalidSceneFailClosed() {
        assertNull(historyChartViewport(scene, HistoryRange(Long.MIN_VALUE, Long.MAX_VALUE)))
        assertNull(historyChartViewport(scene.copy(segments = listOf(listOf(points.first().copy(y = Float.NaN)))), HistoryRange(1000, 5000)))
    }
}

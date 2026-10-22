package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryChartSceneTest {
    private val key = HistorySeriesKey("pack", "voltage_v")
    private val origin = HistoryTick(1000, 10, 1)
    private fun sample(axis: Long, value: Double?, wall: Long = axis) = HistorySample(
        HistoryTick(wall, axis - 990, 1), key, value, if (value == null) HistoryUnknownReason.GAP else null)
    private fun scene(rows: List<HistorySample>, markers: List<HistoryMarker> = emptyList(),
        truncated: Boolean = false, range: HistoryRange = HistoryRange(1000, 5000)) =
        assertNotNull(historyChartScene(HistorySeries(key, rows, markers, truncated, origin), range))

    @Test fun actualOverviewGapSeparatorDoesNotEraseFirstEarnedPeakAfterOutage() {
        val overview = historyOverview(listOf(sample(1000, 60.0), sample(4000, 84.0), sample(4500, 70.0)), emptyList())
        assertTrue(overview.any { it.unknown == HistoryUnknownReason.GAP })
        val result = scene(overview)
        assertEquals(84.0, result.maximum)
        assertEquals(listOf(listOf(1000L), listOf(4000L, 4500L)), result.segments.map { it.map(HistoryChartPoint::epochMillis) })
    }

    @Test fun unknownAndScopedGapsSplitLinesWithoutConnectingMissingIntervals() {
        val rows = listOf(sample(1000, 60.0), sample(1500, 61.0), sample(2000, null),
            sample(2500, 62.0), sample(3000, 63.0), sample(4000, 64.0))
        val marker = HistoryMarker(sample(3500, 0.0).tick, HistoryMarkerKind.BLE_GAP, "pack")
        assertEquals(listOf(listOf(1000L, 1500L), listOf(2500L, 3000L), listOf(4000L)),
            scene(rows, listOf(marker)).segments.map { it.map(HistoryChartPoint::epochMillis) })
    }

    @Test fun sparseOverviewDoesNotInventCadenceGapsAndUnrelatedGpsOrSourceLossDoesNotSplit() {
        val rows = listOf(sample(1000, 60.0), sample(5000, 84.0))
        val tick = sample(3000, 0.0).tick
        val result = scene(rows, listOf(HistoryMarker(tick, HistoryMarkerKind.GPS_GAP),
            HistoryMarker(tick, HistoryMarkerKind.BLE_GAP, "other")))
        assertEquals(1, result.segments.size)
        assertEquals(listOf(0f, 1f), result.segments.single().map { it.x })
        assertEquals(listOf(1f, 0f), result.segments.single().map { it.y })
    }

    @Test fun monotonicAxisSurvivesWallRollbackAndDisplaySignIsAppliedOnce() {
        val power = HistorySeriesKey("pack", "power_w")
        val rows = listOf(sample(1000, -4200.0).copy(key = power), sample(5000, 200.0, -5000).copy(key = power))
        val result = assertNotNull(historyChartScene(HistorySeries(power, rows, emptyList(), false, origin), HistoryRange(1000, 5000)))
        assertEquals(listOf(1000L, 5000L), result.segments.single().map { it.epochMillis })
        assertEquals(listOf(4200.0, -200.0), result.segments.single().map { it.value })
        assertEquals(-200.0, result.minimum); assertEquals(4200.0, result.maximum)
    }

    // Retraction: applying cursor ambiguity to the line erased earned points,
    // including the real first post-gap peak paired with an overview separator.
    @Test fun equalTimeUnknownSplitsSavedOrderWithoutErasingEarnedPoints() {
        for (pair in listOf(listOf(sample(2000, 80.0), sample(2000, null)),
            listOf(sample(2000, null), sample(2000, 80.0)))) {
            val result = scene(listOf(sample(1000, 60.0)) + pair + sample(3000, 70.0))
            assertEquals(listOf(1000L, 2000L, 3000L), result.segments.flatten().map { it.epochMillis })
            assertEquals(2, result.segments.size)
            assertFalse(result.segments.any { segment -> segment.any { it.epochMillis == 1000L } && segment.any { it.epochMillis == 3000L } })
            assertEquals(80.0, result.maximum)
        }
    }

    @Test fun boundaryAtRestartPointSplitsOnceAndAllowsHealthyContinuation() {
        val rows = listOf(sample(1000, 60.0), sample(1500, 61.0), sample(2000, 84.0), sample(2500, 70.0))
        val marker = HistoryMarker(sample(2000, 0.0).tick, HistoryMarkerKind.BLE_GAP, "pack")
        assertEquals(listOf(listOf(1000L, 1500L), listOf(2000L, 2500L)),
            scene(rows, listOf(marker)).segments.map { it.map(HistoryChartPoint::epochMillis) })
    }

    @Test fun emptyUnknownAndStationarySeriesRemainHonest() {
        assertTrue(scene(emptyList()).segments.isEmpty())
        val unknown = scene(listOf(sample(1000, null), sample(2000, null)))
        assertTrue(unknown.segments.isEmpty()); assertNull(unknown.minimum); assertNull(unknown.maximum)
        val constant = scene(listOf(sample(1000, 0.0), sample(2000, 0.0)))
        assertEquals(0.0, constant.minimum); assertTrue(constant.segments.flatten().all { it.y == 0.5f })
    }

    @Test fun truncatedTailDoesNotGrowToViewportEndAndNoSyntheticEndpointsAppear() {
        val result = scene(listOf(sample(1500, 60.0), sample(2500, 70.0)), truncated = true)
        assertTrue(result.truncated)
        assertEquals(listOf(1500L, 2500L), result.segments.single().map { it.epochMillis })
        assertEquals(listOf(0.125f, 0.375f), result.segments.single().map { it.x })
    }

    @Test fun malformedKeysOrderRangeAndClockGenerationFailClosed() {
        val good = sample(2000, 60.0)
        for (rows in listOf(listOf(good.copy(key = HistorySeriesKey("other", "voltage_v"))),
            listOf(good, sample(1000, 60.0)), listOf(sample(6000, 60.0)),
            listOf(good.copy(tick = good.tick.copy(clockGeneration = 2))))) {
            assertNull(historyChartScene(HistorySeries(key, rows, emptyList(), false, origin), HistoryRange(1000, 5000)))
        }
    }

    @Test fun legacyWallAxisAndExtremeFiniteValuesHaveFiniteCoordinates() {
        val rows = listOf(HistorySample(HistoryTick(Long.MIN_VALUE, 0, 0), key, -Double.MAX_VALUE),
            HistorySample(HistoryTick(Long.MAX_VALUE, 0, 0), key, Double.MAX_VALUE))
        val result = assertNotNull(historyChartScene(HistorySeries(key, rows, emptyList(), false), HistoryRange(Long.MIN_VALUE, Long.MAX_VALUE)))
        assertEquals(listOf(0f, 1f), result.segments.single().map { it.x })
        assertEquals(listOf(1f, 0f), result.segments.single().map { it.y })
        val zeroWidth = scene(listOf(sample(1000, 60.0)), range = HistoryRange(1000, 1000))
        assertTrue(zeroWidth.segments.single().single().x.isFinite())
    }
}

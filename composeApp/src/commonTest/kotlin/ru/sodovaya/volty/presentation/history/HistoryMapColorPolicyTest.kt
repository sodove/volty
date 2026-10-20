package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryMapColorPolicyTest {
    private val origin = HistoryTick(1000, 10, 1)
    private val key = HistorySeriesKey("pack", "power_w")
    private fun tick(time: Long) = HistoryTick(time, time - 990, 1)
    private fun fix(time: Long) = HistoryGpsFix(tick(time), 56.0, 60.0 + (time - 1000) * 0.000001, 3.0, null)
    private fun sample(time: Long, value: Double?) = HistorySample(tick(time), key, value,
        if (value == null) HistoryUnknownReason.GAP else null)
    private fun series(samples: List<HistorySample>, markers: List<HistoryMarker> = emptyList(), truncated: Boolean = false) =
        HistorySeries(key, samples, markers, truncated, origin)
    private fun scene(times: List<Long> = listOf(1000, 1500, 2000), markers: List<HistoryMarker> = emptyList()) =
        historyMapScene(HistoryMapIdentity("a", 0), origin, times.map(::fix), markers, null, 1000)
    private val stats = HistoryStats(-500.0, -100.0, null, true)
    private fun values() = series(listOf(sample(1000, -100.0), sample(1500, -300.0), sample(2000, -500.0)))
    private fun detail(data: HistorySeries = values()) = HistoryDetailState(rideId = "a",
        ride = HistoryRide("a", "bike", origin, tick(2000), HistoryRideStatus.COMPLETED), range = HistoryRange(1000, 2000),
        sources = listOf(HistorySource("pack", HistorySourceKind.BATTERY, "Same name")), selectedKeys = listOf(key),
        series = mapOf(key to data), stats = mapOf(key to stats))

    @Test fun mapEvidenceRetainsTheExactSelectedSeriesAndStatsObjects() {
        val state = detail(); val result = assertNotNull(historyMapMetricEvidence(state, key))
        assertSame(state.series[key], result.series); assertSame(stats, result.stats)
    }
    @Test fun foreignCompleteSeriesCannotWearAnotherPacksMapEntryOrEqualName() {
        val foreign = key.copy(sourceId = "other")
        val data = values().copy(key = foreign, samples = values().samples.map { it.copy(key = foreign) })
        val counterfeit = detail(data).copy(sources = detail().sources + HistorySource("other", HistorySourceKind.BATTERY, "Same name"))
        assertNull(historyMapMetricEvidence(counterfeit, key))
        assertNull(historyMapMetricEvidence(detail(), foreign))
    }
    @Test fun missingUnselectedLoadingOrWrongKindEvidenceFailsClosed() {
        val original = detail()
        for (state in listOf(original.copy(selectedKeys = emptyList()), original.copy(loading = true),
            original.copy(error = HistoryReadError.STORAGE), original.copy(range = null),
            original.copy(loadingKeys = setOf(key)), original.copy(metricErrors = mapOf(key to HistoryReadError.STORAGE)),
            original.copy(sources = listOf(HistorySource("pack", HistorySourceKind.CONTROLLER, "Same name"))),
            original.copy(series = emptyMap()), original.copy(stats = emptyMap()), original.copy(sources = emptyList()))) {
            assertNull(historyMapMetricEvidence(state, key))
        }
        assertNull(historyMapMetricEvidence(null, key)); assertNull(historyMapMetricEvidence(original, null))
    }
    @Test fun qualifiedUnknownSeriesStaysUnknownInsteadOfBorrowingAnotherMetric() {
        val empty = values().copy(samples = listOf(sample(1000, null), sample(1500, null)))
        val unknownStats = HistoryStats(null, null, null, false)
        val state = detail(empty).copy(stats = mapOf(key to unknownStats))
        val result = assertNotNull(historyMapMetricEvidence(state, key))
        assertSame(empty, result.series); assertSame(unknownStats, result.stats)
        assertNull(historyMapColoring(scene(), origin, result.series, result.stats, 500))
    }

    @Test fun colorsActualStartValuesWithDisplaySignAndEarnedScale() {
        val result = assertNotNull(historyMapColoring(scene(), origin, values(), stats, 500))
        assertEquals(100.0, result.minimum); assertEquals(500.0, result.maximum)
        assertFalse(result.partial); assertEquals(listOf(0.0, 0.5), result.strokes.map { it.fraction })
        assertEquals(listOf(1000L, 1500L), result.strokes.map { it.start.tick.epochMillis })
    }
    @Test fun unknownInsideEdgePreventsBridgingEvenWhenBothEndpointsAreKnown() {
        val samples = listOf(sample(1000, -100.0), sample(1250, null), sample(1500, -300.0), sample(2000, -500.0))
        val result = assertNotNull(historyMapColoring(scene(), origin, series(samples), stats, 500))
        assertEquals(listOf(1500L), result.strokes.map { it.start.tick.epochMillis })
    }
    @Test fun sourceGapBlocksEdgeWhileOtherSourceAndGpsGapDoNotEraseTelemetry() {
        for (kind in listOf(HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CLOCK_CHANGE)) {
            val marker = HistoryMarker(tick(1250), kind, if (kind == HistoryMarkerKind.BLE_GAP) "pack" else null)
            assertEquals(1, assertNotNull(historyMapColoring(scene(), origin, values().copy(markers = listOf(marker)), stats, 500)).strokes.size)
        }
        for (marker in listOf(HistoryMarker(tick(1250), HistoryMarkerKind.BLE_GAP, "other"),
            HistoryMarker(tick(1250), HistoryMarkerKind.GPS_GAP))) {
            assertEquals(2, assertNotNull(historyMapColoring(scene(), origin, values().copy(markers = listOf(marker)), stats, 500)).strokes.size)
        }
    }
    @Test fun gpsSegmentsRemainSeparateAndTruncatedTailIsNeutral() {
        val separated = scene(listOf(1000, 1500, 2000, 2500), listOf(HistoryMarker(tick(1750), HistoryMarkerKind.GPS_GAP)))
        val data = series(listOf(sample(1000, -100.0), sample(1500, -300.0), sample(2000, -500.0), sample(2500, -200.0)))
        assertEquals(listOf(1000L, 2000L), assertNotNull(historyMapColoring(separated, origin, data, stats, 500)).strokes.map { it.start.tick.epochMillis })
        val tail = values().copy(samples = values().samples.take(2), truncated = true)
        val result = assertNotNull(historyMapColoring(scene(), origin, tail, stats, 1000))
        assertEquals(1, result.strokes.size); assertTrue(result.partial)
    }
    @Test fun noFutureOrStaleHoldingAndAmbiguousSameTimeUnknownWins() {
        val data = series(listOf(sample(1000, -100.0), sample(1500, -300.0), sample(2000, -500.0)))
        assertTrue(assertNotNull(historyMapColoring(scene(listOf(1100, 1600)), origin, data, stats, 0)).strokes.isEmpty())
        val ambiguous = data.copy(samples = listOf(sample(1000, -100.0), sample(1500, null), sample(1500, -300.0), sample(2000, -500.0)))
        assertTrue(assertNotNull(historyMapColoring(scene(), origin, ambiguous, stats, 500)).strokes.isEmpty())
    }
    @Test fun constantZeroIsEarnedButMissingInvalidOrForeignEvidenceIsNot() {
        val zero = series(listOf(sample(1000, 0.0), sample(1500, 0.0)))
        assertEquals(0.5, assertNotNull(historyMapColoring(scene(listOf(1000, 1500)), origin, zero, HistoryStats(0.0, 0.0, null, true), 0)).strokes.single().fraction)
        assertNull(historyMapColoring(scene(), origin, null, stats, 500))
        assertNull(historyMapColoring(scene(), origin, values(), HistoryStats(null, null, null, true), 500))
        // Both finite extrema are constructible, but their span overflows. A NaN
        // Stats fixture would fail its constructor without reaching this policy.
        assertNull(historyMapColoring(scene(), origin, values(), HistoryStats(-Double.MAX_VALUE, Double.MAX_VALUE, null, true), 500))
        assertNull(historyMapColoring(scene(), origin.copy(epochMillis = 999), values(), stats, 500))
        assertNull(historyMapColoring(scene(), origin, values().copy(samples = values().samples.reversed()), stats, 500))
        assertNull(historyMapColoring(scene(), origin, values().copy(samples = listOf(sample(1000, -100.0).copy(key = key.copy(sourceId = "other")))), stats, 500))
    }
    @Test fun boundedOverlayReportsPartialAndPreservesOriginalGeometry() {
        val original = scene()
        val result = assertNotNull(historyMapColoring(original, origin, values(), stats, 500, maxStrokes = 1))
        assertEquals(1, result.strokes.size); assertTrue(result.partial)
        assertEquals(3, original.segments.single().fixes.size)
        assertSame(original.segments.single().fixes[0], result.strokes.single().start)
        assertFailsWith<IllegalArgumentException> { historyMapColoring(original, origin, values(), stats, -1) }
    }
    @Test fun stableAxisSurvivesWallClockRollbackWithoutRewritingStoredTicks() {
        val back = HistoryTick(500, 510, 1)
        val end = HistoryGpsFix(back, 56.001, 60.001, 3.0, null)
        val route = historyMapScene(HistoryMapIdentity("a", 0), origin, listOf(fix(1000), end), emptyList(), null, 1000)
        val data = series(listOf(sample(1000, -100.0), HistorySample(back, key, -300.0)))
        assertSame(end, assertNotNull(historyMapColoring(route, origin, data, stats, 0)).strokes.single().end)
    }
    @Test fun connectionAtStartCanBeginAnEdgeButConnectionInsideCannotBridgeIt() {
        val atStart = List(10000) { HistoryMarker(tick(1000), HistoryMarkerKind.CONNECTED, "pack") }
        assertEquals(2, assertNotNull(historyMapColoring(scene(), origin, values().copy(markers = atStart), stats, 500)).strokes.size)
        val inside = values().copy(markers = listOf(HistoryMarker(tick(1500), HistoryMarkerKind.CONNECTED, "pack")))
        assertEquals(listOf(1500L), assertNotNull(historyMapColoring(scene(), origin, inside, stats, 500)).strokes.map { it.start.tick.epochMillis })
    }
}

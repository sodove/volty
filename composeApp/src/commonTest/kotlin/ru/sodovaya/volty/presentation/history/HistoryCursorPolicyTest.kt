package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryCursorPolicyTest {
    private val key = HistorySeriesKey("pack", "voltage_v")
    private val origin = HistoryTick(1000, 10, 1)
    private fun sample(axis: Long, value: Double? = 63.0) = HistorySample(HistoryTick(axis, axis - 990, 1), key, value,
        if (value == null) HistoryUnknownReason.PARTIAL_SOURCE else null)
    private fun series(vararg rows: HistorySample, markers: List<HistoryMarker> = emptyList()) =
        HistorySeries(key, rows.toList(), markers, false, origin)

    @Test fun cursorUsesOnlyRecentPastEvidenceIncludingEarnedZero() {
        val zero = sample(1000, 0.0); val data = series(zero, sample(2000))
        assertSame(zero, historyCursorSample(data, 1500, 500))
        assertNull(historyCursorSample(data, 1501, 500)); assertNull(historyCursorSample(data, 999, 1000))
        assertSame(zero, historyCursorSample(data, 1000, 0)); assertNull(historyCursorSample(data, 1001, 0))
    }

    @Test fun unknownAtLatestTimeBlocksOlderFactsAndAmbiguousEqualTimeWinsOverKnown() {
        val known = sample(1000); val unknown = sample(2000, null); val atSameTime = sample(2000, 42.0)
        for (rows in listOf(listOf(known, unknown, atSameTime), listOf(known, atSameTime, unknown)))
            assertNull(historyCursorSample(series(*rows.toTypedArray()), 2000, 1000))
        assertNull(historyCursorSample(series(known, unknown), 2100, 1000))
        assertSame(atSameTime, historyCursorSample(series(sample(2000, 0.0), atSameTime), 2000, 1000))
    }

    @Test fun cursorCannotHoldFactsAcrossGlobalOrOwnSourceBoundaries() {
        val known = sample(1000); val boundary = sample(1200).tick
        for (marker in listOf(HistoryMarker(boundary, HistoryMarkerKind.BLE_GAP),
            HistoryMarker(boundary, HistoryMarkerKind.BLE_GAP, key.sourceId), HistoryMarker(boundary, HistoryMarkerKind.CLOCK_CHANGE)))
            assertNull(historyCursorSample(series(known, markers = listOf(marker)), 1300, 1000))
        for (marker in listOf(HistoryMarker(boundary, HistoryMarkerKind.BLE_GAP, "other"),
            HistoryMarker(boundary, HistoryMarkerKind.GPS_GAP), HistoryMarker(known.tick, HistoryMarkerKind.CONNECTED)))
            assertSame(known, historyCursorSample(series(known, markers = listOf(marker)), 1300, 1000))
        val coincidentGap = HistoryMarker(known.tick, HistoryMarkerKind.BLE_GAP)
        assertSame(known, historyCursorSample(series(known, markers = listOf(coincidentGap)), 1000, 1000))
        assertNull(historyCursorSample(series(known, markers = listOf(coincidentGap)), 1001, 1000))
    }

    @Test fun v2CursorAndMarkersUseMonotonicAxisInsteadOfCapturedWallJump() {
        val earned = sample(2000).copy(tick = sample(2000).tick.copy(epochMillis = 3_602_000))
        assertSame(earned, historyCursorSample(series(earned), 2100, 500))
        val gap = HistoryMarker(HistoryTick(3_602_050, 1060, 1), HistoryMarkerKind.CLOCK_CHANGE)
        assertNull(historyCursorSample(series(earned, markers = listOf(gap)), 2100, 500))
    }

    @Test fun legacyUsesWallTimeWithoutInterpretingItsPlaceholderElapsed() {
        val first = HistorySample(HistoryTick(1000, 0, 0), key, 0.0)
        val second = HistorySample(HistoryTick(2000, 0, 0), key, 42.0)
        val legacy = HistorySeries(key, listOf(first, second), emptyList(), false)
        assertSame(first, historyCursorSample(legacy, 1500, 500))
        assertSame(second, historyCursorSample(legacy, 2000, 0))
        assertNull(historyCursorSample(legacy, 1501, 500))
        val extremes = legacy.copy(samples = listOf(first.copy(tick = HistoryTick(Long.MIN_VALUE, 0, 0))))
        assertNull(historyCursorSample(extremes, Long.MAX_VALUE, Long.MAX_VALUE))
    }

    @Test fun omittedTailCannotBecomeAnExtendedCursorValue() {
        val first = sample(1000); val last = sample(2000); val partial = series(first, last).copy(truncated = true)
        assertSame(last, historyCursorSample(partial, 2000, 1000))
        assertNull(historyCursorSample(partial, 2001, 1000))
        assertSame(first, historyCursorSample(partial, 1500, 1000))
    }

    @Test fun malformedSeriesAndInvalidAgeFailWithoutRelabelingOtherSourceFacts() {
        val known = sample(1000)
        for (data in listOf(series(known.copy(key = key.copy(sourceId = "other"))), series(sample(2000), known),
            series(known.copy(tick = known.tick.copy(clockGeneration = 2)))))
            assertNull(historyCursorSample(data, 2000, 1000))
        assertNull(historyCursorSample(series(), 2000, 1000))
        assertFailsWith<IllegalArgumentException> { historyCursorSample(series(known), 1000, -1) }
    }
}

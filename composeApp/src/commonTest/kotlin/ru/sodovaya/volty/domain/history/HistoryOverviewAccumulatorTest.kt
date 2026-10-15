package ru.sodovaya.volty.domain.history

import kotlin.test.*

class HistoryOverviewAccumulatorTest {
    private val key = HistorySeriesKey("pack-a", "power_w")
    private fun sample(index: Int, value: Double? = 10.0) = HistorySample(
        HistoryTick(index * 100L, index * 100L, 1), key, value,
        if (value == null) HistoryUnknownReason.UNAVAILABLE else null)

    @Test fun pagedThinningKeepsPeaksAndNeverTreatsThinnedIntervalsAsAcquisitionGaps() {
        val a = HistoryOverviewAccumulator(emptyList(), 31)
        a.offerPage((0..999).map { sample(it, if (it == 501) 4200.0 else 10.0) })
        a.offerPage((1000..1999).map { sample(it, if (it == 1501) -1500.0 else 20.0) })
        val result = a.snapshot()
        assertTrue(result.size <= 31)
        assertEquals(sample(0), result.first())
        assertEquals(sample(1999, 20.0), result.last())
        assertEquals(4200.0, result.mapNotNull { it.value }.maxOrNull())
        assertEquals(-1500.0, result.mapNotNull { it.value }.minOrNull())
        assertTrue(result.all { it.unknown == null })
    }

    @Test fun aDroppedTrailingUnknownStillSeparatesTheNextPageAfterDenseGapCompaction() {
        val a = HistoryOverviewAccumulator(emptyList(), 7)
        a.offerPage((0..99).map { sample(it, if (it % 2 == 1) null else 1.0) })
        a.offerPage(listOf(sample(100, 2.0)))
        val result = a.snapshot()
        assertEquals(2.0, result.last().value)
        val before = result.indexOfLast { it.tick.elapsedMillis < 10000 && it.value != null }
        assertTrue(before >= 0)
        assertTrue(result.drop(before + 1).dropLast(1).any { it.value == null })
    }

    @Test fun markerAndRealCadenceBreaksSurvivePageEdgesAndEmptyPages() {
        val marker = HistoryMarker(sample(1).tick, HistoryMarkerKind.BLE_GAP)
        val a = HistoryOverviewAccumulator(listOf(marker))
        a.offerPage(listOf(sample(0))); a.offerPage(emptyList()); a.offerPage(listOf(sample(2)))
        assertTrue(a.snapshot().any { it.unknown == HistoryUnknownReason.GAP && it.tick == marker.tick })
        a.offerPage(emptyList()); a.offerPage(listOf(sample(50)))
        assertTrue(a.snapshot().any { it.unknown == HistoryUnknownReason.GAP && it.tick == sample(50).tick })
    }

    @Test fun aDifferentSeriesCannotJoinAnExistingAccumulatorAndInvalidBudgetsFailEarly() {
        val a = HistoryOverviewAccumulator(emptyList())
        a.offerPage(listOf(sample(0)))
        assertFailsWith<IllegalArgumentException> { a.offerPage(listOf(sample(1).copy(key = key.copy(sourceId = "pack-b")))) }
        assertFailsWith<IllegalArgumentException> { HistoryOverviewAccumulator(emptyList(), 2) }
        assertFailsWith<IllegalArgumentException> { HistoryOverviewAccumulator(emptyList(), 20001) }
    }

    @Test fun snapshotsDoNotChangeWhenTheNextPageOrCallerListChanges() {
        val a = HistoryOverviewAccumulator(emptyList())
        val input = mutableListOf(sample(0), sample(1))
        a.offerPage(input); val before = a.snapshot(); input.clear()
        a.offerPage(listOf(sample(2)))
        assertEquals(listOf(sample(0), sample(1)), before)
        assertEquals(listOf(sample(0), sample(1), sample(2)), a.snapshot())
    }

    @Test fun twoHoursOfPagesRemainBoundedWithOriginalPeakTicksAndNoFalseGaps() {
        val a = HistoryOverviewAccumulator(emptyList(), 61)
        repeat(12) { page ->
            a.offerPage((page * 6000 until (page + 1) * 6000).map {
                sample(it, when (it) { 4000 -> -1500.0; 60001 -> 4200.0; else -> 10.0 })
            })
            assertTrue(a.snapshot().size <= 61)
        }
        val result = a.snapshot()
        assertTrue(result.contains(sample(4000, -1500.0)))
        assertTrue(result.contains(sample(60001, 4200.0)))
        assertEquals(sample(71999), result.last())
        assertTrue(result.none { it.unknown != null })
    }
}

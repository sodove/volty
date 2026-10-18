package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryPreviewReductionPolicyTest {
    private fun fix(index: Int, lat: Double, lon: Double) = HistoryGpsFix(HistoryTick(1000L + index, index.toLong(), 1), lat, lon, 3.0, null)
    private fun segment(vararg fixes: HistoryGpsFix) = HistoryMapSegment(fixes.toList())

    @Test fun boundedReductionRetainsActualEndpointsAndARealCorner() {
        val points = (0..1000).map { index -> if (index <= 500) fix(index,56.0,60.0 + index * 0.00001)
            else fix(index,56.0 + (index - 500) * 0.00001,60.005) }
        val result = reduceHistoryPreview(listOf(HistoryMapSegment(points)),3)
        assertEquals(listOf(points[0],points[500],points[1000]),result.segments.single().fixes)
        assertFalse(result.partial)
    }

    @Test fun everyRetainedSegmentKeepsItsOwnEndpointsAndNoLineBridgesGaps() {
        val a = segment(fix(0,56.0,60.0),fix(1,56.1,60.1),fix(2,56.2,60.0))
        val b = segment(fix(3,57.0,61.0),fix(4,57.1,61.1),fix(5,57.2,61.0))
        val result = reduceHistoryPreview(listOf(a,b),4)
        assertEquals(listOf(listOf(a.fixes.first(),a.fixes.last()),listOf(b.fixes.first(),b.fixes.last())),result.segments.map { it.fixes })
        assertFalse(result.partial)
    }

    @Test fun tooManyGapSegmentsBecomeExplicitPartialPrefixWithinTheBudget() {
        val input = (0..9).map { index -> segment(fix(index * 2,56.0,60.0 + index),fix(index * 2 + 1,56.01,60.01 + index)) }
        val result = reduceHistoryPreview(input,3)
        assertTrue(result.partial)
        assertEquals(listOf(input.first()),result.segments)
        assertTrue(result.segments.sumOf { it.fixes.size } <= 3)
    }

    @Test fun stationaryRoutesStayFiniteAndOutputDoesNotAliasInputLists() {
        val points = (0..19).map { fix(it,56.0,60.0) }
        val reduced = reduceHistoryPreview(listOf(HistoryMapSegment(points)),6)
        assertEquals(listOf(points.first(),points.last()),reduced.segments.single().fixes)
        val mutable = mutableListOf(points.first(),points.last())
        val snapshot = reduceHistoryPreview(listOf(HistoryMapSegment(mutable)),6)
        mutable.clear()
        assertEquals(2,snapshot.segments.single().fixes.size)
        assertFalse(snapshot.partial)
    }

    @Test fun emptyRouteAndInvalidBudgetsCannotInventPreviewCoordinates() {
        assertEquals(HistoryPreviewData(emptyList(),false),reduceHistoryPreview(listOf(HistoryMapSegment(emptyList())),128))
        assertFailsWith<IllegalArgumentException> { reduceHistoryPreview(emptyList(),1) }
        assertFailsWith<IllegalArgumentException> { reduceHistoryPreview(emptyList(),4097) }
    }
}

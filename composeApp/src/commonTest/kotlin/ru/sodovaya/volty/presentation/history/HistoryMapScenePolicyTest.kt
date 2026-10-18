package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import kotlin.test.*

class HistoryMapScenePolicyTest {
    private val id = HistoryMapIdentity("ride", 1)
    private val origin = HistoryTick(1000, 10, 1)
    private fun fix(time: Long, lon: Double = 60.0) = HistoryGpsFix(HistoryTick(time, time - 990, 1), 56.0, lon, 3.0, null)
    private fun scene(fixes: List<HistoryGpsFix>, markers: List<HistoryMarker> = emptyList(), cursor: Long? = null, age: Long = 1000) =
        historyMapScene(id, origin, fixes, markers, cursor, age)

    @Test fun noGpsMeansNoRouteOrCursorOrInventedEndpoints() {
        val result = scene(emptyList(), cursor = 1500)
        assertTrue(result.segments.isEmpty()); assertNull(result.selectedFix)
        assertNull(result.startFix); assertNull(result.endFix); assertEquals(id, result.identity)
    }

    @Test fun recordedGpsGapSplitsLineAndDisallowsHoldingCursorAcrossIt() {
        val a = fix(1000); val b = fix(1300); val c = fix(1600); val d = fix(1800)
        val gap = HistoryMarker(fix(1400).tick, HistoryMarkerKind.GPS_GAP)
        val result = scene(listOf(a,b,c,d), listOf(gap), 1500)
        assertEquals(listOf(listOf(a,b), listOf(c,d)), result.segments.map { it.fixes })
        assertEquals(listOf(c), result.gapFixes); assertNull(result.selectedFix)
        assertEquals(a, result.startFix); assertEquals(d, result.endFix)
        assertEquals(c, scene(listOf(a,b,c,d), listOf(gap), 1600).selectedFix)
    }

    @Test fun missingGapMarkerStillCannotBridgeUnboundedGpsSilence() {
        val a = fix(1000); val b = fix(5000)
        assertEquals(listOf(listOf(a), listOf(b)), scene(listOf(a,b)).segments.map { it.fixes })
        assertNull(scene(listOf(a,b), cursor = 3000).selectedFix)
        assertEquals(a, scene(listOf(a,b), cursor = 2000).selectedFix)
        assertNull(scene(listOf(a,b), cursor = 999).selectedFix)
    }

    @Test fun wallClockJumpUsesRecordedMonotonicAxisForGapAndCursor() {
        val a = fix(1000); val b = fix(1600).copy(tick = fix(1600).tick.copy(epochMillis = 3_601_600))
        assertEquals(b, scene(listOf(a,b), cursor = 1700).selectedFix)
        val gap = HistoryMarker(fix(1300).tick.copy(epochMillis = 3_601_300), HistoryMarkerKind.GPS_GAP)
        assertEquals(2, scene(listOf(a,b), listOf(gap)).segments.size)
    }

    @Test fun bleSourceOutageDoesNotEraseIndependentGpsContinuity() {
        val a = fix(1000); val b = fix(1600)
        val markers = listOf(HistoryMarker(fix(1300).tick, HistoryMarkerKind.BLE_GAP, "pack"),
            HistoryMarker(fix(1400).tick, HistoryMarkerKind.SOURCE_CHANGE))
        assertEquals(listOf(listOf(a,b)), scene(listOf(a,b), markers, 1700).segments.map { it.fixes })
        assertEquals(b, scene(listOf(a,b), markers, 1700).selectedFix)
    }

    @Test fun invalidOrderOrClockGenerationFailsClosedWithoutSortingWrongEvidence() {
        val a = fix(1000); val b = fix(1600)
        assertTrue(scene(listOf(b,a)).segments.isEmpty())
        assertTrue(scene(listOf(a,b.copy(tick = b.tick.copy(clockGeneration = 2)))).segments.isEmpty())
        assertFailsWith<IllegalArgumentException> { scene(listOf(a), age = -1) }
    }

    @Test fun longitudeOrderingStationaryAndCoincidentGapStayLiteral() {
        val a = fix(1000, 179.9); val b = fix(1500, -179.9)
        assertEquals(listOf(a,b), scene(listOf(a,b)).segments.single().fixes)
        val still = a.copy(tick = fix(1500).tick)
        assertEquals(listOf(a,still), scene(listOf(a,still)).segments.single().fixes)
        val gap = HistoryMarker(a.tick, HistoryMarkerKind.GPS_GAP)
        assertEquals(a, scene(listOf(a), listOf(gap), 1000).selectedFix)
        assertNull(scene(listOf(a), listOf(gap), 1001).selectedFix)
    }
}

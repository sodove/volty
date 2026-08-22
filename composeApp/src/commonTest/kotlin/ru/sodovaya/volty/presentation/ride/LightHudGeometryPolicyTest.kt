package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.math.abs

class LightHudGeometryPolicyTest {
    @Test
    fun gauge_block_is_compact_and_starts_directly_after_top_graphs() {
        assertTrue(lightGaugeBlockHeight(LightLayoutMode.MEDIUM) <= 160f)
        assertEquals(0f, lightGaugeBlockTopSpacing(LightLayoutMode.MEDIUM))
        assertEquals(1f, lightDashboardMiddleSpacerWeight())
    }

    @Test
    fun gauge_sectors_are_narrow_side_arcs_with_heavy_readouts() {
        val left = lightGaugeGeometry(LightLayoutMode.MEDIUM, LightArcSide.LEFT)
        val right = lightGaugeGeometry(LightLayoutMode.MEDIUM, LightArcSide.RIGHT)

        assertTrue(left.arcWidthFraction < 1f)
        assertTrue(left.arcHeightFraction < 1f)
        assertEquals(225f, left.arcStartDegrees)
        assertEquals(315f, right.arcStartDegrees)
        assertEquals(-90f, left.arcSweepDegrees)
        assertEquals(90f, right.arcSweepDegrees)
        assertEquals(abs(left.arcSweepDegrees), abs(right.arcSweepDegrees))
        assertTrue(left.progressesFromTop)
        assertTrue(right.progressesFromTop)
        assertTrue(left.valueWeight >= 700)
        assertTrue(left.labelWeight >= 600)
    }
}

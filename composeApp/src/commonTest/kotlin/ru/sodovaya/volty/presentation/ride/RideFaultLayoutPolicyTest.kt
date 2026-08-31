package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.model.DashboardStyle

class RideFaultLayoutPolicyTest {
    @Test
    fun light_faults_are_overlaid_so_the_hud_does_not_move() {
        assertEquals(RideFaultPlacement.OVERLAY, rideFaultPlacement(DashboardStyle.LIGHT))
    }

    @Test
    fun non_light_faults_remain_inline_with_the_scrollable_dashboard() {
        assertEquals(RideFaultPlacement.INLINE, rideFaultPlacement(DashboardStyle.CLEAN))
        assertEquals(RideFaultPlacement.INLINE, rideFaultPlacement(DashboardStyle.CLASSIC))
    }
}

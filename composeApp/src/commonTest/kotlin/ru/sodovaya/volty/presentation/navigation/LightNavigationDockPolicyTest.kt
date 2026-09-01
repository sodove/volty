package ru.sodovaya.volty.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LightNavigationDockPolicyTest {
    @Test
    fun `planner has no bottom inset so it can cover telemetry`() {
        assertEquals(
            0f,
            LightNavigationDockPolicy.bottomPadding(LightNavigationSurface.PLANNER),
        )
    }

    @Test
    fun `planner leaves a small visual gap above the keyboard only while it is visible`() {
        assertEquals(12f, LightNavigationDockPolicy.plannerBottomPadding(imeVisible = true))
        assertEquals(0f, LightNavigationDockPolicy.plannerBottomPadding(imeVisible = false))
    }

    @Test
    fun `active guidance keeps a telemetry-sized bottom gap`() {
        assertTrue(
            LightNavigationDockPolicy.bottomPadding(LightNavigationSurface.GUIDANCE_DOCK) >
                LightNavigationDockPolicy.bottomPadding(LightNavigationSurface.PLANNER),
        )
    }

    @Test
    fun `search results show three rows before scrolling`() {
        assertEquals(3, LightNavigationSearchPolicy.MAX_VISIBLE_RESULTS)
        assertEquals(3, LightNavigationSearchPolicy.visibleResultRows(8))
        assertEquals(1, LightNavigationSearchPolicy.visibleResultRows(1))
    }

    @Test
    fun `maneuver instruction is the primary guidance text even when street is known`() {
        assertEquals(
            "Поверните налево",
            guidancePrimaryText(
                NavigationUiManeuver(
                    icon = NavigationUiManeuverIcon.LEFT,
                    distanceText = "120 м",
                    instruction = "Поверните налево",
                    streetName = "улица Индустрии",
                ),
            ),
        )
    }

    @Test
    fun `guidance title removes the street suffix from localized instructions`() {
        assertEquals(
            "Поверните налево",
            guidancePrimaryText(
                NavigationUiManeuver(
                    icon = NavigationUiManeuverIcon.LEFT,
                    distanceText = "120 м",
                    instruction = "Поверните налево на улица Индустрии",
                    streetName = "улица Индустрии",
                ),
            ),
        )
    }
}

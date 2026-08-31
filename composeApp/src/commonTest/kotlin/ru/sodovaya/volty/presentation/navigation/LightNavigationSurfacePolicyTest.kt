package ru.sodovaya.volty.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class LightNavigationSurfacePolicyTest {
    @Test
    fun `planner and guidance use a map safe bottom panel`() {
        assertEquals(
            LightNavigationPanelPlacement.MAP_BOTTOM_PANEL,
            LightNavigationPanelPolicy.forSurface(LightNavigationSurface.PLANNER),
        )
        assertEquals(
            LightNavigationPanelPlacement.MAP_BOTTOM_PANEL,
            LightNavigationPanelPolicy.forSurface(LightNavigationSurface.GUIDANCE_DOCK),
        )
        assertEquals(
            LightNavigationPanelPlacement.HIDDEN,
            LightNavigationPanelPolicy.forSurface(LightNavigationSurface.HIDDEN),
        )
    }

    @Test
    fun `planner uses a panel while active navigation uses the compact dock`() {
        assertEquals(
            LightNavigationSurface.PLANNER,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.PLANNING),
        )
        assertEquals(
            LightNavigationSurface.PLANNER,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.ROUTE_READY),
        )
        assertEquals(
            LightNavigationSurface.GUIDANCE_DOCK,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.NAVIGATING),
        )
        assertEquals(
            LightNavigationSurface.GUIDANCE_DOCK,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.REROUTING),
        )
        assertEquals(
            LightNavigationSurface.GUIDANCE_DOCK,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.ARRIVED),
        )
        assertEquals(
            LightNavigationSurface.HIDDEN,
            LightNavigationSurfacePolicy.forPhase(NavigationUiPhase.IDLE),
        )
    }
}

package ru.sodovaya.volty.presentation.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class LightNearbyPlacementPolicyTest {
    @Test
    fun `nearby keeps its dashboard affordance until navigation owns the surface`() {
        assertEquals(
            LightNearbyPlacement.HIDDEN,
            LightNearbyPlacementPolicy.forSurface(
                navigationSurface = LightNavigationSurface.HIDDEN,
                nearbyActive = false,
            ),
        )
        assertEquals(
            LightNearbyPlacement.DASHBOARD_CARD,
            LightNearbyPlacementPolicy.forSurface(
                navigationSurface = LightNavigationSurface.HIDDEN,
                nearbyActive = true,
            ),
        )
    }

    @Test
    fun `an active nearby ride is represented inside planning and guidance without duplicate cards`() {
        assertEquals(
            LightNearbyPlacement.PLANNER_ROW,
            LightNearbyPlacementPolicy.forSurface(
                navigationSurface = LightNavigationSurface.PLANNER,
                nearbyActive = true,
            ),
        )
        assertEquals(
            LightNearbyPlacement.GUIDANCE_DOCK,
            LightNearbyPlacementPolicy.forSurface(
                navigationSurface = LightNavigationSurface.GUIDANCE_DOCK,
                nearbyActive = true,
            ),
        )
        assertEquals(
            LightNearbyPlacement.HIDDEN,
            LightNearbyPlacementPolicy.forSurface(
                navigationSurface = LightNavigationSurface.PLANNER,
                nearbyActive = false,
            ),
        )
    }
}

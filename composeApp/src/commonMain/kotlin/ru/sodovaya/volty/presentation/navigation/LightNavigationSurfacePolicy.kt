package ru.sodovaya.volty.presentation.navigation

/** The surface a Light dashboard should reserve for the current navigation phase. */
enum class LightNavigationSurface {
    HIDDEN,
    PLANNER,
    GUIDANCE_DOCK,
}

/** Where the existing Nearby affordance belongs for the current Light HUD. */
enum class LightNearbyPlacement {
    HIDDEN,
    DASHBOARD_CARD,
    PLANNER_ROW,
    GUIDANCE_DOCK,
}

/** The panel placement shared by route selection and active guidance. */
enum class LightNavigationPanelPlacement {
    HIDDEN,
    MAP_BOTTOM_PANEL,
}

object LightNavigationSurfacePolicy {
    fun forPhase(phase: NavigationUiPhase): LightNavigationSurface = when (phase) {
        NavigationUiPhase.PLANNING,
        NavigationUiPhase.ROUTE_READY -> LightNavigationSurface.PLANNER

        NavigationUiPhase.NAVIGATING,
        NavigationUiPhase.REROUTING,
        NavigationUiPhase.ARRIVED -> LightNavigationSurface.GUIDANCE_DOCK

        NavigationUiPhase.IDLE -> LightNavigationSurface.HIDDEN
    }
}

object LightNearbyPlacementPolicy {
    fun forSurface(
        navigationSurface: LightNavigationSurface,
        nearbyActive: Boolean,
    ): LightNearbyPlacement = if (!nearbyActive) {
        LightNearbyPlacement.HIDDEN
    } else {
        when (navigationSurface) {
            LightNavigationSurface.HIDDEN -> LightNearbyPlacement.DASHBOARD_CARD
            LightNavigationSurface.PLANNER -> LightNearbyPlacement.PLANNER_ROW
            LightNavigationSurface.GUIDANCE_DOCK -> LightNearbyPlacement.GUIDANCE_DOCK
        }
    }
}

object LightNavigationPanelPolicy {
    fun forSurface(surface: LightNavigationSurface): LightNavigationPanelPlacement = when (surface) {
        LightNavigationSurface.HIDDEN -> LightNavigationPanelPlacement.HIDDEN
        LightNavigationSurface.PLANNER,
        LightNavigationSurface.GUIDANCE_DOCK -> LightNavigationPanelPlacement.MAP_BOTTOM_PANEL
    }
}

object LightNavigationDockPolicy {
    /** Returns a bottom inset in dp for the layer hosting the current surface. */
    fun bottomPadding(surface: LightNavigationSurface): Float = when (surface) {
        LightNavigationSurface.PLANNER -> 0f
        LightNavigationSurface.GUIDANCE_DOCK -> GUIDANCE_TELEMETRY_GAP_DP
        LightNavigationSurface.HIDDEN -> 0f
    }

    /** Keeps a planner card visually detached from the IME without moving it up twice. */
    fun plannerBottomPadding(imeVisible: Boolean): Float =
        if (imeVisible) PLANNER_IME_GAP_DP else 0f

    private const val GUIDANCE_TELEMETRY_GAP_DP = 170f
    private const val PLANNER_IME_GAP_DP = 12f
}

object LightNavigationSearchPolicy {
    const val MAX_VISIBLE_RESULTS = 3

    fun visibleResultRows(resultCount: Int): Int =
        resultCount.coerceIn(0, MAX_VISIBLE_RESULTS)
}

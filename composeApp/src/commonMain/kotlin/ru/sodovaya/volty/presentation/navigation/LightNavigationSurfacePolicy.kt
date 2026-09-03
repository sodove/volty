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
        LightNavigationSurface.GUIDANCE_DOCK -> 0f
        LightNavigationSurface.HIDDEN -> 0f
    }

    /** The dashboard supplies the shared HUD edge inset; the card fills that available width. */
    fun guidanceCardWidthFraction(): Float = GUIDANCE_CARD_WIDTH_FRACTION

    /** The only intentional space between the guidance card and the HUD action row. */
    fun guidanceHudGap(): Float = GUIDANCE_HUD_GAP_DP

    /** Keeps the guidance surface compact while preserving room for its touch targets. */
    fun guidanceCardMinHeight(): Float = GUIDANCE_CARD_MIN_HEIGHT_DP

    /** The resized window already ends at the IME; navigation bars are handled by the surface. */
    fun plannerBottomPadding(imeVisible: Boolean): Float = 0f

    /** A small visual lift keeps the card legible without depending on screen height. */
    fun plannerImeGap(): Float = PLANNER_IME_GAP_DP

    /** Route alternatives remain easy to scan and meet the dashboard touch-target baseline. */
    fun routeOptionMinHeight(): Float = ROUTE_OPTION_MIN_HEIGHT_DP

    private const val GUIDANCE_HUD_GAP_DP = 16f
    private const val GUIDANCE_CARD_MIN_HEIGHT_DP = 90f
    private const val GUIDANCE_CARD_WIDTH_FRACTION = 1f
    private const val ROUTE_OPTION_MIN_HEIGHT_DP = 52f
    private const val PLANNER_IME_GAP_DP = 8f
}

object LightNavigationSearchPolicy {
    const val MAX_VISIBLE_RESULTS = 3
    /** Two characters are enough to start autocomplete; the provider still debounces input. */
    const val MIN_QUERY_LENGTH = 2

    fun visibleResultRows(resultCount: Int): Int =
        resultCount.coerceIn(0, MAX_VISIBLE_RESULTS)
}

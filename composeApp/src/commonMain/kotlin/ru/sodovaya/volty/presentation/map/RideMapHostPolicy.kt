package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.model.DashboardStyle

internal enum class RideMapScreen {
    RIDE,
    BATTERY,
    NEARBY,
    GROUP_MAP,
    HISTORY,
    OTHER,
}

internal data class RideMapHostState(
    val mounted: Boolean,
    val visible: Boolean,
    val requestLocationPermission: Boolean,
)

/** One duration for the screen crossfade and the shared map fade, so the two never drift apart. */
internal const val RIDE_MAP_FADE_MILLIS = 300

/**
 * The retained map keeps its style and tiles while hidden, but its renderer
 * runs only while the map is wanted or still fading out. An earlier revision
 * hid the map with alpha alone, so an invisible map kept drawing every scene
 * update (own fix, trail, participant markers).
 */
internal fun rideMapRenderActive(visible: Boolean, alpha: Float): Boolean = visible || alpha > 0f

/**
 * Whether the Ride HUD has the root-hosted map under it, and so must stay
 * transparent. It follows [RideMapHostState.mounted], not `visible`: the stack's
 * active child changes on the first frame of a crossfade, while the outgoing
 * Ride child stays composed for the whole fade. Retraction: an earlier revision
 * keyed the HUD on `visible`, so the outgoing HUD switched to its opaque
 * fallback (a hardcoded navy placeholder map) on the first frame of every
 * Ride -> other-screen transition and covered the real map as it faded.
 */
internal fun rideHudMapBacked(host: RideMapHostState): Boolean = host.mounted

/** Haze can only sample the live map when MapLibre renders through a TextureView. */
internal const val rideMapTextureModeRequiredForMapCapture = true

/**
 * The native map must outlive the active Decompose child. Hiding it keeps the
 * GL surface and loaded tiles alive while the rider visits another screen.
 */
internal fun rideMapHostState(
    rideAvailable: Boolean,
    activeScreen: RideMapScreen,
    activeStyle: DashboardStyle?,
): RideMapHostState {
    val rideMapVisible = rideAvailable &&
        activeScreen == RideMapScreen.RIDE &&
        activeStyle == DashboardStyle.LIGHT
    // Only the group map shows the shared map on the Nearby side. The Nearby tab
    // itself is opaque; treating it as a map screen made the map flash through
    // the tab crossfade and keep rendering unseen underneath.
    val nearbyMapVisible = activeScreen == RideMapScreen.GROUP_MAP
    val historyMapVisible = activeScreen == RideMapScreen.HISTORY
    return RideMapHostState(
        mounted = rideAvailable || nearbyMapVisible || historyMapVisible,
        visible = rideMapVisible || nearbyMapVisible || historyMapVisible,
        // Nearby may render remote markers, but opening it must never ask for
        // or start the user's own location stream.
        requestLocationPermission = rideMapVisible,
    )
}

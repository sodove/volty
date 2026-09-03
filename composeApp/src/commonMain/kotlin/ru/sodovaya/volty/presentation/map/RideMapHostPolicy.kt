package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.model.DashboardStyle

internal enum class RideMapScreen {
    RIDE,
    BATTERY,
    NEARBY,
    GROUP_MAP,
    OTHER,
}

internal data class RideMapHostState(
    val mounted: Boolean,
    val visible: Boolean,
    val requestLocationPermission: Boolean,
)

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
    val nearbyMapVisible = activeScreen == RideMapScreen.NEARBY ||
        activeScreen == RideMapScreen.GROUP_MAP
    return RideMapHostState(
        mounted = rideAvailable || nearbyMapVisible,
        visible = rideMapVisible || nearbyMapVisible,
        // Nearby may render remote markers, but opening it must never ask for
        // or start the user's own location stream.
        requestLocationPermission = rideMapVisible,
    )
}

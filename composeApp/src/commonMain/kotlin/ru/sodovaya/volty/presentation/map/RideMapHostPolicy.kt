package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.model.DashboardStyle

internal enum class RideMapScreen {
    RIDE,
    BATTERY,
    NEARBY,
    OTHER,
}

internal data class RideMapHostState(
    val mounted: Boolean,
    val visible: Boolean,
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
): RideMapHostState = RideMapHostState(
    mounted = rideAvailable,
    visible = rideAvailable && activeScreen == RideMapScreen.RIDE && activeStyle == DashboardStyle.LIGHT,
)

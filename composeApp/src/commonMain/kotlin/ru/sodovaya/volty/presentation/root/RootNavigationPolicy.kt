package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.domain.model.DashboardStyle

/** Root destinations that may expose the persistent bottom navigation. */
internal enum class RootChromeDestination {
    RIDE,
    BATTERY,
    GRAPH,
    NEARBY,
    SETTINGS,
    OTHER,
}

/**
 * Light owns its own map HUD controls. The other ride dashboards need the
 * root tab bar so the rider can still reach Battery, Nearby and Settings.
 */
internal fun bottomTabBarVisible(
    destination: RootChromeDestination,
    rideStyle: DashboardStyle?,
): Boolean = when (destination) {
    RootChromeDestination.RIDE -> rideStyle != DashboardStyle.LIGHT
    RootChromeDestination.BATTERY,
    RootChromeDestination.GRAPH,
    RootChromeDestination.NEARBY,
    RootChromeDestination.SETTINGS -> true
    RootChromeDestination.OTHER -> false
}

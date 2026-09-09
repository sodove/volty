package ru.sodovaya.volty.presentation.root

import ru.sodovaya.volty.domain.model.DashboardStyle

/** Root destinations that may expose the persistent bottom navigation. */
internal enum class RootChromeDestination {
    RIDE,
    BATTERY,
    GRAPH,
    NEARBY,
    GROUP_MAP,
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
    RootChromeDestination.GROUP_MAP,
    RootChromeDestination.SETTINGS -> true
    RootChromeDestination.OTHER -> false
}

internal fun shouldShowGroupMapTab(
    destination: RootChromeDestination,
    groupMapVisible: Boolean,
): Boolean = groupMapVisible && destination == RootChromeDestination.GROUP_MAP

internal data class GroupMapBackAction(
    val shouldPop: Boolean,
    val clearsSocialRuntime: Boolean,
)

internal fun groupMapBackAction(hasPreviousDestination: Boolean): GroupMapBackAction =
    GroupMapBackAction(
        shouldPop = hasPreviousDestination,
        clearsSocialRuntime = false,
    )

/**
 * Android dispatches Back to the activity before a text field's IME has
 * necessarily consumed it. While the IME is visible, the first Back belongs
 * to the field; only a later Back may pop the Decompose stack or finish the
 * activity. Keeping this as a pure policy makes the ordering regression
 * testable without pretending Compose itself is unit-testable.
 */
internal fun shouldConsumeBackForIme(imeBottomPx: Int): Boolean = imeBottomPx > 0

package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.hasMotionSource
import ru.sodovaya.volty.domain.model.isDemo
import ru.sodovaya.volty.presentation.ride.RideSpeed

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
 * Whether the Ride screen asks for the rider's own location for the dashboards' GPS speed, with no map mounted
 * (Classic and Clean; Light's map already holds the demand). RootScreen ORs this with the map's own visibility.
 *
 *  - **A vehicle with no controller** (battery-only): GPS is the only speed it can have, so the demand is raised
 *    while its Ride screen is up, whatever the style.
 *  - **A vehicle with a controller**: never for its own sake, so the controller's speed is not doubled by a GPS
 *    radio. One exception, decided for: [controllerSpeedSeen] (the controller has reported a speed on this visit)
 *    and that speed is now gone ([controllerSpeedKnown] false), i.e. the controller dropped out mid-ride. The demand
 *    then lasts exactly as long as the dropout and is released when the controller's speed returns. Worth it because
 *    a dropout is precisely when a rider glances down and finds `—`, it costs nothing while the link is healthy, and
 *    the rider's own hardware drops out in the field (Begode BLE, the plain VESC's watchdog, Part M). A first fix
 *    can take seconds, so a short blip may end before GPS speed arrives; that is accepted, not hidden.
 *    Deliberately NOT for a connected controller that never knew its speed (a VESC with no wheel geometry): that
 *    would hold GPS on for the whole ride of a vehicle that has a controller. The speed rule still shows GPS there
 *    if another consumer (history recording) already holds the fix.
 *
 * No permission prompt follows from this: [RideMapHostState.requestLocationPermission] stays Light-only, so without
 * an earlier grant the demand simply yields no fix and the dashboard keeps its `—`.
 */
internal fun rideGpsSpeedDemand(
    rideScreenActive: Boolean,
    vehicleHasController: Boolean,
    controllerSpeedKnown: Boolean,
    controllerSpeedSeen: Boolean,
): Boolean = rideScreenActive && (!vehicleHasController || (controllerSpeedSeen && !controllerSpeedKnown))

/** The latch behind [rideGpsSpeedDemand]'s dropout exception: once a controller speed was seen it stays seen. */
internal fun rideControllerSpeedSeen(seen: Boolean, controllerSpeedKnown: Boolean): Boolean = seen || controllerSpeedKnown

/**
 * The dropout latch of [rideGpsSpeedDemand], keyed by the vehicle it was earned on: switching vehicles starts it fresh,
 * so a speed seen on one vehicle never arms the exception for the next.
 */
internal data class RideGpsSpeedLatch(val vehicleId: String? = null, val seen: Boolean = false) {
    fun next(vehicleId: String?, motion: ControllerData?): RideGpsSpeedLatch {
        val known = motion != null && RideSpeed.controllerSpeedKnown(motion)
        return if (vehicleId != this.vehicleId) RideGpsSpeedLatch(vehicleId, known)
        else copy(seen = rideControllerSpeedSeen(seen, known))
    }
}

/**
 * RootScreen's whole GPS-speed demand decision from its raw inputs (review I2: the inputs were wired in the composable
 * and untested). "Has a controller" is [hasMotionSource], the rule the history recorder's source registry follows, so a
 * Begode or Leaperkim configured as a battery pack (it decodes a CONTROLLER source) is never treated as battery-only.
 * A latch from another vehicle counts as not seen.
 */
internal fun rideGpsSpeedWanted(
    rideScreenShown: Boolean,
    rideAvailable: Boolean,
    vehicle: Vehicle?,
    motion: ControllerData?,
    latch: RideGpsSpeedLatch,
): Boolean = rideGpsSpeedDemand(
    rideScreenActive = rideScreenShown && rideAvailable && vehicle != null && !vehicle.isDemo,
    vehicleHasController = vehicle?.hasMotionSource == true,
    controllerSpeedKnown = motion != null && RideSpeed.controllerSpeedKnown(motion),
    controllerSpeedSeen = latch.vehicleId == vehicle?.id && latch.seen,
)

/**
 * The native map must outlive the active Decompose child. Hiding it keeps the
 * GL surface and loaded tiles alive while the rider visits another screen.
 */
internal fun rideMapHostState(
    rideAvailable: Boolean,
    activeScreen: RideMapScreen,
    activeStyle: DashboardStyle?,
    demoMode: Boolean = false,
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
        requestLocationPermission = rideMapVisible && !demoMode,
    )
}

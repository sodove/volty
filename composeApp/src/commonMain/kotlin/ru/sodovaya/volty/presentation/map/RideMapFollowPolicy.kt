package ru.sodovaya.volty.presentation.map

enum class RideMapFollowMode {
    FOLLOWING,
    FREE,
}

enum class RideMapCameraMoveOrigin {
    GESTURE,
    PROGRAMMATIC,
}

data class RideMapFollowState(
    val mode: RideMapFollowMode = RideMapFollowMode.FOLLOWING,
    /** Stamped at the gesture's START and again at its END (camera idle). */
    val lastGestureAtMillis: Long? = null,
    /** A gesture (drag, hold, fling) is in progress: the map must not be pulled back from under the finger. */
    val gestureActive: Boolean = false,
)

internal const val RIDE_MAP_AUTO_RETURN_DELAY_MILLIS = 5_000L
internal const val RIDE_MAP_MOVING_SPEED_THRESHOLD_KMH = 2f

internal fun onRideMapCameraMoveStarted(
    state: RideMapFollowState,
    origin: RideMapCameraMoveOrigin,
    nowMillis: Long,
): RideMapFollowState = when (origin) {
    RideMapCameraMoveOrigin.GESTURE -> state.copy(
        mode = RideMapFollowMode.FREE,
        lastGestureAtMillis = nowMillis,
        gestureActive = true,
    )
    RideMapCameraMoveOrigin.PROGRAMMATIC -> state
}

/**
 * The camera went idle after a gesture-originated move (the drag ended and any fling settled): the grace period is
 * measured from HERE. An earlier revision measured it from the gesture's start, so a long drag, or a pan followed by a
 * moment of reading the map, was pulled back 5 s after the START (review I2, 2026-10-28). An idle without a gesture
 * (a programmatic move settling) changes nothing.
 */
internal fun onRideMapCameraIdle(state: RideMapFollowState, nowMillis: Long): RideMapFollowState =
    if (state.gestureActive) state.copy(gestureActive = false, lastGestureAtMillis = nowMillis) else state

internal fun shouldAutoReturnToFollow(
    state: RideMapFollowState,
    speedKmh: Float?,
    nowMillis: Long,
    delayMillis: Long = RIDE_MAP_AUTO_RETURN_DELAY_MILLIS,
): Boolean {
    if (state.gestureActive) return false
    val lastGestureAt = state.lastGestureAtMillis ?: return false
    val speed = speedKmh?.takeIf { it.isFinite() } ?: return false
    return state.mode == RideMapFollowMode.FREE &&
        speed >= RIDE_MAP_MOVING_SPEED_THRESHOLD_KMH &&
        nowMillis - lastGestureAt >= delayMillis
}

internal fun recenterRideMap(state: RideMapFollowState): RideMapFollowState = state.copy(
    mode = RideMapFollowMode.FOLLOWING,
    lastGestureAtMillis = null,
    gestureActive = false,
)

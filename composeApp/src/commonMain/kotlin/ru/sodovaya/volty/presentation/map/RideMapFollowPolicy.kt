package ru.sodovaya.volty.presentation.map

internal enum class RideMapFollowMode {
    FOLLOWING,
    FREE,
}

internal enum class RideMapCameraMoveOrigin {
    GESTURE,
    PROGRAMMATIC,
}

internal data class RideMapFollowState(
    val mode: RideMapFollowMode = RideMapFollowMode.FOLLOWING,
    val lastGestureAtMillis: Long? = null,
)

internal const val RIDE_MAP_AUTO_RETURN_DELAY_MILLIS = 2_000L
internal const val RIDE_MAP_MOVING_SPEED_THRESHOLD_KMH = 2f

internal fun onRideMapCameraMoveStarted(
    state: RideMapFollowState,
    origin: RideMapCameraMoveOrigin,
    nowMillis: Long,
): RideMapFollowState = when (origin) {
    RideMapCameraMoveOrigin.GESTURE -> state.copy(
        mode = RideMapFollowMode.FREE,
        lastGestureAtMillis = nowMillis,
    )
    RideMapCameraMoveOrigin.PROGRAMMATIC -> state
}

internal fun shouldAutoReturnToFollow(
    state: RideMapFollowState,
    speedKmh: Float?,
    nowMillis: Long,
    delayMillis: Long = RIDE_MAP_AUTO_RETURN_DELAY_MILLIS,
): Boolean {
    val lastGestureAt = state.lastGestureAtMillis ?: return false
    val speed = speedKmh?.takeIf { it.isFinite() } ?: return false
    return state.mode == RideMapFollowMode.FREE &&
        speed >= RIDE_MAP_MOVING_SPEED_THRESHOLD_KMH &&
        nowMillis - lastGestureAt >= delayMillis
}

internal fun recenterRideMap(state: RideMapFollowState): RideMapFollowState = state.copy(
    mode = RideMapFollowMode.FOLLOWING,
    lastGestureAtMillis = null,
)

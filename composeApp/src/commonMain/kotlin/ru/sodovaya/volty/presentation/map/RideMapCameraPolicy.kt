package ru.sodovaya.volty.presentation.map

/** Camera defaults used even before Android supplies a location fix. */
internal data class RideMapCameraPolicy(
    val tiltDegrees: Double,
    val appliesWithoutTarget: Boolean,
)

internal val defaultRideMapCameraPolicy = RideMapCameraPolicy(
    // MapLibre's 0° is a flat top-down view. Keep the same oblique angle used
    // when a location is available, but apply it to the initial world view too.
    tiltDegrees = 58.0,
    appliesWithoutTarget = true,
)

internal const val RIDE_MAP_MIN_ZOOM = 15.0
internal const val RIDE_MAP_MAX_ZOOM = 16.5
private const val RIDE_MAP_ZOOM_SPEED_LIMIT_KMH = 140f

/** Higher speed needs more context around the rider; invalid speed preserves the current zoom. */
internal fun rideMapZoomForSpeed(
    speedKmh: Float,
    fallbackZoom: Double = 15.0,
): Double {
    if (!speedKmh.isFinite()) return fallbackZoom
    val progress = (speedKmh.coerceAtLeast(0f) / RIDE_MAP_ZOOM_SPEED_LIMIT_KMH).coerceIn(0f, 1f)
    return RIDE_MAP_MAX_ZOOM - progress * (RIDE_MAP_MAX_ZOOM - RIDE_MAP_MIN_ZOOM)
}

internal fun rideMapBearingDegrees(
    gpsBearingDegrees: Float?,
    speedKmh: Float,
    fallbackDegrees: Double,
): Double = gpsBearingDegrees
    ?.takeIf { it.isFinite() && it >= 0f && it < 360f && speedKmh >= 2f }
    ?.toDouble()
    ?: fallbackDegrees

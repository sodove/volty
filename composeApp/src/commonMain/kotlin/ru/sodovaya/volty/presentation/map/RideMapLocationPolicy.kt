package ru.sodovaya.volty.presentation.map

/**
 * Location cadence for the rider-facing map. The platform provider may still
 * be limited by the GNSS chipset, but the app must not add a one-second or
 * one-metre throttle of its own.
 */
internal data class RideMapLocationUpdatePolicy(
    val minIntervalMillis: Long,
    val minDistanceMeters: Float,
    val renderIntervalMillis: Long,
    val maxPredictionAgeMillis: Long,
)

internal val defaultRideMapLocationUpdatePolicy = RideMapLocationUpdatePolicy(
    minIntervalMillis = 100L,
    minDistanceMeters = 0f,
    // Camera movement is driven directly from the frame clock. This is not a
    // location request interval; it is the visual refresh cadence.
    renderIntervalMillis = 16L,
    // Do not let dead reckoning continue indefinitely after a GNSS fix stops.
    maxPredictionAgeMillis = 1_500L,
)

internal data class RideMapPredictedCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/**
 * Advances the last earned fix while the next one is in flight. This only
 * smooths the presentation; it never replaces the raw fix used for the trail
 * or telemetry.
 */
internal fun predictRideMapCoordinate(
    latitude: Double,
    longitude: Double,
    speedMetersPerSecond: Float?,
    bearingDegrees: Float?,
    ageMillis: Long,
): RideMapPredictedCoordinate {
    val speed = speedMetersPerSecond?.takeIf { it.isFinite() && it > 0f } ?: return RideMapPredictedCoordinate(latitude, longitude)
    val bearing = bearingDegrees?.takeIf { it.isFinite() && it >= 0f && it < 360f }
        ?: return RideMapPredictedCoordinate(latitude, longitude)
    val ageSeconds = ageMillis.coerceIn(0L, defaultRideMapLocationUpdatePolicy.maxPredictionAgeMillis) / 1_000.0
    val distanceMeters = speed * ageSeconds
    val bearingRadians = bearing * Math.PI / 180.0
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * kotlin.math.cos(latitude * Math.PI / 180.0)
    if (metersPerDegreeLongitude <= 0.0) return RideMapPredictedCoordinate(latitude, longitude)
    return RideMapPredictedCoordinate(
        latitude = latitude + kotlin.math.cos(bearingRadians) * distanceMeters / metersPerDegreeLatitude,
        longitude = longitude + kotlin.math.sin(bearingRadians) * distanceMeters / metersPerDegreeLongitude,
    )
}

package ru.sodovaya.volty.presentation.map

internal const val TRAIL_FADE_DISTANCE_METERS = 180.0

/** Newest segment is fully visible; segments older than the window disappear. */
internal fun trailOpacityForDistanceMeters(distanceMeters: Double): Float {
    if (!distanceMeters.isFinite()) return 0f
    val progress = (distanceMeters.coerceAtLeast(0.0) / TRAIL_FADE_DISTANCE_METERS).coerceIn(0.0, 1.0)
    return (1.0 - progress).toFloat()
}

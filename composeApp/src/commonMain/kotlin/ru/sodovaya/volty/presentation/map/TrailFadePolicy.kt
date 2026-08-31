package ru.sodovaya.volty.presentation.map

internal const val TRAIL_FADE_DISTANCE_METERS = 180.0
internal const val TRAIL_FADE_START_AGE_MILLIS = 20_000L
internal const val TRAIL_FADE_DURATION_MILLIS = 10_000L
internal const val TRAIL_MAX_AGE_MILLIS =
    TRAIL_FADE_START_AGE_MILLIS + TRAIL_FADE_DURATION_MILLIS
internal const val TRAIL_RENDER_INTERVAL_MILLIS = 100L

internal fun shouldRenderTrail(nowMillis: Long, lastRenderMillis: Long?): Boolean =
    lastRenderMillis == null || nowMillis - lastRenderMillis >= TRAIL_RENDER_INTERVAL_MILLIS

/** Newest segment is fully visible; segments older than the window disappear. */
internal fun trailOpacityForDistanceMeters(distanceMeters: Double): Float {
    if (!distanceMeters.isFinite()) return 0f
    val progress = (distanceMeters.coerceAtLeast(0.0) / TRAIL_FADE_DISTANCE_METERS).coerceIn(0.0, 1.0)
    return (1.0 - progress).toFloat()
}

/** Newest points stay solid for 20 seconds, then fade out over the next 10. */
internal fun trailOpacityForAgeMillis(ageMillis: Long): Float {
    if (ageMillis <= TRAIL_FADE_START_AGE_MILLIS) return 1f
    val progress = ((ageMillis - TRAIL_FADE_START_AGE_MILLIS).toDouble() /
        TRAIL_FADE_DURATION_MILLIS.toDouble()).coerceIn(0.0, 1.0)
    return (1.0 - progress).toFloat()
}

internal fun shouldRetainTrailPoint(ageMillis: Long): Boolean =
    ageMillis < TRAIL_MAX_AGE_MILLIS

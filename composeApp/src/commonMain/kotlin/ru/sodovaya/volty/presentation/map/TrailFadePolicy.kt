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

/**
 * Lowest opacity a *visible* trail segment may have. The trail is the one line on the map the rider
 * drew by riding, so it fades with age and distance but never to a ghost: the first fade was linear
 * to zero, which left most of the 180 m window pale against 3D buildings and dashed footpaths.
 */
internal const val TRAIL_MIN_OPACITY = 0.45f

/** Core line width in dp-ish map pixels (was 3.2) and the extra width of the contrasting casing. */
internal const val TRAIL_LINE_WIDTH = 6f
internal const val TRAIL_CASING_EXTRA_WIDTH = 3.5f

/**
 * Opacity of a trail segment [distanceBehindMeters] behind the rider and [ageMillis] old: 1 when
 * fresh, easing to [TRAIL_MIN_OPACITY] at the edge of the window, 0 only once the segment has left
 * the window (past [TRAIL_FADE_DISTANCE_METERS] or [TRAIL_MAX_AGE_MILLIS]) and is dropped.
 */
internal fun trailSegmentOpacity(distanceBehindMeters: Double, ageMillis: Long): Float {
    if (!distanceBehindMeters.isFinite() || distanceBehindMeters >= TRAIL_FADE_DISTANCE_METERS) return 0f
    if (!shouldRetainTrailPoint(ageMillis)) return 0f
    val fade = minOf(
        trailOpacityForDistanceMeters(distanceBehindMeters),
        trailOpacityForAgeMillis(ageMillis),
    )
    return TRAIL_MIN_OPACITY + (1f - TRAIL_MIN_OPACITY) * fade
}

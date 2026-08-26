package ru.sodovaya.volty.presentation.map

import kotlin.math.abs
import kotlin.math.exp

/** The camera values drawn on a frame, separate from the latest sensor target. */
internal data class RideMapCameraFrame(
    val zoom: Double,
    val bearingDegrees: Double,
    val center: RideMapPredictedCoordinate? = null,
)

/** Keeps user-camera fallbacks inside the zoom range used by ride follow mode. */
internal fun clampRideMapCameraZoom(zoom: Double): Double =
    zoom.takeIf { it.isFinite() }?.coerceIn(RIDE_MAP_MIN_ZOOM, RIDE_MAP_MAX_ZOOM)
        ?: RIDE_MAP_MAX_ZOOM

/**
 * Frame-rate independent enough for the ride map: a new GPS/controller target
 * is approached over several render frames instead of being assigned directly
 * to MapLibre. The map target itself is still driven every frame by the motion
 * estimator; this class only smooths the visibly jumpy zoom and heading.
 */
internal class RideMapCameraSmoother(
    private val zoomResponseMillis: Long = 220L,
    private val bearingResponseMillis: Long = 180L,
    private val centerResponseMillis: Long = 140L,
) {
    private var current: RideMapCameraFrame? = null

    fun reset(frame: RideMapCameraFrame) {
        current = frame.copy(bearingDegrees = normalizeBearing(frame.bearingDegrees))
    }

    fun advance(
        targetZoom: Double,
        targetBearingDegrees: Double,
        targetCenter: RideMapPredictedCoordinate? = null,
        deltaMillis: Long,
    ): RideMapCameraFrame {
        val target = RideMapCameraFrame(
            zoom = targetZoom,
            bearingDegrees = normalizeBearing(targetBearingDegrees),
            center = targetCenter,
        )
        val previous = current ?: return target.also { current = it }
        val zoomAlpha = responseAlpha(deltaMillis, zoomResponseMillis)
        val bearingAlpha = responseAlpha(deltaMillis, bearingResponseMillis)
        val centerAlpha = responseAlpha(deltaMillis, centerResponseMillis)
        val next = RideMapCameraFrame(
            zoom = lerp(previous.zoom, target.zoom, zoomAlpha),
            bearingDegrees = lerpBearing(previous.bearingDegrees, target.bearingDegrees, bearingAlpha),
            center = when {
                target.center == null -> null
                previous.center == null -> target.center
                else -> RideMapPredictedCoordinate(
                    latitude = lerp(previous.center.latitude, target.center.latitude, centerAlpha),
                    longitude = lerp(previous.center.longitude, target.center.longitude, centerAlpha),
                )
            },
        )
        current = next
        return next
    }

    private fun responseAlpha(deltaMillis: Long, responseMillis: Long): Double =
        (1.0 - exp(
            -deltaMillis.coerceAtLeast(0L).toDouble() / responseMillis.coerceAtLeast(1L),
        )).coerceIn(0.0, 1.0)
}

private fun lerp(start: Double, end: Double, fraction: Double): Double =
    start + (end - start) * fraction

private fun lerpBearing(start: Double, end: Double, fraction: Double): Double {
    val delta = ((end - start + 540.0) % 360.0) - 180.0
    return normalizeBearing(start + delta * fraction)
}

private fun normalizeBearing(value: Double): Double {
    if (!value.isFinite()) return 0.0
    val normalized = value % 360.0
    return if (abs(normalized) < 0.000001) 0.0
    else if (normalized < 0.0) normalized + 360.0
    else normalized
}

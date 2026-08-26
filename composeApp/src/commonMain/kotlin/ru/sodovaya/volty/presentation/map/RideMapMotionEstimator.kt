package ru.sodovaya.volty.presentation.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class RideMapMotionFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
)

internal enum class RideMapTimestampSource {
    WALL_CLOCK,
    ELAPSED_REALTIME,
}

/** Converts platform timestamps to the wall-clock domain used by map frames. */
internal class RideMapSessionTimestampNormalizer(
    private val wallClockStartMillis: Long,
    private val elapsedRealtimeStartMillis: Long,
) {
    fun normalize(timestampMillis: Long, source: RideMapTimestampSource): Long = when (source) {
        RideMapTimestampSource.WALL_CLOCK -> timestampMillis
        RideMapTimestampSource.ELAPSED_REALTIME ->
            wallClockStartMillis + (timestampMillis - elapsedRealtimeStartMillis)
    }
}

internal data class RideMapMotionEstimate(
    val coordinate: RideMapPredictedCoordinate,
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
)

internal data class RideMapMotionEstimatorPolicy(
    val maxPredictionAgeMillis: Long,
    val correctionDurationMillis: Long,
    val correctionDeadZoneMeters: Double,
    val hardResetDistanceMeters: Double,
    val movingSpeedThresholdMetersPerSecond: Float,
    val bearingSmoothingAlpha: Double,
)

internal val defaultRideMapMotionEstimatorPolicy = RideMapMotionEstimatorPolicy(
    maxPredictionAgeMillis = defaultRideMapLocationUpdatePolicy.maxPredictionAgeMillis,
    correctionDurationMillis = 500L,
    correctionDeadZoneMeters = 2.0,
    hardResetDistanceMeters = 30.0,
    movingSpeedThresholdMetersPerSecond = 0.55f,
    bearingSmoothingAlpha = 0.25,
)

/**
 * Display-only dead-reckoning state for the ride map. Raw fixes remain owned by the
 * Android location layer; this class only estimates what should be drawn this frame.
 */
internal class RideMapMotionEstimator(
    private val policy: RideMapMotionEstimatorPolicy = defaultRideMapMotionEstimatorPolicy,
) {
    private var anchor: Anchor? = null

    fun accept(fix: RideMapMotionFix): Boolean {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.timestampMillis < 0L) return false
        val previous = anchor
        if (previous != null && fix.timestampMillis < previous.fix.timestampMillis) return false

        val oldDisplay = previous?.let { positionAt(it, fix.timestampMillis) }
        val previousFix = previous?.fix
        val derivedMotion = previousFix?.let { deriveMotion(it, fix) }
        val speed = validSpeed(fix.speedMetersPerSecond)
            ?: derivedMotion?.speedMetersPerSecond
            ?: previous?.speedMetersPerSecond
        val moving = speed != null && speed >= policy.movingSpeedThresholdMetersPerSecond
        val candidateBearing = validBearing(fix.bearingDegrees)
            ?: derivedMotion?.bearingDegrees
        val filteredBearing = when {
            !moving -> previous?.bearingDegrees
            candidateBearing == null -> previous?.bearingDegrees
            previous?.bearingDegrees == null -> candidateBearing
            else -> lerpBearing(previous.bearingDegrees, candidateBearing, policy.bearingSmoothingAlpha)
        }

        val correction = if (oldDisplay == null) {
            Correction()
        } else {
            val delta = toMeters(
                from = RideMapPredictedCoordinate(fix.latitude, fix.longitude),
                to = oldDisplay,
                latitudeReference = fix.latitude,
            )
            val distance = hypot(delta.east, delta.north)
            when {
                distance <= policy.correctionDeadZoneMeters -> Correction()
                distance >= policy.hardResetDistanceMeters -> Correction()
                else -> Correction(
                    eastMeters = delta.east,
                    northMeters = delta.north,
                    startedAtMillis = fix.timestampMillis,
                    durationMillis = policy.correctionDurationMillis,
                )
            }
        }

        anchor = Anchor(
            fix = fix,
            speedMetersPerSecond = speed,
            bearingDegrees = filteredBearing,
            correction = correction,
        )
        return true
    }

    fun estimate(
        nowMillis: Long,
        speedMetersPerSecondOverride: Float? = null,
    ): RideMapMotionEstimate? {
        val current = anchor ?: return null
        val speed = validSpeed(speedMetersPerSecondOverride) ?: current.speedMetersPerSecond
        val coordinate = positionAt(
            current,
            nowMillis,
            speedMetersPerSecondOverride = speed,
        )
        return RideMapMotionEstimate(
            coordinate = coordinate,
            speedMetersPerSecond = speed,
            bearingDegrees = current.bearingDegrees,
        )
    }

    private fun positionAt(
        state: Anchor,
        nowMillis: Long,
        speedMetersPerSecondOverride: Float? = null,
    ): RideMapPredictedCoordinate {
        val ageMillis = (nowMillis - state.fix.timestampMillis)
            .coerceAtLeast(0L)
            .coerceAtMost(policy.maxPredictionAgeMillis)
        val seconds = ageMillis / 1_000.0
        val speed = validSpeed(speedMetersPerSecondOverride) ?: state.speedMetersPerSecond
        val predicted = moveCoordinate(
            latitude = state.fix.latitude,
            longitude = state.fix.longitude,
            speedMetersPerSecond = speed,
            bearingDegrees = state.bearingDegrees,
            seconds = seconds,
        )
        val correctionScale = state.correction.remainingScale(nowMillis)
        return offsetCoordinate(
            coordinate = predicted,
            eastMeters = state.correction.eastMeters * correctionScale,
            northMeters = state.correction.northMeters * correctionScale,
        )
    }

    private data class Anchor(
        val fix: RideMapMotionFix,
        val speedMetersPerSecond: Float?,
        val bearingDegrees: Float?,
        val correction: Correction,
    )

    private data class Correction(
        val eastMeters: Double = 0.0,
        val northMeters: Double = 0.0,
        val startedAtMillis: Long = 0L,
        val durationMillis: Long = 0L,
    ) {
        fun remainingScale(nowMillis: Long): Double {
            if (durationMillis <= 0L || nowMillis <= startedAtMillis) return if (durationMillis > 0L) 1.0 else 0.0
            return 1.0 - ((nowMillis - startedAtMillis).toDouble() / durationMillis).coerceIn(0.0, 1.0)
        }
    }
}

private data class DerivedMotion(
    val speedMetersPerSecond: Float?,
    val bearingDegrees: Float?,
)

private data class MeterOffset(
    val east: Double,
    val north: Double,
)

private fun deriveMotion(previous: RideMapMotionFix, current: RideMapMotionFix): DerivedMotion {
    val dtSeconds = (current.timestampMillis - previous.timestampMillis) / 1_000.0
    if (dtSeconds <= 0.0) return DerivedMotion(null, null)
    val from = RideMapPredictedCoordinate(previous.latitude, previous.longitude)
    val to = RideMapPredictedCoordinate(current.latitude, current.longitude)
    val offset = toMeters(from, to, latitudeReference = previous.latitude)
    val distance = hypot(offset.east, offset.north)
    if (distance <= 0.5) return DerivedMotion(null, null)
    val bearing = (Math.toDegrees(atan2(offset.east, offset.north)) + 360.0) % 360.0
    return DerivedMotion(
        speedMetersPerSecond = (distance / dtSeconds).toFloat(),
        bearingDegrees = bearing.toFloat(),
    )
}

private fun moveCoordinate(
    latitude: Double,
    longitude: Double,
    speedMetersPerSecond: Float?,
    bearingDegrees: Float?,
    seconds: Double,
): RideMapPredictedCoordinate {
    val speed = validSpeed(speedMetersPerSecond) ?: return RideMapPredictedCoordinate(latitude, longitude)
    val bearing = validBearing(bearingDegrees) ?: return RideMapPredictedCoordinate(latitude, longitude)
    val distance = speed * seconds
    val radians = Math.toRadians(bearing.toDouble())
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(latitude))
    if (metersPerDegreeLongitude <= 0.0) return RideMapPredictedCoordinate(latitude, longitude)
    return RideMapPredictedCoordinate(
        latitude = latitude + cos(radians) * distance / metersPerDegreeLatitude,
        longitude = longitude + sin(radians) * distance / metersPerDegreeLongitude,
    )
}

private fun offsetCoordinate(
    coordinate: RideMapPredictedCoordinate,
    eastMeters: Double,
    northMeters: Double,
): RideMapPredictedCoordinate {
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(coordinate.latitude))
    if (metersPerDegreeLongitude <= 0.0) return coordinate
    return RideMapPredictedCoordinate(
        latitude = coordinate.latitude + northMeters / metersPerDegreeLatitude,
        longitude = coordinate.longitude + eastMeters / metersPerDegreeLongitude,
    )
}

private fun toMeters(
    from: RideMapPredictedCoordinate,
    to: RideMapPredictedCoordinate,
    latitudeReference: Double,
): MeterOffset {
    val metersPerDegreeLatitude = 111_320.0
    val metersPerDegreeLongitude = metersPerDegreeLatitude * cos(Math.toRadians(latitudeReference))
    return MeterOffset(
        east = (to.longitude - from.longitude) * metersPerDegreeLongitude,
        north = (to.latitude - from.latitude) * metersPerDegreeLatitude,
    )
}

private fun validSpeed(speed: Float?): Float? = speed?.takeIf { it.isFinite() && it >= 0f }

private fun validBearing(bearing: Float?): Float? = bearing?.takeIf { it.isFinite() && it >= 0f && it < 360f }

private fun lerpBearing(from: Float, to: Float, alpha: Double): Float {
    val delta = ((to - from + 540f) % 360f) - 180f
    return ((from + delta * alpha) + 360.0).mod(360.0).toFloat()
}

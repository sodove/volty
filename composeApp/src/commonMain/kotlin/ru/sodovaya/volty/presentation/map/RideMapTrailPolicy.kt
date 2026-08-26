package ru.sodovaya.volty.presentation.map

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal data class RideMapTrailSample(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
)

internal data class RideMapTrailPolicy(
    val maxFixGapMillis: Long = 3_000L,
    val maxUnreportedSegmentMeters: Double = 80.0,
    val maxAcceptedAccuracyMeters: Float = 75f,
    val speedDistanceMultiplier: Double = 1.8,
    val speedDistanceBufferMeters: Double = 20.0,
)

internal val defaultRideMapTrailPolicy = RideMapTrailPolicy()

/** Splits history into visual runs without discarding runs older than a bad newest fix. */
internal fun connectedRideMapTrailSegments(
    samples: List<RideMapTrailSample>,
    policy: RideMapTrailPolicy = defaultRideMapTrailPolicy,
): List<List<RideMapTrailSample>> {
    if (samples.isEmpty()) return emptyList()
    val segments = mutableListOf<MutableList<RideMapTrailSample>>()
    var current = mutableListOf(samples.first())
    for (sample in samples.drop(1)) {
        if (shouldConnectRideMapTrail(current.last(), sample, policy)) {
            current += sample
        } else {
            segments += current
            current = mutableListOf(sample)
        }
    }
    segments += current
    return segments
}

/**
 * Decides whether a raw GPS segment is safe to draw as one straight line.
 *
 * A trail is historical evidence, not a prediction. When a phone gives us a
 * stale or implausibly distant fix, connecting it to the previous point makes
 * the line cut across whole blocks. The marker and camera may still use that
 * fix; only the visual history is deliberately broken here.
 */
internal fun shouldConnectRideMapTrail(
    previous: RideMapTrailSample,
    current: RideMapTrailSample,
    policy: RideMapTrailPolicy = defaultRideMapTrailPolicy,
): Boolean {
    if (!validCoordinate(previous) || !validCoordinate(current)) return false
    val elapsedMillis = current.timestampMillis - previous.timestampMillis
    if (elapsedMillis <= 0L || elapsedMillis > policy.maxFixGapMillis) return false

    val accuracies = listOfNotNull(previous.accuracyMeters, current.accuracyMeters)
    if (accuracies.any { !it.isFinite() || it < 0f || it > policy.maxAcceptedAccuracyMeters }) return false

    val distanceMeters = distanceMeters(previous, current)
    if (!distanceMeters.isFinite()) return false
    if (distanceMeters <= 1.0) return true

    val speed = current.speedMetersPerSecond
        ?.takeIf { it.isFinite() && it >= 0f }
        ?: previous.speedMetersPerSecond?.takeIf { it.isFinite() && it >= 0f }
    val elapsedSeconds = elapsedMillis / 1_000.0
    val maximumDistance = if (speed == null) {
        policy.maxUnreportedSegmentMeters
    } else {
        max(
            policy.maxUnreportedSegmentMeters,
            speed * elapsedSeconds * policy.speedDistanceMultiplier + policy.speedDistanceBufferMeters,
        )
    }
    return distanceMeters <= maximumDistance
}

private fun validCoordinate(sample: RideMapTrailSample): Boolean =
    sample.latitude.isFinite() && sample.longitude.isFinite() &&
        sample.latitude in -90.0..90.0 && sample.longitude in -180.0..180.0

private fun distanceMeters(first: RideMapTrailSample, second: RideMapTrailSample): Double {
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(second.longitude - first.longitude)
    val a = sin(dLat / 2.0).pow(2.0) +
        cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
    return 6_371_000.0 * 2.0 * kotlin.math.atan2(sqrt(a), sqrt(1.0 - a))
}

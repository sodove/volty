package ru.sodovaya.volty.domain.navigation

import ru.sodovaya.volty.domain.location.RideLocationFix
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

enum class NavigationPositionProblem {
    UNKNOWN,
    STALE,
    POOR_ACCURACY,
}

data class RouteGuidance(
    val routeId: String,
    val maneuver: RouteManeuver,
    val distanceToManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Long,
    val projectedShapeIndex: Int,
)

sealed interface RouteProgressUpdate {
    data class Unavailable(val problem: NavigationPositionProblem) : RouteProgressUpdate
    data class OnRoute(val guidance: RouteGuidance) : RouteProgressUpdate
    data class OffRouteCandidate(val distanceFromRouteMeters: Double) : RouteProgressUpdate
    data class OffRouteConfirmed(val distanceFromRouteMeters: Double, val episodeId: Long) : RouteProgressUpdate
    data object Arrived : RouteProgressUpdate
}

class RouteProgressEngine(
    private val policy: RouteProgressPolicy = defaultRouteProgressPolicy,
) {
    private var activeRouteId: String? = null
    private var lastCapturedAtEpochMillis: Long? = null
    private var lastElapsedRealtimeMillis: Long? = null
    private var lastProjectedDistanceMeters: Double? = null
    private var offRouteCount = 0
    private var offRouteFirstCapturedAtEpochMillis: Long? = null
    private var offRouteConfirmed = false
    private var offRouteEpisodeId = 0L
    private var arrivalCount = 0
    private var arrived = false

    fun reset(routeId: String?) {
        activeRouteId = routeId
        lastCapturedAtEpochMillis = null
        lastElapsedRealtimeMillis = null
        lastProjectedDistanceMeters = null
        offRouteCount = 0
        offRouteFirstCapturedAtEpochMillis = null
        offRouteConfirmed = false
        offRouteEpisodeId = 0L
        arrivalCount = 0
        arrived = false
    }

    fun update(
        route: RouteAlternative,
        fix: RideLocationFix?,
        nowEpochMillis: Long,
    ): RouteProgressUpdate {
        if (activeRouteId != route.id) reset(route.id)

        val trustedFix = validateFix(fix, nowEpochMillis)
            ?: return unavailable(fix, nowEpochMillis)

        lastCapturedAtEpochMillis = trustedFix.capturedAtEpochMillis
        lastElapsedRealtimeMillis = trustedFix.elapsedRealtimeMillis

        val cumulativeGeometryMeters = cumulativeGeometryDistances(route.geometry)
        val totalGeometryMeters = cumulativeGeometryMeters.last()
        val projection = project(route.geometry, cumulativeGeometryMeters, trustedFix)
        val projectedDistanceMeters = monotonicProgress(projection.alongMeters)
        lastProjectedDistanceMeters = projectedDistanceMeters

        val routeScale = if (totalGeometryMeters > 0.0) {
            route.distanceMeters / totalGeometryMeters
        } else {
            0.0
        }
        val remainingDistanceMeters = max(
            0.0,
            route.distanceMeters - projectedDistanceMeters * routeScale,
        )
        val remainingDurationSeconds = if (route.distanceMeters > 0.0) {
            (route.durationSeconds.toDouble() * remainingDistanceMeters / route.distanceMeters)
                .coerceIn(0.0, route.durationSeconds.toDouble())
                .toLong()
        } else {
            0L
        }
        val projectedShapeIndex = projection.shapeIndex
        val maneuver = nextManeuver(route.maneuvers, projectedShapeIndex)
        val maneuverDistance = cumulativeGeometryMeters[maneuver.shapeIndex] * routeScale
        val distanceToManeuverMeters = max(0.0, maneuverDistance - projectedDistanceMeters * routeScale)
        val guidance = RouteGuidance(
            routeId = route.id,
            maneuver = maneuver,
            distanceToManeuverMeters = distanceToManeuverMeters,
            remainingDistanceMeters = remainingDistanceMeters,
            remainingDurationSeconds = remainingDurationSeconds,
            projectedShapeIndex = projectedShapeIndex,
        )

        if (remainingDistanceMeters <= policy.arrivalRemainingDistanceMeters &&
            distanceMeters(trustedFix.coordinate, route.geometry.last()) <= policy.arrivalDestinationDistanceMeters
        ) {
            offRouteCount = 0
            offRouteFirstCapturedAtEpochMillis = null
            offRouteConfirmed = false
            arrivalCount += 1
            if (arrived || arrivalCount >= policy.arrivalConfirmationFixes) {
                arrived = true
                return RouteProgressUpdate.Arrived
            }
            return RouteProgressUpdate.OnRoute(guidance)
        }

        arrivalCount = 0
        arrived = false

        val offRouteThreshold = policy.offRouteThreshold(trustedFix.accuracyMeters)
        if (projection.distanceFromRouteMeters > offRouteThreshold) {
            offRouteCount += 1
            if (offRouteFirstCapturedAtEpochMillis == null) {
                offRouteFirstCapturedAtEpochMillis = trustedFix.capturedAtEpochMillis
            }
            val evidenceSpanMillis = trustedFix.capturedAtEpochMillis -
                (offRouteFirstCapturedAtEpochMillis ?: trustedFix.capturedAtEpochMillis)
            if (!offRouteConfirmed &&
                offRouteCount >= policy.offRouteConfirmationFixes &&
                evidenceSpanMillis >= policy.offRouteConfirmationWindowMillis
            ) {
                offRouteConfirmed = true
                offRouteEpisodeId += 1L
            }
            return if (offRouteConfirmed) {
                RouteProgressUpdate.OffRouteConfirmed(
                    distanceFromRouteMeters = projection.distanceFromRouteMeters,
                    episodeId = offRouteEpisodeId,
                )
            } else {
                RouteProgressUpdate.OffRouteCandidate(projection.distanceFromRouteMeters)
            }
        }

        offRouteCount = 0
        offRouteFirstCapturedAtEpochMillis = null
        offRouteConfirmed = false
        return RouteProgressUpdate.OnRoute(guidance)
    }

    private fun validateFix(fix: RideLocationFix?, nowEpochMillis: Long): RideLocationFix? {
        if (fix == null) return null
        if (!fix.coordinate.latitude.isFinite() || !fix.coordinate.longitude.isFinite()) return null
        if (fix.capturedAtEpochMillis > nowEpochMillis) return null
        val ageMillis = nowEpochMillis - fix.capturedAtEpochMillis
        if (ageMillis > policy.freshFixMaxAgeMillis) return null
        if (fix.accuracyMeters > policy.maxAccuracyMeters) return null
        val previousCapturedAt = lastCapturedAtEpochMillis
        if (previousCapturedAt != null && fix.capturedAtEpochMillis < previousCapturedAt) return null
        val previousElapsed = lastElapsedRealtimeMillis
        if (previousElapsed != null && fix.elapsedRealtimeMillis != null &&
            fix.elapsedRealtimeMillis < previousElapsed
        ) return null
        return fix
    }

    private fun unavailable(fix: RideLocationFix?, nowEpochMillis: Long): RouteProgressUpdate.Unavailable {
        offRouteCount = 0
        offRouteFirstCapturedAtEpochMillis = null
        offRouteConfirmed = false
        arrivalCount = 0
        arrived = false
        val problem = when {
            fix == null -> NavigationPositionProblem.UNKNOWN
            fix.capturedAtEpochMillis > nowEpochMillis -> NavigationPositionProblem.UNKNOWN
            nowEpochMillis - fix.capturedAtEpochMillis > policy.freshFixMaxAgeMillis -> NavigationPositionProblem.STALE
            fix.accuracyMeters > policy.maxAccuracyMeters -> NavigationPositionProblem.POOR_ACCURACY
            else -> NavigationPositionProblem.UNKNOWN
        }
        return RouteProgressUpdate.Unavailable(problem)
    }

    private fun monotonicProgress(candidateMeters: Double): Double {
        val previous = lastProjectedDistanceMeters ?: return candidateMeters
        return max(previous, candidateMeters)
    }

    private fun nextManeuver(maneuvers: List<RouteManeuver>, projectedShapeIndex: Int): RouteManeuver =
        maneuvers.firstOrNull { it.shapeIndex > projectedShapeIndex } ?: maneuvers.last()

    private fun project(
        geometry: List<GeoCoordinate>,
        cumulativeMeters: List<Double>,
        fix: RideLocationFix,
    ): Projection {
        val candidates = geometry.zipWithNext().mapIndexed { index, (start, end) ->
            projectOnSegment(
                start = start,
                end = end,
                point = fix.coordinate,
                segmentIndex = index,
                segmentStartMeters = cumulativeMeters[index],
                segmentLengthMeters = cumulativeMeters[index + 1] - cumulativeMeters[index],
            )
        }
        val previous = lastProjectedDistanceMeters
        val nearbyCandidates = if (previous == null) {
            candidates
        } else {
            candidates.filter {
                it.alongMeters >= previous - policy.backwardsProgressToleranceMeters &&
                    it.alongMeters <= previous + policy.projectionSearchWindowMeters
            }.ifEmpty { candidates }
        }
        return nearbyCandidates.minWithOrNull(
            compareBy<Projection> { it.distanceFromRouteMeters }
                .thenBy { candidate -> if (previous == null) 0.0 else abs(candidate.alongMeters - previous) },
        ) ?: candidates.first()
    }

    private fun projectOnSegment(
        start: GeoCoordinate,
        end: GeoCoordinate,
        point: GeoCoordinate,
        segmentIndex: Int,
        segmentStartMeters: Double,
        segmentLengthMeters: Double,
    ): Projection {
        val referenceLatitudeRadians = radians((start.latitude + end.latitude + point.latitude) / 3.0)
        val metersPerRadian = EARTH_RADIUS_METERS
        val startX = 0.0
        val startY = 0.0
        val endX = (radians(end.longitude - start.longitude) * cos(referenceLatitudeRadians)) * metersPerRadian
        val endY = radians(end.latitude - start.latitude) * metersPerRadian
        val pointX = (radians(point.longitude - start.longitude) * cos(referenceLatitudeRadians)) * metersPerRadian
        val pointY = radians(point.latitude - start.latitude) * metersPerRadian
        val dx = endX - startX
        val dy = endY - startY
        val segmentSquared = dx * dx + dy * dy
        val rawT = if (segmentSquared <= 0.0) 0.0 else (pointX * dx + pointY * dy) / segmentSquared
        val t = rawT.coerceIn(0.0, 1.0)
        val projectedX = dx * t
        val projectedY = dy * t
        val lateralDistance = sqrt(
            (pointX - projectedX) * (pointX - projectedX) +
                (pointY - projectedY) * (pointY - projectedY),
        )
        val alongMeters = segmentStartMeters + segmentLengthMeters * t
        val shapeIndex = if (t >= SHAPE_ENDPOINT_EPSILON) segmentIndex + 1 else segmentIndex
        return Projection(
            alongMeters = alongMeters,
            distanceFromRouteMeters = lateralDistance,
            shapeIndex = shapeIndex,
        )
    }

    private fun cumulativeGeometryDistances(geometry: List<GeoCoordinate>): List<Double> {
        val cumulative = ArrayList<Double>(geometry.size)
        cumulative += 0.0
        geometry.zipWithNext().forEach { (start, end) ->
            cumulative += cumulative.last() + distanceMeters(start, end)
        }
        return cumulative
    }

    private data class Projection(
        val alongMeters: Double,
        val distanceFromRouteMeters: Double,
        val shapeIndex: Int,
    )

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_000.0
        const val SHAPE_ENDPOINT_EPSILON = 0.999999

        fun radians(degrees: Double): Double = degrees * PI / 180.0

        fun distanceMeters(first: GeoCoordinate, second: GeoCoordinate): Double {
            val firstLatitude = radians(first.latitude)
            val secondLatitude = radians(second.latitude)
            val deltaLatitude = secondLatitude - firstLatitude
            val deltaLongitude = radians(second.longitude - first.longitude)
            val sinLatitude = sin(deltaLatitude / 2.0)
            val sinLongitude = sin(deltaLongitude / 2.0)
            val a = sinLatitude * sinLatitude +
                cos(firstLatitude) * cos(secondLatitude) * sinLongitude * sinLongitude
            return EARTH_RADIUS_METERS * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        }
    }
}

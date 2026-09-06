package ru.sodovaya.volty.domain.navigation.routing

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.RouteAlternative

/** Provider-independent ordering and avoidance rules for route candidates. */
object RouteAlternativePolicy {
    private const val MAX_ALTERNATIVES = 3
    private const val CURVY_MAX_DETOUR_RATIO = 1.25
    private const val MAX_TOURING_DETOUR_RATIO = 1.60
    private const val RESAMPLE_SPACING_METERS = 40.0
    private const val MIN_TURN_ANGLE_DEGREES = 7.0
    private const val MAX_AVOID_LOCATIONS = 12

    /**
     * Keeps fast profiles in provider order, but makes curvy profiles choose
     * the bendiest candidate that is still within their detour budget.
     */
    fun orderForStyle(
        candidates: List<RouteAlternative>,
        style: RouteStyle,
        limit: Int,
    ): List<RouteAlternative> {
        require(candidates.isNotEmpty()) { "At least one route candidate is required" }
        require(limit in 1..MAX_ALTERNATIVES) {
            "Route alternative limit must be between one and $MAX_ALTERNATIVES"
        }
        if (style != RouteStyle.CURVY && style != RouteStyle.MAX_CURVY_TOURING) {
            return candidates.take(limit)
        }

        val shortestDistance = candidates.minOf { it.distanceMeters.coerceAtLeast(1.0) }
        val detourRatio = if (style == RouteStyle.CURVY) {
            CURVY_MAX_DETOUR_RATIO
        } else {
            MAX_TOURING_DETOUR_RATIO
        }
        val eligible = candidates.filter {
            it.distanceMeters <= shortestDistance * detourRatio
        }
        val pool = if (eligible.isNotEmpty()) eligible else listOf(candidates.minBy { it.distanceMeters })
        return pool
            .sortedWith(
                compareByDescending<RouteAlternative> { curvinessScore(it) }
                    .thenBy { it.distanceMeters },
            )
            .take(limit)
    }

    /**
     * Returns interior anchors for the next Valhalla request. The first
     * alternate avoids more of the primary corridor; later alternates avoid a
     * smaller sample from each already accepted corridor so the graph can
     * still find a route instead of being over-constrained.
     */
    fun avoidLocationsFor(
        acceptedRoutes: List<RouteAlternative>,
        nextAlternativeIndex: Int,
    ): List<GeoCoordinate> {
        require(nextAlternativeIndex in 1..MAX_ALTERNATIVES - 1) {
            "Next alternative index must be between one and ${MAX_ALTERNATIVES - 1}"
        }
        val sampleCount = if (nextAlternativeIndex == 1) 8 else 4
        return acceptedRoutes
            .take(nextAlternativeIndex)
            .flatMap { sampleInterior(it.geometry, sampleCount) }
            .take(MAX_AVOID_LOCATIONS)
    }

    /**
     * Heading-change degrees per kilometre after resampling the provider
     * polyline. Resampling prevents dense polyline vertices from fabricating
     * curviness while retaining real bends and switchbacks.
     */
    fun curvinessScore(route: RouteAlternative): Double {
        val points = resample(route.geometry)
        if (points.size < 3) return 0.0
        var totalTurnDegrees = 0.0
        for (index in 1 until points.lastIndex) {
            val incoming = bearing(points[index - 1], points[index])
            val outgoing = bearing(points[index], points[index + 1])
            val turn = abs(normalizeAngle(outgoing - incoming))
            if (turn >= MIN_TURN_ANGLE_DEGREES) totalTurnDegrees += turn
        }
        return totalTurnDegrees / (route.distanceMeters.coerceAtLeast(1.0) / 1_000.0)
    }

    private fun sampleInterior(geometry: List<GeoCoordinate>, count: Int): List<GeoCoordinate> {
        if (geometry.size < 3) return emptyList()
        val available = geometry.size - 2
        val sampleSize = min(count, available)
        return (1..sampleSize).map { sampleIndex ->
            val offset = ((available * sampleIndex.toDouble()) / (sampleSize + 1)).toInt().coerceIn(1, available)
            geometry[offset]
        }.distinct()
    }

    private fun resample(geometry: List<GeoCoordinate>): List<GeoCoordinate> {
        if (geometry.size < 2) return geometry
        val cumulative = DoubleArray(geometry.size)
        for (index in 1 until geometry.size) {
            cumulative[index] = cumulative[index - 1] + distanceMeters(geometry[index - 1], geometry[index])
        }
        val total = cumulative.last()
        if (total <= RESAMPLE_SPACING_METERS) return geometry

        val targets = buildList {
            add(0.0)
            var target = RESAMPLE_SPACING_METERS
            while (target < total) {
                add(target)
                target += RESAMPLE_SPACING_METERS
            }
            add(total)
        }
        return targets.map { target -> interpolate(geometry, cumulative, target) }
    }

    private fun interpolate(
        geometry: List<GeoCoordinate>,
        cumulative: DoubleArray,
        target: Double,
    ): GeoCoordinate {
        val segment = (1 until cumulative.size).firstOrNull { cumulative[it] >= target }
            ?: cumulative.lastIndex
        val startDistance = cumulative[segment - 1]
        val segmentDistance = (cumulative[segment] - startDistance).coerceAtLeast(1e-9)
        val fraction = ((target - startDistance) / segmentDistance).coerceIn(0.0, 1.0)
        val start = geometry[segment - 1]
        val end = geometry[segment]
        return GeoCoordinate(
            latitude = start.latitude + (end.latitude - start.latitude) * fraction,
            longitude = start.longitude + (end.longitude - start.longitude) * fraction,
        )
    }

    private fun bearing(from: GeoCoordinate, to: GeoCoordinate): Double {
        val latitude1 = Math.toRadians(from.latitude)
        val latitude2 = Math.toRadians(to.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        return Math.toDegrees(
            atan2(
                sin(longitudeDelta) * cos(latitude2),
                cos(latitude1) * sin(latitude2) -
                    sin(latitude1) * cos(latitude2) * cos(longitudeDelta),
            ),
        )
    }

    private fun normalizeAngle(angle: Double): Double = (angle + 540.0) % 360.0 - 180.0

    private fun distanceMeters(from: GeoCoordinate, to: GeoCoordinate): Double {
        val latitudeDelta = Math.toRadians(to.latitude - from.latitude)
        val longitudeDelta = Math.toRadians(to.longitude - from.longitude)
        val latitude1 = Math.toRadians(from.latitude)
        val latitude2 = Math.toRadians(to.latitude)
        val a = sin(latitudeDelta / 2.0) * sin(latitudeDelta / 2.0) +
            cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2.0) * sin(longitudeDelta / 2.0)
        return 2.0 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1.0 - a))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}

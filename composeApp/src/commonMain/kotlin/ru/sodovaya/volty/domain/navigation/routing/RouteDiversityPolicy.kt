package ru.sodovaya.volty.domain.navigation.routing

import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.RouteAlternative

/**
 * Keeps the provider's first route as the primary choice and removes near-identical
 * alternatives before they reach the UI. The comparison is deliberately geometry based:
 * route style and speed preference must not turn the same road into fake diversity.
 */
object RouteDiversityPolicy {
    private const val MAX_ALTERNATIVES = 3
    // A route polyline can move a few metres between engine versions, but a
    // 20–30 m parallel road is already a meaningful branch to the rider.
    private const val GEOMETRY_TOLERANCE_METERS = 18.0
    private const val METERS_PER_DEGREE = 111_320.0

    fun select(
        candidates: List<RouteAlternative>,
        limit: Int,
    ): List<RouteAlternative> {
        require(candidates.isNotEmpty()) { "At least one route candidate is required" }
        require(limit in 1..MAX_ALTERNATIVES) {
            "Route alternative limit must be between 1 and $MAX_ALTERNATIVES"
        }

        val selected = ArrayList<RouteAlternative>(limit)
        for (candidate in candidates) {
            if (selected.none { it.isEquivalentTo(candidate) }) {
                selected += candidate
            }
            if (selected.size == limit) break
        }
        return selected
    }

    private fun RouteAlternative.isEquivalentTo(other: RouteAlternative): Boolean {
        // Distance and duration are route metadata, not route identity. They can
        // differ after costing/style changes even when the provider returned the
        // same road sequence, so they must not create a fake alternative.
        return geometriesEquivalent(geometry, other.geometry)
    }

    private fun geometriesEquivalent(
        first: List<GeoCoordinate>,
        second: List<GeoCoordinate>,
    ): Boolean = first.all { point ->
        second.any { it.isWithinToleranceOf(point) }
    } && second.all { point ->
        first.any { it.isWithinToleranceOf(point) }
    }

    private fun GeoCoordinate.isWithinToleranceOf(other: GeoCoordinate): Boolean {
        val meanLatitudeRadians = Math.toRadians((latitude + other.latitude) / 2.0)
        val latitudeDeltaMeters = (latitude - other.latitude) * METERS_PER_DEGREE
        val longitudeDeltaMeters = (longitude - other.longitude) * METERS_PER_DEGREE *
            kotlin.math.cos(meanLatitudeRadians)
        return latitudeDeltaMeters * latitudeDeltaMeters + longitudeDeltaMeters * longitudeDeltaMeters <=
            GEOMETRY_TOLERANCE_METERS * GEOMETRY_TOLERANCE_METERS
    }
}

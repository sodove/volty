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
    // Roughly 35–40 m on the latitude axis. A 70–80 m corridor tolerance was
    // collapsing genuinely parallel urban roads into one route.
    private const val GEOMETRY_TOLERANCE_DEGREES = 0.00035

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
        val latitudeDelta = latitude - other.latitude
        val longitudeDelta = longitude - other.longitude
        return latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta <=
            GEOMETRY_TOLERANCE_DEGREES * GEOMETRY_TOLERANCE_DEGREES
    }
}

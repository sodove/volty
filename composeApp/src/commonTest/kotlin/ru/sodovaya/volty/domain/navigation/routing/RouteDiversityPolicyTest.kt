package ru.sodovaya.volty.domain.navigation.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver

class RouteDiversityPolicyTest {
    @Test
    fun limit_one_keeps_the_primary_route_without_extra_comparison_work() {
        val primary = route("primary", listOf(point(56.80, 60.50), point(56.81, 60.51)))
        val scenic = route("scenic", listOf(point(56.80, 60.50), point(56.82, 60.53)))

        assertEquals(
            listOf(primary),
            RouteDiversityPolicy.select(listOf(primary, scenic), limit = 1),
        )
    }

    @Test
    fun equivalent_geometry_is_removed_but_a_distinct_route_is_retained() {
        val primary = route("primary", listOf(point(56.80, 60.50), point(56.81, 60.51)))
        val duplicate = route("duplicate", listOf(point(56.80, 60.50), point(56.81, 60.51)))
        val distinct = route(
            "distinct",
            listOf(point(56.80, 60.50), point(56.815, 60.525), point(56.81, 60.51)),
        )

        assertEquals(
            listOf("primary", "distinct"),
            RouteDiversityPolicy.select(listOf(primary, duplicate, distinct), limit = 3)
                .map { it.id },
        )
    }

    @Test
    fun same_geometry_with_different_metrics_is_not_fake_diversity() {
        val primary = route("primary", listOf(point(56.80, 60.50), point(56.81, 60.51)))
        val sameRoadWithDifferentMetrics = primary.copy(
            id = "same-road-different-metrics",
            distanceMeters = 4_500.0,
            durationSeconds = 900L,
        )

        assertEquals(
            listOf("primary"),
            RouteDiversityPolicy.select(
                listOf(primary, sameRoadWithDifferentMetrics),
                limit = 3,
            ).map { it.id },
        )
    }

    @Test
    fun nearby_parallel_corridors_are_not_collapsed_into_the_primary_route() {
        val primary = route(
            "primary",
            listOf(point(56.80, 60.50), point(56.805, 60.505), point(56.81, 60.51)),
        )
        val parallel = route(
            "parallel",
            listOf(point(56.80, 60.50), point(56.80545, 60.505), point(56.81, 60.51)),
        )

        assertEquals(
            listOf("primary", "parallel"),
            RouteDiversityPolicy.select(listOf(primary, parallel), limit = 3).map { it.id },
        )
    }

    @Test
    fun selection_is_stable_and_never_returns_more_than_three_routes() {
        val routes = (1..5).map { index ->
            route(
                "route-$index",
                listOf(point(56.80, 60.50), point(56.80 + index * 0.01, 60.50 + index * 0.01)),
            )
        }

        assertEquals(
            listOf("route-1", "route-2", "route-3"),
            RouteDiversityPolicy.select(routes, limit = 3).map { it.id },
        )
    }

    private fun route(id: String, geometry: List<GeoCoordinate>) = RouteAlternative(
        id = id,
        distanceMeters = 1000.0,
        durationSeconds = 60L,
        geometry = geometry,
        maneuvers = listOf(
            RouteManeuver(
                id = "$id-arrive",
                kind = ManeuverKind.ARRIVE,
                instruction = "Прибытие",
                streetName = null,
                shapeIndex = geometry.lastIndex,
                distanceMeters = 0.0,
            ),
        ),
    )

    private fun point(latitude: Double, longitude: Double) = GeoCoordinate(latitude, longitude)
}

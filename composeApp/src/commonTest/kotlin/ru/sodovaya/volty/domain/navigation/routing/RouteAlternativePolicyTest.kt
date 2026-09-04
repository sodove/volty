package ru.sodovaya.volty.domain.navigation.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver

class RouteAlternativePolicyTest {
    @Test
    fun curvy_style_prefers_the_most_bendy_route_within_detour_budget() {
        val direct = route(
            "direct",
            distanceMeters = 10_000.0,
            geometry = listOf(point(56.80, 60.50), point(56.89, 60.50)),
        )
        val curvy = route(
            "curvy",
            distanceMeters = 11_500.0,
            geometry = listOf(
                point(56.80, 60.50),
                point(56.83, 60.54),
                point(56.86, 60.48),
                point(56.89, 60.50),
            ),
        )
        val tooLong = route(
            "too-long",
            distanceMeters = 14_000.0,
            geometry = listOf(
                point(56.80, 60.50),
                point(56.84, 60.56),
                point(56.87, 60.45),
                point(56.89, 60.50),
            ),
        )

        assertEquals(
            listOf("curvy", "direct"),
            RouteAlternativePolicy.orderForStyle(
                candidates = listOf(direct, curvy, tooLong),
                style = RouteStyle.CURVY,
                limit = 3,
            ).map { it.id },
        )
    }

    @Test
    fun fast_styles_keep_provider_order() {
        val first = route("first", 10_000.0, listOf(point(56.80, 60.50), point(56.89, 60.50)))
        val second = route("second", 11_000.0, listOf(point(56.80, 60.50), point(56.82, 60.54), point(56.89, 60.50)))

        assertEquals(
            listOf("first", "second"),
            RouteAlternativePolicy.orderForStyle(
                listOf(first, second),
                RouteStyle.FAST_WITHOUT_HIGHWAYS,
                limit = 3,
            ).map { it.id },
        )
    }

    @Test
    fun avoid_locations_sample_only_interior_route_anchors() {
        val route = route(
            "primary",
            10_000.0,
            (0..20).map { index -> point(56.80 + index * 0.004, 60.50) },
        )

        val avoids = RouteAlternativePolicy.avoidLocationsFor(listOf(route), nextAlternativeIndex = 1)

        assertTrue(avoids.isNotEmpty())
        assertTrue(avoids.size <= 8)
        assertTrue(avoids.none { it == route.geometry.first() || it == route.geometry.last() })
    }

    @Test
    fun curviness_score_is_higher_for_a_route_with_real_heading_changes() {
        val straight = route("straight", 10_000.0, listOf(point(56.80, 60.50), point(56.89, 60.50)))
        val bendy = route(
            "bendy",
            11_000.0,
            listOf(point(56.80, 60.50), point(56.84, 60.55), point(56.89, 60.50)),
        )

        assertTrue(RouteAlternativePolicy.curvinessScore(bendy) > RouteAlternativePolicy.curvinessScore(straight))
    }

    private fun route(id: String, distanceMeters: Double, geometry: List<GeoCoordinate>) = RouteAlternative(
        id = id,
        distanceMeters = distanceMeters,
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

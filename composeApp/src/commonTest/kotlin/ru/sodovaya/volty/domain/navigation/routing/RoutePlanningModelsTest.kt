package ru.sodovaya.volty.domain.navigation.routing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

class RoutePlanningModelsTest {
    @Test
    fun route_request_carries_style_and_generic_ride_preferences() {
        val request = RouteRequest(
            origin = GeoCoordinate(56.8389, 60.6057),
            destination = place("finish"),
            languageTag = "ru-RU",
            style = RouteStyle.CURVY,
            preferences = RoutingPreferences(declaredTopSpeedKph = 90),
        )

        assertEquals(RouteStyle.CURVY, request.style)
        assertEquals(90, request.preferences.declaredTopSpeedKph)
    }

    @Test
    fun supported_styles_are_transport_agnostic() {
        assertEquals(
            listOf(
                RouteStyle.FAST_WITH_HIGHWAYS,
                RouteStyle.FAST_WITHOUT_HIGHWAYS,
                RouteStyle.CURVY,
                RouteStyle.MAX_CURVY_TOURING,
            ),
            RouteStyle.entries,
        )
    }

    @Test
    fun declared_speed_is_limited_to_product_range() {
        assertFailsWith<IllegalArgumentException> { RoutingPreferences(declaredTopSpeedKph = 19) }
        assertFailsWith<IllegalArgumentException> { RoutingPreferences(declaredTopSpeedKph = 131) }
    }

    @Test
    fun first_route_update_contains_a_single_route_plan() {
        val plan = RoutePlan(
            destination = place("finish"),
            alternatives = listOf(route("primary")),
        )

        val update = RoutePlanningUpdate.PrimaryRouteReady(
            plan = plan,
            source = RouteSource.OFFLINE,
        )

        assertEquals("primary", update.plan.alternatives.single().id)
        assertEquals(RouteSource.OFFLINE, update.source)
    }

    @Test
    fun alternative_update_carries_the_route_for_progressive_rendering() {
        val alternative = route("scenic")

        val update = RoutePlanningUpdate.AlternativeAdded(
            route = alternative,
            source = RouteSource.OFFLINE,
        )

        assertEquals("scenic", update.route.id)
        assertEquals(RouteSource.OFFLINE, update.source)
    }

    private fun place(id: String) = PlaceCandidate(
        id = id,
        title = id,
        subtitle = null,
        coordinate = GeoCoordinate(56.84, 60.61),
    )

    private fun route(id: String) = RouteAlternative(
        id = id,
        distanceMeters = 1000.0,
        durationSeconds = 60,
        geometry = listOf(
            GeoCoordinate(56.83, 60.60),
            GeoCoordinate(56.84, 60.61),
        ),
        maneuvers = listOf(
            RouteManeuver(
                id = "arrive",
                kind = ManeuverKind.ARRIVE,
                instruction = "Прибытие",
                streetName = null,
                shapeIndex = 1,
                distanceMeters = 0.0,
            ),
        ),
    )
}

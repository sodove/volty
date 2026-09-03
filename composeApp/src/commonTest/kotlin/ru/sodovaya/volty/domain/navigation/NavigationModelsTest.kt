package ru.sodovaya.volty.domain.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NavigationModelsTest {
    @Test
    fun coordinate_rejects_non_finite_or_out_of_range_values() {
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(Double.NaN, 60.6) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(56.8, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(90.001, 60.6) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(-90.001, 60.6) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(56.8, -180.001) }
        assertFailsWith<IllegalArgumentException> { GeoCoordinate(56.8, 180.001) }

        assertEquals(GeoCoordinate(-90.0, -180.0), GeoCoordinate(-90.0, -180.0))
        assertEquals(GeoCoordinate(90.0, 180.0), GeoCoordinate(90.0, 180.0))
    }

    @Test
    fun route_rejects_short_geometry_and_invalid_shape_indices() {
        val destination = coordinate(56.801, 60.601)
        val geometry = listOf(coordinate(56.8, 60.6), destination)

        assertFailsWith<IllegalArgumentException> {
            RouteManeuver(
                id = "negative-shape-index",
                kind = ManeuverKind.ARRIVE,
                instruction = "Arrive",
                streetName = null,
                shapeIndex = -1,
                distanceMeters = 0.0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteManeuver(
                id = "non-finite-maneuver-distance",
                kind = ManeuverKind.ARRIVE,
                instruction = "Arrive",
                streetName = null,
                shapeIndex = 1,
                distanceMeters = Double.NaN,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteManeuver(
                id = "negative-maneuver-distance",
                kind = ManeuverKind.ARRIVE,
                instruction = "Arrive",
                streetName = null,
                shapeIndex = 1,
                distanceMeters = -1.0,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "non-finite-route-distance",
                distanceMeters = Double.NaN,
                durationSeconds = 30L,
                geometry = geometry,
                maneuvers = listOf(arrival(shapeIndex = 1)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "negative-route-distance",
                distanceMeters = -1.0,
                durationSeconds = 30L,
                geometry = geometry,
                maneuvers = listOf(arrival(shapeIndex = 1)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "zero-route-duration",
                distanceMeters = 100.0,
                durationSeconds = 0L,
                geometry = geometry,
                maneuvers = listOf(arrival(shapeIndex = 1)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "short",
                distanceMeters = 100.0,
                durationSeconds = 30L,
                geometry = listOf(destination),
                maneuvers = listOf(arrival(shapeIndex = 0)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "outside",
                distanceMeters = 100.0,
                durationSeconds = 30L,
                geometry = geometry,
                maneuvers = listOf(arrival(shapeIndex = 2)),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "empty-maneuvers",
                distanceMeters = 100.0,
                durationSeconds = 30L,
                geometry = geometry,
                maneuvers = emptyList(),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            RouteAlternative(
                id = "does-not-arrive",
                distanceMeters = 100.0,
                durationSeconds = 30L,
                geometry = geometry,
                maneuvers = listOf(
                    RouteManeuver(
                        id = "straight",
                        kind = ManeuverKind.STRAIGHT,
                        instruction = "Continue",
                        streetName = null,
                        shapeIndex = 0,
                        distanceMeters = 100.0,
                    )
                ),
            )
        }
    }

    @Test
    fun route_plan_accepts_one_route_and_caps_three() {
        val destination = PlaceCandidate(
            id = "place-1",
            title = "Destination",
            subtitle = null,
            coordinate = coordinate(56.801, 60.601),
        )
        val one = alternative("one", destination.coordinate)

        assertEquals(1, RoutePlan(destination, listOf(one)).alternatives.size)

        val three = listOf(
            one,
            alternative("two", destination.coordinate),
            alternative("three", destination.coordinate),
        )
        assertEquals(3, RoutePlan(destination, three).alternatives.size)

        assertFailsWith<IllegalArgumentException> {
            RoutePlan(destination, emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            RoutePlan(
                destination,
                three + alternative("four", destination.coordinate),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RoutePlan(destination, listOf(one, one.copy(id = "one")))
        }
    }

    @Test
    fun route_request_has_no_vehicle_choice() {
        val destination = PlaceCandidate(
            id = "place-1",
            title = "Destination",
            subtitle = null,
            coordinate = coordinate(56.801, 60.601),
        )

        val request = RouteRequest(
            origin = coordinate(56.8, 60.6),
            destination = destination,
            languageTag = "ru-RU",
        )

        assertEquals(destination, request.destination)
    }

    private fun coordinate(latitude: Double, longitude: Double): GeoCoordinate =
        GeoCoordinate(latitude, longitude)

    private fun arrival(shapeIndex: Int): RouteManeuver = RouteManeuver(
        id = "arrive",
        kind = ManeuverKind.ARRIVE,
        instruction = "Arrive",
        streetName = null,
        shapeIndex = shapeIndex,
        distanceMeters = 0.0,
    )

    private fun alternative(id: String, destination: GeoCoordinate): RouteAlternative = RouteAlternative(
        id = id,
        distanceMeters = 100.0,
        durationSeconds = 30L,
        geometry = listOf(coordinate(56.8, 60.6), destination),
        maneuvers = listOf(arrival(shapeIndex = 1)),
    )
}

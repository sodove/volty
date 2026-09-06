package ru.sodovaya.volty.data.navigation.offline

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle
import ru.sodovaya.volty.domain.navigation.routing.RoutingPreferences

class ValhallaRouteCodecTest {
    @Test
    fun request_uses_generic_costing_and_requests_the_remaining_alternatives() {
        val request = request(style = RouteStyle.FAST_WITHOUT_HIGHWAYS)

        val json = ValhallaRouteCodec.encodeRequest(request)

        assertContains(json, "\"costing\":\"auto\"")
        assertContains(json, "\"alternates\":2")
        assertContains(json, "\"top_speed\":90")
        assertContains(json, "\"use_highways\":0.0")
        assertContains(json, "\"language\":\"ru-RU\"")
        kotlin.test.assertFalse(json.contains("vehicle"))
        kotlin.test.assertFalse(json.contains("profile"))
    }

    @Test
    fun request_keeps_style_as_highway_bias_without_faking_curvature_controls() {
        val fast = ValhallaRouteCodec.encodeRequest(request(style = RouteStyle.FAST_WITH_HIGHWAYS))
        val curvy = ValhallaRouteCodec.encodeRequest(request(style = RouteStyle.CURVY))
        val touring = ValhallaRouteCodec.encodeRequest(request(style = RouteStyle.MAX_CURVY_TOURING))
        val avoidUnpaved = ValhallaRouteCodec.encodeRequest(
            request(preferences = RoutingPreferences(declaredTopSpeedKph = 90, avoidUnpaved = true)),
        )

        assertContains(fast, "\"use_highways\":1.0")
        assertContains(curvy, "\"use_highways\":0.15")
        assertContains(touring, "\"use_highways\":0.0")
        listOf(fast, curvy, touring).forEach { json ->
            assertContains(json, "\"use_distance\":0.0")
            assertContains(json, "\"shortest\":false")
            assertContains(json, "\"ignore_restrictions\":false")
            assertContains(json, "\"ignore_access\":false")
            assertContains(json, "\"ignore_oneways\":false")
            assertContains(json, "\"ignore_closures\":false")
            kotlin.test.assertFalse(json.contains("maneuver_penalty"))
        }
        assertContains(avoidUnpaved, "\"exclude_unpaved\":true")
        kotlin.test.assertFalse(avoidUnpaved.contains("use_unpaved"))
    }

    @Test
    fun request_keeps_tolls_and_ferries_neutral_until_the_rider_asks_to_avoid_them() {
        val neutral = ValhallaRouteCodec.encodeRequest(request())
        val avoid = ValhallaRouteCodec.encodeRequest(
            request(
                preferences = RoutingPreferences(
                    declaredTopSpeedKph = 90,
                    avoidTolls = true,
                    avoidFerries = true,
                ),
            ),
        )

        assertContains(neutral, "\"use_tolls\":0.5")
        assertContains(neutral, "\"use_ferry\":0.5")
        assertContains(avoid, "\"use_tolls\":0.0")
        assertContains(avoid, "\"use_ferry\":0.0")
    }

    @Test
    fun candidate_request_can_avoid_interior_roads_without_requesting_more_alternates() {
        val json = ValhallaRouteCodec.encodeRequest(
            request = request(),
            alternativesLimit = 1,
            avoidLocations = listOf(GeoCoordinate(56.82, 60.615), GeoCoordinate(56.83, 60.62)),
        )

        assertContains(json, "\"alternates\":0")
        assertContains(json, "\"avoid_locations\"")
        assertContains(json, "\"lat\":56.82")
        assertContains(json, "\"lon\":60.62")
    }

    @Test
    fun production_costings_have_explicit_options_and_never_become_transport_selector_fields() {
        val motorcycle = ValhallaRouteCodec.encodeRequest(
            request = request(style = RouteStyle.MAX_CURVY_TOURING),
            alternativesLimit = 1,
            costing = ValhallaCosting.MOTORCYCLE,
        )
        val bicycle = ValhallaRouteCodec.encodeRequest(
            request = request(style = RouteStyle.CURVY),
            alternativesLimit = 1,
            costing = ValhallaCosting.BICYCLE,
        )
        val pedestrian = ValhallaRouteCodec.encodeRequest(
            request = request(preferences = RoutingPreferences(declaredTopSpeedKph = 20)),
            alternativesLimit = 1,
            costing = ValhallaCosting.PEDESTRIAN,
        )

        assertContains(motorcycle, "\"costing\":\"motorcycle\"")
        assertContains(motorcycle, "\"use_highways\":0.0")
        assertContains(motorcycle, "\"use_trails\":0.6")
        assertContains(motorcycle, "\"use_tracks\":0.5")
        assertContains(bicycle, "\"use_roads\":0.1")
        assertContains(pedestrian, "\"walking_speed\":20")
        listOf(motorcycle, bicycle, pedestrian).forEach { json ->
            kotlin.test.assertFalse(json.contains("\"vehicle\""))
            kotlin.test.assertFalse(json.contains("\"profile\""))
        }
    }

    @Test
    fun low_speed_hardening_removes_highway_bias_from_generic_and_specialized_costings() {
        val request = request(
            style = RouteStyle.FAST_WITH_HIGHWAYS,
            preferences = RoutingPreferences(declaredTopSpeedKph = 20),
        )

        val generic = ValhallaRouteCodec.encodeRequest(request, alternativesLimit = 1)
        val motorcycle = ValhallaRouteCodec.encodeRequest(
            request = request,
            alternativesLimit = 1,
            costing = ValhallaCosting.MOTORCYCLE,
        )
        val bicycle = ValhallaRouteCodec.encodeRequest(
            request = request,
            alternativesLimit = 1,
            costing = ValhallaCosting.BICYCLE,
        )

        assertContains(generic, "\"use_highways\":0.0")
        assertContains(motorcycle, "\"use_highways\":0.0")
        assertContains(motorcycle, "\"use_trails\":0.0")
        assertContains(motorcycle, "\"use_tracks\":0.0")
        assertContains(bicycle, "\"use_roads\":0.0")
    }

    @Test
    fun highway_response_is_rejected_for_safe_styles_but_allowed_for_fast_highways() {
        val body = """
            {
              "trip": {
                "summary": {"length": 1.2, "time": 90, "has_highway": true},
                "legs": [{
                  "shape": "_sflkB_|irrB_pR_pR",
                  "maneuvers": [
                    {"type": 1, "instruction": "start", "begin_shape_index": 0, "length": 0.0},
                    {"type": 4, "instruction": "destination", "begin_shape_index": 1, "length": 1.2}
                  ]
                }]
              }
            }
        """.trimIndent()

        val lowSpeed = ValhallaRouteCodec.decodeRoutePlan(
            body,
            request(
                style = RouteStyle.CURVY,
                preferences = RoutingPreferences(declaredTopSpeedKph = 20),
            ),
        )
        val curvy = ValhallaRouteCodec.decodeRoutePlan(
            body,
            request(
                style = RouteStyle.CURVY,
                preferences = RoutingPreferences(declaredTopSpeedKph = 90),
            ),
        )
        val fastHighways = ValhallaRouteCodec.decodeRoutePlan(
            body,
            request(
                style = RouteStyle.FAST_WITH_HIGHWAYS,
                preferences = RoutingPreferences(declaredTopSpeedKph = 90),
            ),
        )
        val missingHighwayVerdict = ValhallaRouteCodec.decodeRoutePlan(
            body.replace(", \"has_highway\": true", ""),
            request(
                style = RouteStyle.CURVY,
                preferences = RoutingPreferences(declaredTopSpeedKph = 90),
            ),
        )

        assertEquals(NavigationFailure.NoRoute, assertIs<NavigationResult.Failure>(lowSpeed).reason)
        assertEquals(NavigationFailure.NoRoute, assertIs<NavigationResult.Failure>(curvy).reason)
        assertEquals(
            NavigationFailure.NoRoute,
            assertIs<NavigationResult.Failure>(missingHighwayVerdict).reason,
        )
        assertIs<NavigationResult.Success<RoutePlan>>(fastHighways)
    }

    @Test
    fun curvy_motorcycle_profile_scales_adventure_bias_with_speed() {
        val urban = ValhallaRouteCodec.encodeRequest(
            request = request(
                style = RouteStyle.CURVY,
                preferences = RoutingPreferences(declaredTopSpeedKph = 50),
            ),
            alternativesLimit = 1,
            costing = ValhallaCosting.MOTORCYCLE,
        )
        val highSpeed = ValhallaRouteCodec.encodeRequest(
            request = request(
                style = RouteStyle.CURVY,
                preferences = RoutingPreferences(declaredTopSpeedKph = 90),
            ),
            alternativesLimit = 1,
            costing = ValhallaCosting.MOTORCYCLE,
        )

        assertContains(urban, "\"use_highways\":0.15")
        assertContains(urban, "\"use_trails\":0.55")
        assertContains(urban, "\"use_tracks\":0.4")
        assertContains(highSpeed, "\"use_highways\":0.15")
        assertContains(highSpeed, "\"use_trails\":0.35")
        assertContains(highSpeed, "\"use_tracks\":0.25")
    }

    @Test
    fun response_decodes_primary_and_top_level_alternates_with_polyline6_and_maneuvers() {
        val body = """
            {
              "trip": {
                "summary": {"length": 1.2, "time": 90},
                "legs": [{
                  "summary": {"length": 1.2, "time": 90},
                  "shape": "_sflkB_|irrB_pR_pR",
                  "maneuvers": [
                    {"type": 1, "instruction": "Начните движение", "begin_shape_index": 0, "length": 0.0},
                    {"type": 4, "instruction": "Вы прибыли", "begin_shape_index": 1, "length": 1.2}
                  ]
                }]
              },
              "alternates": [{
                "trip": {
                  "summary": {"length": 1.4, "time": 100},
                  "legs": [{
                    "summary": {"length": 1.4, "time": 100},
                    "shape": "_sflkB_|irrBonT_mV",
                    "maneuvers": [
                      {"type": 1, "instruction": "Начните движение", "begin_shape_index": 0, "length": 0.0},
                      {"type": 4, "instruction": "Вы прибыли", "begin_shape_index": 1, "length": 1.4}
                    ]
                  }]
                }
              }]
            }
        """.trimIndent()

        val result = ValhallaRouteCodec.decodeRoutePlan(body, request())

        val plan = assertIs<NavigationResult.Success<ru.sodovaya.volty.domain.navigation.RoutePlan>>(result).value
        assertEquals(2, plan.alternatives.size)
        assertEquals(1_200.0, plan.alternatives.first().distanceMeters)
        assertEquals(90L, plan.alternatives.first().durationSeconds)
        assertEquals(56.84, plan.alternatives.first().geometry.first().latitude, 0.000001)
        assertEquals(60.61, plan.alternatives.first().geometry.first().longitude, 0.000001)
        assertEquals(ManeuverKind.DEPART, plan.alternatives.first().maneuvers.first().kind)
        assertEquals(ManeuverKind.ARRIVE, plan.alternatives.first().maneuvers.last().kind)
    }

    @Test
    fun no_route_and_malformed_responses_remain_typed_failures() {
        val noRoute = ValhallaRouteCodec.decodeRoutePlan(
            "{\"error_code\":171,\"error\":\"No route could be found\"}",
            request(),
        )
        val malformed = ValhallaRouteCodec.decodeRoutePlan("{\"trip\":{}}", request())

        assertEquals(NavigationFailure.NoRoute, assertIs<NavigationResult.Failure>(noRoute).reason)
        assertEquals(NavigationFailure.MalformedResponse, assertIs<NavigationResult.Failure>(malformed).reason)
    }

    @Test
    fun route_evidence_preserves_maneuver_modes_and_safety_flags() {
        val body = """
            {
              "trip": {
                "legs": [{
                  "maneuvers": [
                    {"travel_mode":"drive","travel_type":"car","rough":true,"toll":false,"ferry":false},
                    {"travel_mode":"pedestrian","travel_type":"foot","rough":false,"toll":true,"ferry":true}
                  ]
                }]
              }
            }
        """.trimIndent()

        val evidence = assertIs<NavigationResult.Success<ValhallaRouteEvidence>>(
            ValhallaRouteCodec.decodeRouteEvidence(body),
        ).value

        assertEquals(setOf("drive", "pedestrian"), evidence.travelModes)
        assertEquals(setOf("car", "foot"), evidence.travelTypes)
        assertTrue(evidence.hasRoughSegments)
        assertTrue(evidence.hasTollSegments)
        assertTrue(evidence.hasFerrySegments)
        assertEquals(2, evidence.maneuverCount)
        assertFalse(evidence.hasUnknownTravelModes)
    }

    @Test
    fun maneuver_types_follow_the_pinned_valhalla_enum() {
        val body = """
            {
              "trip": {
                "summary": {"length": 1.0, "time": 60},
                "legs": [{
                  "shape": "_sflkB_|irrB_pR_pR",
                  "maneuvers": [
                    {"type": 1, "instruction": "start", "begin_shape_index": 0},
                    {"type": 2, "instruction": "start right", "begin_shape_index": 0},
                    {"type": 3, "instruction": "start left", "begin_shape_index": 0},
                    {"type": 9, "instruction": "slight right", "begin_shape_index": 0},
                    {"type": 10, "instruction": "right", "begin_shape_index": 0},
                    {"type": 11, "instruction": "sharp right", "begin_shape_index": 0},
                    {"type": 12, "instruction": "u-turn right", "begin_shape_index": 0},
                    {"type": 13, "instruction": "u-turn left", "begin_shape_index": 0},
                    {"type": 14, "instruction": "sharp left", "begin_shape_index": 0},
                    {"type": 15, "instruction": "left", "begin_shape_index": 0},
                    {"type": 16, "instruction": "slight left", "begin_shape_index": 0},
                    {"type": 4, "instruction": "destination", "begin_shape_index": 1}
                  ]
                }]
              }
            }
        """.trimIndent()

        val plan = assertIs<NavigationResult.Success<ru.sodovaya.volty.domain.navigation.RoutePlan>>(
            ValhallaRouteCodec.decodeRoutePlan(body, request()),
        ).value

        assertEquals(
            listOf(
                ManeuverKind.DEPART,
                ManeuverKind.DEPART,
                ManeuverKind.DEPART,
                ManeuverKind.SLIGHT_RIGHT,
                ManeuverKind.RIGHT,
                ManeuverKind.SHARP_RIGHT,
                ManeuverKind.U_TURN,
                ManeuverKind.U_TURN,
                ManeuverKind.SHARP_LEFT,
                ManeuverKind.LEFT,
                ManeuverKind.SLIGHT_LEFT,
                ManeuverKind.ARRIVE,
            ),
            plan.alternatives.single().maneuvers.map { it.kind },
        )
    }

    private fun request(
        style: RouteStyle = RouteStyle.FAST_WITH_HIGHWAYS,
        preferences: RoutingPreferences = RoutingPreferences(declaredTopSpeedKph = 90),
    ) = RouteRequest(
        origin = GeoCoordinate(56.84, 60.61),
        destination = PlaceCandidate("finish", "Плотинка", "Екатеринбург", GeoCoordinate(56.85, 60.62)),
        languageTag = "ru-RU",
        style = style,
        preferences = preferences,
        alternativesLimit = 3,
    )
}

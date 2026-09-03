package ru.sodovaya.volty.data.navigation.offline

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
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
                    {"type": 2, "instruction": "Вы прибыли", "begin_shape_index": 1, "length": 1.2}
                  ]
                }]
              },
              "alternates": [{
                "trip": {
                  "summary": {"length": 1.4, "time": 100},
                  "legs": [{
                    "summary": {"length": 1.4, "time": 100},
                    "shape": "_sflkB_|irrB_pR_pR",
                    "maneuvers": [
                      {"type": 1, "instruction": "Начните движение", "begin_shape_index": 0, "length": 0.0},
                      {"type": 2, "instruction": "Вы прибыли", "begin_shape_index": 1, "length": 1.4}
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

    private fun request(style: RouteStyle = RouteStyle.FAST_WITH_HIGHWAYS) = RouteRequest(
        origin = GeoCoordinate(56.84, 60.61),
        destination = PlaceCandidate("finish", "Плотинка", "Екатеринбург", GeoCoordinate(56.85, 60.62)),
        languageTag = "ru-RU",
        style = style,
        preferences = RoutingPreferences(declaredTopSpeedKph = 90),
        alternativesLimit = 3,
    )
}

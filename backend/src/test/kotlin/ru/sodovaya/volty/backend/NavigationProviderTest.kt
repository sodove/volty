package ru.sodovaya.volty.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NavigationProviderTest {
    @Test
    fun geocode_escapes_query_applies_bias_and_caps_results_at_eight() = kotlinx.coroutines.test.runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            requestData = request
            respond(
                content = """{"hits": []}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val provider = GraphHopperNavigationProvider(graphhopperConfig(), client)

        val result = provider.search(
            ProviderSearchRequest(
                query = "Queen & Main / 1",
                near = GeoCoordinateDto(56.8, 60.6),
                languageTag = "ru-RU",
                limit = 99,
            ),
        )

        assertIs<ProviderResult.Success<List<NavigationPlaceDto>>>(result)
        val request = requireNotNull(requestData)
        assertEquals("Queen & Main / 1", request.url.parameters["q"])
        assertEquals("ru-RU", request.url.parameters["locale"])
        assertEquals("56.8,60.6", request.url.parameters["point"])
        assertEquals("8", request.url.parameters["limit"])
        assertEquals("test-key", request.url.parameters["key"])
        assertEquals("Volty/0.1 navigation", request.headers[HttpHeaders.UserAgent])
    }

    @Test
    fun route_normalizes_geojson_coordinates_instructions_and_time() = kotlinx.coroutines.test.runTest {
        var requestData: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            requestData = request
            respond(
                content = routeFixture(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val provider = GraphHopperNavigationProvider(graphhopperConfig(), client)

        val result = provider.routes(
            ProviderRouteRequest(
                origin = GeoCoordinateDto(56.8, 60.6),
                destination = GeoCoordinateDto(56.81, 60.61),
                providerProfileId = "bike-custom",
                languageTag = "ru-RU",
                alternativesLimit = 3,
            ),
        )

        val response = assertIs<ProviderResult.Success<NavigationRouteResponse>>(result).value
        val request = requireNotNull(requestData)
        assertEquals("56.8,60.6", request.url.parameters.getAll("point")?.first())
        assertEquals("56.81,60.61", request.url.parameters.getAll("point")?.last())
        assertEquals("bike-custom", request.url.parameters["profile"])
        assertEquals("ru-RU", request.url.parameters["locale"])
        assertEquals("false", request.url.parameters["points_encoded"])
        assertEquals("alternative_route", request.url.parameters["algorithm"])
        assertEquals("3", request.url.parameters["alternative_route.max_paths"])
        assertEquals(2, response.routes.single().geometry.size)
        assertEquals(60.6, response.routes.single().geometry.first().longitude)
        assertEquals("ARRIVE", response.routes.single().maneuvers.last().kind)
        assertEquals(1, response.routes.single().maneuvers.last().shapeIndex)
        assertEquals(121L, response.routes.single().durationSeconds)
    }

    @Test
    fun malformed_coordinates_or_maneuver_indices_fail_closed() = kotlinx.coroutines.test.runTest {
        val client = HttpClient(MockEngine {
            respond(
                content = """{"paths":[{"distance":10,"time":1000,"points":{"type":"LineString","coordinates":[[60.6,56.8],[181,56.81]]},"instructions":[{"text":"arrive","sign":4,"distance":10,"interval":[0,1]}]}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        })
        val provider = GraphHopperNavigationProvider(graphhopperConfig(), client)

        assertEquals(
            ProviderResult.MalformedResponse,
            provider.routes(
                ProviderRouteRequest(
                    GeoCoordinateDto(56.8, 60.6),
                    GeoCoordinateDto(56.81, 60.61),
                    "bike-custom",
                    "ru-RU",
                    1,
                ),
            ),
        )
    }

    private fun graphhopperConfig() = AppConfig.forTests().copy(
        navigationProvider = "graphhopper",
        navigationEnabled = true,
        graphHopperApiKey = "test-key",
        navigationProfileIds = mapOf(
            "bicycle" to "bike-custom",
            "light_ev" to "small-electric",
            "motor_scooter" to "scooter-custom",
        ),
    )

    private fun routeFixture() = """
        {
          "paths": [{
            "distance": 1200.5,
            "time": 120500,
            "points": {"type":"LineString","coordinates":[[60.6,56.8],[60.61,56.81]]},
            "instructions": [
              {"text":"Поверните направо","street_name":"Main","sign":2,"distance":400.5,"interval":[0,0]},
              {"text":"Вы прибыли","street_name":"Main","sign":4,"distance":800.0,"interval":[1,1]}
            ]
          }]
        }
    """.trimIndent()
}

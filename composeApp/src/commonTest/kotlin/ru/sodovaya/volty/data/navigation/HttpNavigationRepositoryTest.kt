package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpNavigationRepositoryTest {
    @Test
    fun search_encodes_query_and_omits_or_sends_near_bias() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = HttpNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            respond("[]", headers = jsonHeaders())
        }), "https://example.test/v1")

        repository.search("Queen & Main / 1", near = null, languageTag = "ru-RU")
        repository.search("Main Street", near = GeoCoordinate(56.8, 60.6), languageTag = "ru-RU")

        assertEquals("Queen & Main / 1", requests[0].url.parameters["q"])
        assertEquals(null, requests[0].url.parameters["latitude"])
        assertEquals("56.8", requests[1].url.parameters["latitude"])
        assertEquals("60.6", requests[1].url.parameters["longitude"])
        assertEquals(HttpMethod.Get, requests[0].method)
        assertEquals(null, requests[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun routes_send_domain_shape_and_decode_one_two_and_three_routes() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = HttpNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            respond(routeJson(3), headers = jsonHeaders())
        }), "https://example.test/v1")
        val result = repository.routes(testRequest())

        val plan = assertIs<NavigationResult.Success<*>>(result).value
        assertEquals(3, (plan as ru.sodovaya.volty.domain.navigation.RoutePlan).alternatives.size)
        val body = (requests.single().body as TextContent).text
        assertTrue(!body.contains("\"profile\""))
        assertTrue(body.contains("\"routingProfile\":\"motorcycle\""))
        assertTrue(body.contains("\"alternativesLimit\":3"))
        assertTrue(body.contains("\"latitude\":56.8"))
        assertEquals(ContentType.Application.Json, requests.single().body.contentType)
    }

    @Test
    fun empty_routes_422_429_and_503_are_typed_failures() = runTest {
        val noRoute = HttpNavigationRepository(HttpClient(MockEngine {
            respond("{\"code\":\"navigation_no_route\",\"message\":\"none\"}", HttpStatusCode.UnprocessableEntity, jsonHeaders())
        }), "https://example.test/v1")
        assertEquals(NavigationFailure.NoRoute, assertFailure(noRoute.routes(testRequest())))

        val limited = HttpNavigationRepository(HttpClient(MockEngine {
            respond("{\"code\":\"navigation_rate_limited\"}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "17"))
        }), "https://example.test/v1")
        assertEquals(NavigationFailure.RateLimited(17), assertFailure(limited.search("Main", null, "ru-RU")))

        val unavailable = HttpNavigationRepository(HttpClient(MockEngine {
            respond("{\"code\":\"navigation_unavailable\"}", HttpStatusCode.ServiceUnavailable, jsonHeaders())
        }), "https://example.test/v1")
        assertEquals(NavigationFailure.ProviderUnavailable, assertFailure(unavailable.search("Main", null, "ru-RU")))

        val malformed = HttpNavigationRepository(HttpClient(MockEngine {
            respond("{\"schemaVersion\":1,\"destination\":{\"id\":\"p\",\"title\":\"P\",\"latitude\":56.8,\"longitude\":60.6},\"routes\":[]}", headers = jsonHeaders())
        }), "https://example.test/v1")
        assertEquals(NavigationFailure.MalformedResponse, assertFailure(malformed.routes(testRequest())))
    }

    @Test
    fun io_failure_is_offline_and_cancellation_is_not_swallowed() = runTest {
        val offline = HttpNavigationRepository(HttpClient(MockEngine {
            throw IllegalStateException("network down")
        }), "https://example.test/v1")
        assertEquals(NavigationFailure.Offline, assertFailure(offline.search("Main", null, "ru-RU")))

        val cancelled = HttpNavigationRepository(HttpClient(MockEngine {
            throw CancellationException("cancel")
        }), "https://example.test/v1")
        assertFailsWith<CancellationException> { cancelled.search("Main", null, "ru-RU") }
    }

    private fun testRequest() = RouteRequest(
        origin = GeoCoordinate(56.8, 60.6),
        destination = PlaceCandidate("place-1", "Main Street", "Yekaterinburg", GeoCoordinate(56.81, 60.61)),
        languageTag = "ru-RU",
        alternativesLimit = 3,
    )

    private fun assertFailure(result: NavigationResult<*>): NavigationFailure =
        (assertIs<NavigationResult.Failure>(result)).reason

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun routeJson(count: Int): String = """
        {
          "schemaVersion": 1,
          "destination": {"id":"place-1","title":"Main Street","subtitle":"Yekaterinburg","latitude":56.81,"longitude":60.61},
          "routes":[${(1..count).joinToString(",") { index ->
            """{"id":"route-$index","distanceMeters":100.0,"durationSeconds":20,"geometry":[{"latitude":56.8,"longitude":60.6},{"latitude":56.81,"longitude":60.61}],"maneuvers":[{"id":"m-$index","kind":"ARRIVE","instruction":"Arrive","streetName":null,"shapeIndex":1,"distanceMeters":100.0}]}"""
          }}]
        }
    """.trimIndent()
}

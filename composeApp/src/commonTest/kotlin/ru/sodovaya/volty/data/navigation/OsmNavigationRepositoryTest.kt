package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OsmNavigationRepositoryTest {
    @Test
    fun search_uses_photon_russian_parameters_headers_and_preserves_place_identity() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { photonResponse() }

        val result = repository.search(
            query = "Плотинка",
            near = GeoCoordinate(56.8389, 60.6057),
            languageTag = "ru-RU",
        )

        val place = assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result).value.single()
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("photon.komoot.io", request.url.host)
        assertEquals("/api/", request.url.encodedPath)
        assertEquals("Плотинка", request.url.parameters["q"])
        assertEquals("8", request.url.parameters["limit"])
        assertEquals("default", request.url.parameters["lang"])
        assertEquals("56.8389", request.url.parameters["lat"])
        assertEquals("60.6057", request.url.parameters["lon"])
        assertEquals("ru-RU,ru", request.headers[HttpHeaders.AcceptLanguage])
        assertContains(request.headers[HttpHeaders.UserAgent].orEmpty(), "Volty")
        assertContains(request.headers[HttpHeaders.UserAgent].orEmpty(), "navigation")
        assertEquals("W:123", place.id)
        assertEquals("Плотинка", place.title)
        assertEquals("Набережная Рабочей Молодёжи 20, Екатеринбург, Россия", place.subtitle)
        assertEquals(56.8389, place.coordinate.latitude)
        assertEquals(60.6057, place.coordinate.longitude)
    }

    @Test
    fun search_normalizes_user_whitespace_before_sending_the_photon_query() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) {
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
        }

        repository.search("  Алатырь   центр  ", near = null, languageTag = "ru-RU")

        assertEquals("Алатырь центр", requests.single().url.parameters["q"])
    }

    @Test
    fun search_uses_base_language_and_omits_bias_without_near() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) {
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
        }

        repository.search("Central Park", near = null, languageTag = "en-US")

        val request = requests.single()
        assertEquals("Central Park", request.url.parameters["q"])
        assertEquals("en", request.url.parameters["lang"])
        assertEquals("8", request.url.parameters["limit"])
        assertNull(request.url.parameters["lat"])
        assertNull(request.url.parameters["lon"])
        assertEquals("en-US", request.headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun search_falls_back_to_photon_default_for_unsupported_language() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) {
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
        }

        repository.search("Museo", near = null, languageTag = "es-ES")

        val request = requests.single()
        assertEquals("default", request.url.parameters["lang"])
        assertEquals("es-ES", request.headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun search_uses_supported_photon_language_for_italian() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) {
            "{\"type\":\"FeatureCollection\",\"features\":[]}"
        }

        repository.search("Roma", near = null, languageTag = "it-IT")

        val request = requests.single()
        assertEquals("it", request.url.parameters["lang"])
        assertEquals("it-IT", request.headers[HttpHeaders.AcceptLanguage])
    }

    @Test
    fun routes_use_fixed_fossgis_routed_car_url_and_decode_alternatives_maneuvers_and_geojson() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { osrmResponseWithRoutes() }

        val result = repository.routes(testRequest(alternativesLimit = 2))

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        val request = requests.first()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("routing.openstreetmap.de", request.url.host)
        assertEquals(
            "/routed-car/route/v1/driving/60.6057,56.8389;60.63,56.83",
            request.url.encodedPath,
        )
        assertEquals("full", request.url.parameters["overview"])
        assertEquals("geojson", request.url.parameters["geometries"])
        assertEquals("true", request.url.parameters["steps"])
        assertEquals("true", request.url.parameters["alternatives"])
        assertEquals("ru-RU,ru", request.headers[HttpHeaders.AcceptLanguage])
        assertEquals(2, plan.alternatives.size)
        assertEquals(2, requests.size)

        val first = plan.alternatives.first()
        assertEquals(6, first.geometry.size)
        assertEquals(1234.5, first.distanceMeters)
        assertEquals(322L, first.durationSeconds)
        assertEquals(
            listOf(
                ManeuverKind.DEPART,
                ManeuverKind.RIGHT,
                ManeuverKind.ROUNDABOUT,
                ManeuverKind.U_TURN,
                ManeuverKind.STRAIGHT,
                ManeuverKind.ARRIVE,
            ),
            first.maneuvers.map { it.kind },
        )
        assertEquals("проспект Мира", first.maneuvers[1].streetName)
        assertContains(first.maneuvers[1].instruction, "Поверните направо")
        assertContains(first.maneuvers[1].instruction, "проспект Мира")
        assertContains(first.maneuvers[2].instruction, "круговом движении")
        assertContains(first.maneuvers[3].instruction, "Развернитесь")
        assertContains(first.maneuvers[4].instruction, "Продолжайте движение")
        assertEquals(listOf(0, 1, 2, 3, 4, 5), first.maneuvers.map { it.shapeIndex })

        val second = plan.alternatives[1]
        assertEquals(ManeuverKind.ARRIVE, second.maneuvers.last().kind)
        assertEquals(second.geometry.lastIndex, second.maneuvers.last().shapeIndex)
    }

    @Test
    fun routes_fall_back_to_osrm_demo_after_primary_transport_failure() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            if (request.url.host == "routing.openstreetmap.de") {
                throw IllegalStateException("primary TLS handshake failed")
            }
            respond(osrmResponseWithRoutes(), headers = jsonHeaders())
        }))

        val result = repository.routes(testRequest())

        assertIs<NavigationResult.Success<RoutePlan>>(result)
        assertEquals(
            setOf("routing.openstreetmap.de", "router.project-osrm.org"),
            requests.map { it.url.host }.toSet(),
        )
        assertEquals(2, requests.size)
    }

    @Test
    fun routes_start_both_providers_in_parallel_when_more_than_one_route_is_requested() = runTest {
        val primaryStarted = CompletableDeferred<Unit>()
        val fallbackStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            if (request.url.host == "routing.openstreetmap.de") {
                primaryStarted.complete(Unit)
            } else {
                fallbackStarted.complete(Unit)
            }
            release.await()
            respond(
                osrmResponse(osrmRoute(1200.0, 321.0, 60.61, 56.81, "primary")),
                headers = jsonHeaders(),
            )
        }))
        val routeJob = async { repository.routes(testRequest()) }

        try {
            primaryStarted.await()
            withTimeout(1_000L) { fallbackStarted.await() }
        } finally {
            release.complete(Unit)
        }

        assertIs<NavigationResult.Success<RoutePlan>>(routeJob.await())
    }

    @Test
    fun routes_fall_back_to_osrm_demo_when_primary_is_rate_limited() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            if (request.url.host == "routing.openstreetmap.de") {
                respond(
                    "{}",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.RetryAfter, "17"),
                )
            } else {
                respond(osrmResponseWithRoutes(), headers = jsonHeaders())
            }
        }))

        val result = repository.routes(testRequest())

        assertIs<NavigationResult.Success<RoutePlan>>(result)
        assertEquals(
            setOf("routing.openstreetmap.de", "router.project-osrm.org"),
            requests.map { it.url.host }.toSet(),
        )
        assertEquals(2, requests.size)
    }

    @Test
    fun routes_aggregate_primary_and_fallback_routes_in_primary_first_order() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            val response = if (request.url.host == "routing.openstreetmap.de") {
                osrmResponse(osrmRoute(1200.0, 321.0, 60.61, 56.81, "primary"))
            } else {
                osrmResponse(
                    osrmRoute(1400.0, 350.0, 60.615, 56.82, "fallback one"),
                    osrmRoute(1600.0, 390.0, 60.62, 56.81, "fallback two"),
                )
            }
            respond(response, headers = jsonHeaders())
        }))

        val result = repository.routes(testRequest())

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        assertEquals(listOf(1200.0, 1400.0, 1600.0), plan.alternatives.map { it.distanceMeters })
        assertEquals(2, requests.size)
        assertEquals(requests[0].url.parameters, requests[1].url.parameters)
        assertEquals(
            requests[0].url.encodedPath.substringAfterLast("/driving/"),
            requests[1].url.encodedPath.substringAfterLast("/driving/"),
        )
    }

    @Test
    fun routes_deduplicate_equivalent_routes_across_providers() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            val response = if (request.url.host == "routing.openstreetmap.de") {
                osrmResponse(osrmRoute(1200.0, 321.0, 60.61, 56.81, "primary"))
            } else {
                osrmResponse(
                    osrmRoute(1208.0, 324.0, 60.61008, 56.81007, "fallback duplicate"),
                    osrmRoute(1500.0, 400.0, 60.615, 56.82, "fallback distinct"),
                )
            }
            respond(response, headers = jsonHeaders())
        }))

        val result = repository.routes(testRequest())

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        assertEquals(2, plan.alternatives.size)
        assertEquals(listOf(1200.0, 1500.0), plan.alternatives.map { it.distanceMeters })
        assertEquals(2, requests.size)
    }

    @Test
    fun routes_with_alternatives_limit_one_return_exactly_one_without_fallback_request() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            respond(
                osrmResponse(
                    osrmRoute(1200.0, 321.0, 60.61, 56.81, "primary"),
                    osrmRoute(1500.0, 400.0, 60.615, 56.82, "unneeded"),
                ),
                headers = jsonHeaders(),
            )
        }))

        val result = repository.routes(testRequest(alternativesLimit = 1))

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        assertEquals(1, plan.alternatives.size)
        assertEquals(1, requests.size)
    }

    @Test
    fun routes_preserve_typed_provider_failure_when_both_providers_fail() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            requests += request
            if (request.url.host == "routing.openstreetmap.de") {
                respond("", HttpStatusCode.ServiceUnavailable, headers = jsonHeaders())
            } else {
                respond(
                    "",
                    HttpStatusCode.TooManyRequests,
                    headers = headersOf(HttpHeaders.RetryAfter, "17"),
                )
            }
        }))

        val result = repository.routes(testRequest())

        assertEquals(NavigationFailure.RateLimited(17), assertFailure(result))
        assertEquals(2, requests.size)
    }

    @Test
    fun aggregated_route_ids_are_unique_and_deterministic() = runTest {
        val repository = OsmNavigationRepository(HttpClient(MockEngine { request ->
            val response = if (request.url.host == "routing.openstreetmap.de") {
                osrmResponse(osrmRoute(1200.0, 321.0, 60.61, 56.81, "primary"))
            } else {
                osrmResponse(
                    osrmRoute(1400.0, 350.0, 60.615, 56.82, "fallback one"),
                    osrmRoute(1600.0, 390.0, 60.62, 56.81, "fallback two"),
                )
            }
            respond(response, headers = jsonHeaders())
        }))

        val first = assertIs<NavigationResult.Success<RoutePlan>>(repository.routes(testRequest())).value
        val second = assertIs<NavigationResult.Success<RoutePlan>>(repository.routes(testRequest())).value

        val expectedIds = listOf("osrm-route-1", "osrm-route-2", "osrm-route-3")
        assertEquals(expectedIds, first.alternatives.map { it.id })
        assertEquals(expectedIds, second.alternatives.map { it.id })
        assertEquals(expectedIds.size, first.alternatives.map { it.id }.toSet().size)
    }

    @Test
    fun routes_cap_provider_alternatives_to_domain_maximum() = runTest {
        val repository = repository { osrmResponseWithRoutes() }

        val result = repository.routes(testRequest(alternativesLimit = 99))

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        assertEquals(3, plan.alternatives.size)
    }

    @Test
    fun malformed_coordinates_and_empty_route_geometry_are_typed_as_malformed() = runTest {
        val malformedSearch = repository {
            """
                {"type":"FeatureCollection","features":[
                  {"type":"Feature","properties":{"name":"Bad"},"geometry":{"type":"Point","coordinates":[60.6,91.0]}}
                ]}
            """.trimIndent()
        }
        assertEquals(
            NavigationFailure.MalformedResponse,
            assertFailure(malformedSearch.search("Bad", null, "ru-RU")),
        )

        val malformedRoute = repository {
            """
                {"code":"Ok","routes":[{"distance":10,"duration":10,
                "geometry":{"type":"LineString","coordinates":[]},"legs":[]}]}
            """.trimIndent()
        }
        assertEquals(
            NavigationFailure.MalformedResponse,
            assertFailure(malformedRoute.routes(testRequest())),
        )
    }

    @Test
    fun typed_http_failures_include_retry_after_and_do_not_retry() = runTest {
        val limitedRequests = mutableListOf<HttpRequestData>()
        val limited = repository(
            requests = limitedRequests,
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "17"),
        ) { "" }
        assertEquals(
            NavigationFailure.RateLimited(17),
            assertFailure(limited.search("Екатеринбург", null, "ru-RU")),
        )
        assertEquals(1, limitedRequests.size)

        val unavailable = repository(
            status = HttpStatusCode.ServiceUnavailable,
            headers = headersOf(HttpHeaders.RetryAfter, "9"),
        ) { "" }
        assertEquals(
            NavigationFailure.ProviderUnavailable,
            assertFailure(unavailable.routes(testRequest())),
        )

        val invalid = repository(
            status = HttpStatusCode.BadRequest,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
        ) { "bad request" }
        assertIs<NavigationFailure.InvalidRequest>(assertFailure(invalid.search("x", null, "en-US")))

        val noRoute = repository { "{\"code\":\"NoRoute\",\"routes\":[]}" }
        assertEquals(NavigationFailure.NoRoute, assertFailure(noRoute.routes(testRequest())))
    }

    @Test
    fun oversized_stream_is_stopped_at_bounded_utf8_read_limit() = runTest {
        val channel = ByteChannel(autoFlush = true)
        val chunk = ByteArray(8192) { 'x'.code.toByte() }
        val totalBytes = 12_000_000
        var writtenBytes = 0
        val writer = launch {
            repeat(totalBytes / chunk.size) {
                channel.writeFully(chunk)
                writtenBytes += chunk.size
            }
            channel.close()
        }
        val oversized = OsmNavigationRepository(HttpClient(MockEngine {
            respond(channel, headers = jsonHeaders())
        }))

        try {
            val result = oversized.search("x", null, "en-US")
            assertEquals(NavigationFailure.MalformedResponse, assertFailure(result))
            assertTrue(writtenBytes < totalBytes, "bounded read must not consume the full response")
        } finally {
            writer.cancelAndJoin()
            channel.cancel(CancellationException("test cleanup"))
        }
    }

    @Test
    fun oversized_error_body_preserves_http_failure_status() = runTest {
        val oversizedBody = ByteArray(8_000_001) { 'x'.code.toByte() }
        val limited = OsmNavigationRepository(HttpClient(MockEngine {
            respond(
                oversizedBody,
                HttpStatusCode.TooManyRequests,
                headersOf(
                    HttpHeaders.ContentLength to listOf(oversizedBody.size.toString()),
                    HttpHeaders.RetryAfter to listOf("17"),
                ),
            )
        }))

        assertEquals(
            NavigationFailure.RateLimited(17),
            assertFailure(limited.search("x", null, "en-US")),
        )
    }

    @Test
    fun network_failure_is_offline() = runTest {
        val offline = OsmNavigationRepository(HttpClient(MockEngine {
            throw IllegalStateException("network down")
        }))
        assertEquals(NavigationFailure.Offline, assertFailure(offline.search("x", null, "en-US")))
    }

    @Test
    fun request_timeout_during_search_is_mapped_to_offline() = runTest {
        val slow = OsmNavigationRepository(HttpClient(MockEngine { request ->
            throw HttpRequestTimeoutException(request)
        }))

        assertEquals(
            NavigationFailure.Offline,
            assertFailure(slow.search("Екатеринбург", null, "ru-RU")),
        )
    }

    @Test
    fun request_timeout_during_route_is_mapped_to_offline() = runTest {
        val slow = OsmNavigationRepository(HttpClient(MockEngine { request ->
            throw HttpRequestTimeoutException(request)
        }))

        assertEquals(
            NavigationFailure.Offline,
            assertFailure(slow.routes(testRequest())),
        )
    }

    @Test
    fun cancellation_and_fatal_errors_are_not_swallowed() = runTest {
        val cancelled = OsmNavigationRepository(HttpClient(MockEngine {
            throw CancellationException("cancelled")
        }))
        assertFailsWith<CancellationException> {
            cancelled.search("x", null, "en-US")
        }

        val fatal = OsmNavigationRepository(HttpClient(MockEngine {
            throw AssertionError("fatal")
        }))
        assertFailsWith<AssertionError> {
            fatal.search("x", null, "en-US")
        }
    }

    @Test
    fun http_date_retry_after_is_parsed_with_safe_past_date_fallback() = runTest {
        val limited = repository(
            status = HttpStatusCode.TooManyRequests,
            headers = headersOf(HttpHeaders.RetryAfter, "Wed, 21 Oct 2015 07:28:00 GMT"),
        ) { "" }

        assertEquals(
            NavigationFailure.RateLimited(1),
            assertFailure(limited.search("x", null, "en-US")),
        )
    }

    private fun repository(
        requests: MutableList<HttpRequestData> = mutableListOf(),
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        response: () -> String,
    ): OsmNavigationRepository = OsmNavigationRepository(
        HttpClient(MockEngine { request ->
            requests += request
            respond(response(), status, headers)
        }),
    )

    private fun testRequest(alternativesLimit: Int = 3) = RouteRequest(
        origin = GeoCoordinate(56.8389, 60.6057),
        destination = PlaceCandidate(
            id = "W:123",
            title = "Плотинка",
            subtitle = "Екатеринбург, Россия",
            coordinate = GeoCoordinate(56.83, 60.63),
        ),
        languageTag = "ru-RU",
        alternativesLimit = alternativesLimit,
    )

    private fun assertFailure(result: NavigationResult<*>): NavigationFailure =
        assertIs<NavigationResult.Failure>(result).reason

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private fun osrmResponse(vararg routes: String) =
        """{"code":"Ok","routes":[${routes.joinToString(",")}]}"""

    private fun osrmRoute(
        distance: Double,
        duration: Double,
        middleLongitude: Double,
        middleLatitude: Double,
        streetName: String,
    ) = """
        {
          "distance":$distance,
          "duration":$duration,
          "geometry":{"type":"LineString","coordinates":[
            [60.6057,56.8389],[$middleLongitude,$middleLatitude],[60.63,56.83]
          ]},
          "legs":[{"steps":[
            {"distance":$distance,"duration":$duration,"name":"$streetName",
             "maneuver":{"type":"depart","location":[60.6057,56.8389]}}
          ]}]
        }
    """.trimIndent()

    private fun photonResponse() = """
        {
          "type":"FeatureCollection",
          "features":[{
            "type":"Feature",
            "properties":{
              "osm_type":"W",
              "osm_id":123,
              "name":"Плотинка",
              "street":"Набережная Рабочей Молодёжи",
              "housenumber":"20",
              "city":"Екатеринбург",
              "country":"Россия"
            },
            "geometry":{"type":"Point","coordinates":[60.6057,56.8389]}
          }]
        }
    """.trimIndent()

    private fun osrmResponseWithRoutes() = """
        {
          "code":"Ok",
          "routes":[
            {
              "distance":1234.5,
              "duration":321.2,
              "geometry":{"type":"LineString","coordinates":[
                [60.6057,56.8389],[60.61,56.81],[60.62,56.82],[60.625,56.825],[60.628,56.828],[60.63,56.83]
              ]},
              "legs":[{"steps":[
                {"distance":100,"duration":20,"name":"ул. Ленина","maneuver":{"type":"depart","modifier":"straight","location":[60.6057,56.8389]}},
                {"distance":200,"duration":40,"name":"проспект Мира","maneuver":{"type":"turn","modifier":"right","location":[60.61,56.81]}},
                {"distance":250,"duration":50,"name":"","maneuver":{"type":"roundabout","modifier":"right","exit":2,"location":[60.62,56.82]}},
                {"distance":300,"duration":60,"name":"ул. Восточная","maneuver":{"type":"turn","modifier":"uturn","location":[60.625,56.825]}},
                {"distance":384.5,"duration":100,"name":"ул. Малышева","maneuver":{"type":"turn","modifier":"straight","location":[60.628,56.828]}},
                {"distance":0,"duration":0,"name":"","maneuver":{"type":"arrive","location":[60.63,56.83]}}
              ]}]
            },
            {
              "distance":1500,
              "duration":400,
              "geometry":{"type":"LineString","coordinates":[[60.6057,56.8389],[60.615,56.82],[60.63,56.83]]},
              "legs":[{"steps":[
                {"distance":500,"duration":120,"name":"ул. Ленина","maneuver":{"type":"depart","location":[60.6057,56.8389]}},
                {"distance":1000,"duration":280,"name":"ул. Восточная","maneuver":{"type":"turn","modifier":"left","location":[60.615,56.82]}}
              ]}]
            },
            {
              "distance":1600,
              "duration":420,
              "geometry":{"type":"LineString","coordinates":[[60.6057,56.8389],[60.62,56.81],[60.63,56.83]]},
              "legs":[{"steps":[{"distance":1600,"duration":420,"name":"ул. Ленина","maneuver":{"type":"depart","location":[60.6057,56.8389]}}]}]
            },
            {
              "distance":1700,
              "duration":440,
              "geometry":{"type":"LineString","coordinates":[[60.6057,56.8389],[60.621,56.81],[60.63,56.83]]},
              "legs":[{"steps":[{"distance":1700,"duration":440,"name":"ул. Ленина","maneuver":{"type":"depart","location":[60.6057,56.8389]}}]}]
            }
          ]
        }
    """.trimIndent()
}

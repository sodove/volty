package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
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
    fun routes_use_fixed_fossgis_routed_bike_url_and_decode_alternatives_maneuvers_and_geojson() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val repository = repository(requests) { osrmResponseWithRoutes() }

        val result = repository.routes(testRequest(alternativesLimit = 2))

        val plan = assertIs<NavigationResult.Success<RoutePlan>>(result).value
        val request = requests.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("routing.openstreetmap.de", request.url.host)
        assertEquals(
            "/routed-bike/route/v1/driving/60.6057,56.8389;60.63,56.83",
            request.url.encodedPath,
        )
        assertEquals("full", request.url.parameters["overview"])
        assertEquals("geojson", request.url.parameters["geometries"])
        assertEquals("true", request.url.parameters["steps"])
        assertEquals("true", request.url.parameters["alternatives"])
        assertEquals("ru-RU,ru", request.headers[HttpHeaders.AcceptLanguage])
        assertEquals(2, plan.alternatives.size)

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
    fun oversized_body_is_malformed_network_failure_is_offline_and_cancellation_is_rethrown() = runTest {
        val oversized = repository { "x".repeat(2_000_001) }
        assertEquals(
            NavigationFailure.MalformedResponse,
            assertFailure(oversized.search("x", null, "en-US")),
        )

        val offline = OsmNavigationRepository(HttpClient(MockEngine {
            throw IllegalStateException("network down")
        }))
        assertEquals(NavigationFailure.Offline, assertFailure(offline.search("x", null, "en-US")))

        val cancelled = OsmNavigationRepository(HttpClient(MockEngine {
            throw CancellationException("cancelled")
        }))
        assertFailsWith<CancellationException> {
            cancelled.search("x", null, "en-US")
        }
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
                {"distance":300,"duration":60,"name":"ул. Восточная","maneuver":{"type":"uturn","location":[60.625,56.825]}},
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

package ru.sodovaya.volty.backend

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationEndpointContractTest {
    @Test
    fun navigation_search_is_public_and_uses_the_injected_provider() = testApplication {
        val provider = FakeNavigationProvider()
        application { module(testDependencies(provider)) }

        val response = client.get("/v1/navigation/search?q=Main%20Street&languageTag=ru-RU")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, provider.searchCalls)
    }

    @Test
    fun invalid_route_input_is_rejected_but_legacy_profile_is_ignored() = testApplication {
        val provider = FakeNavigationProvider()
        application { module(testDependencies(provider)) }

        val invalid = client.post("/v1/navigation/routes") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(routeRequest(origin = GeoCoordinateDto(91.0, 60.6)))
        }
        val legacy = client.post("/v1/navigation/routes") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(routeRequest(profile = "spaceship"))
        }

        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertEquals(HttpStatusCode.OK, legacy.status)
        assertEquals(1, provider.routeCalls)
    }

    @Test
    fun no_route_is_typed_and_provider_failure_is_unavailable() = testApplication {
        val provider = FakeNavigationProvider()
        application { module(testDependencies(provider)) }

        provider.routeResult = ProviderResult.NoRoute
        val noRoute = client.post("/v1/navigation/routes") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(routeRequest())
        }
        provider.routeResult = ProviderResult.Unavailable
        val unavailable = client.post("/v1/navigation/routes") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(routeRequest(origin = GeoCoordinateDto(56.801, 60.6)))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, noRoute.status)
        assertTrue(noRoute.bodyAsText().contains("navigation_no_route"))
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
    }

    @Test
    fun route_quota_returns_retry_after_without_affecting_search_quota() = testApplication {
        val provider = FakeNavigationProvider()
        application { module(testDependencies(provider)) }

        repeat(10) { index ->
            val response = client.post("/v1/navigation/routes") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(routeRequest(origin = GeoCoordinateDto(56.8 + index * 0.00001, 60.6)))
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        val limited = client.post("/v1/navigation/routes") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(routeRequest(origin = GeoCoordinateDto(56.802, 60.6)))
        }

        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertTrue((limited.headers[HttpHeaders.RetryAfter]?.toLongOrNull() ?: 0L) >= 1L)
        assertEquals(HttpStatusCode.OK, client.get("/v1/navigation/search?q=Main%20Street").status)
    }

    private fun testDependencies(provider: NavigationProvider) = AppDependencies(
        config = AppConfig.forTests().copy(
            navigationProvider = "graphhopper",
            navigationEnabled = true,
            graphHopperApiKey = "test-key",
            navigationProfileId = "personal-mobility",
        ),
        store = object : BackendStore {},
        testMode = true,
        navigationProvider = provider,
    )

    private fun routeRequest(
        origin: GeoCoordinateDto = GeoCoordinateDto(56.8, 60.6),
        profile: String? = null,
    ) = backendJson().encodeToString(
        NavigationRouteRequestDto(
            origin = origin,
            destination = NavigationPlaceDto("place-1", "Main Street", "Yekaterinburg", 56.81, 60.61),
            profile = profile,
            languageTag = "ru-RU",
            alternativesLimit = 3,
        ),
    )

    private class FakeNavigationProvider : NavigationProvider {
        var searchCalls = 0
        var routeCalls = 0
        var routeResult: ProviderResult<NavigationRouteResponse> = ProviderResult.Success(
            NavigationRouteResponse(
                destination = NavigationPlaceDto("place-1", "Main Street", null, 56.81, 60.61),
                routes = listOf(
                    NavigationRouteDto(
                        id = "route-1",
                        distanceMeters = 100.0,
                        durationSeconds = 20,
                        geometry = listOf(GeoCoordinateDto(60.6, 56.8), GeoCoordinateDto(60.61, 56.81)),
                        maneuvers = listOf(
                            NavigationManeuverDto("m-1", "ARRIVE", "Arrive", null, 1, 100.0),
                        ),
                    ),
                ),
            ),
        )

        override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<NavigationPlaceDto>> {
            searchCalls++
            return ProviderResult.Success(emptyList())
        }

        override suspend fun routes(request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse> {
            routeCalls++
            return routeResult
        }
    }
}

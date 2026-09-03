package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

class HttpNavigationRepository(
    private val client: HttpClient = HttpClient(),
    baseUrl: String = DEFAULT_BASE_URL,
) : NavigationRepository {
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> = try {
        val response = client.get {
            url("$baseUrl/navigation/search")
            parameter("q", query)
            parameter("languageTag", languageTag)
            near?.let {
                parameter("latitude", it.latitude)
                parameter("longitude", it.longitude)
            }
        }
        response.toResult { body ->
            val places = json.decodeFromString<List<NavigationPlaceWire>>(body)
            places.map(::decodePlace)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        NavigationResult.Failure(NavigationFailure.Offline)
    }

    override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> = try {
        val response = client.post {
            url("$baseUrl/navigation/routes")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request.toWire()))
        }
        response.toResult { body -> decodeRoutePlan(json.decodeFromString<NavigationRouteResponseWire>(body)) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        NavigationResult.Failure(NavigationFailure.Offline)
    }

    private suspend fun <T> HttpResponse.toResult(decode: (String) -> T): NavigationResult<T> {
        val body = bodyAsText()
        if (body.length > MAX_RESPONSE_CHARS) return NavigationResult.Failure(NavigationFailure.MalformedResponse)
        if (status.isSuccess()) {
            return runCatching { NavigationResult.Success(decode(body)) }
                .getOrElse { NavigationResult.Failure(NavigationFailure.MalformedResponse) }
        }
        val error = runCatching { json.decodeFromString<NavigationErrorWire>(body) }.getOrNull()
        return when {
            status == HttpStatusCode.UnprocessableEntity && error?.code == "navigation_no_route" ->
                NavigationResult.Failure(NavigationFailure.NoRoute)
            status == HttpStatusCode.TooManyRequests ->
                NavigationResult.Failure(
                    NavigationFailure.RateLimited(
                        responseRetryAfterSeconds(error),
                    ),
                )
            status == HttpStatusCode.ServiceUnavailable && error?.code == "navigation_malformed_response" ->
                NavigationResult.Failure(NavigationFailure.MalformedResponse)
            status == HttpStatusCode.ServiceUnavailable ->
                NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
            status == HttpStatusCode.BadRequest ->
                NavigationResult.Failure(NavigationFailure.InvalidRequest(error?.message ?: "Navigation request is invalid"))
            else -> NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }
    }

    private fun HttpResponse.responseRetryAfterSeconds(error: NavigationErrorWire?): Long {
        val headerSeconds = headers[HttpHeaders.RetryAfter]?.toLongOrNull()
        return (headerSeconds ?: error?.details?.get("retryAfterSeconds")?.toLongOrNull() ?: 60L).coerceAtLeast(1L)
    }

    private fun decodePlace(wire: NavigationPlaceWire): PlaceCandidate = PlaceCandidate(
        id = wire.id,
        title = wire.title,
        subtitle = wire.subtitle,
        coordinate = GeoCoordinate(wire.latitude, wire.longitude),
    )

    private fun RouteRequest.toWire() = NavigationRouteRequestWire(
        origin = GeoCoordinateWire(origin.latitude, origin.longitude),
        destination = NavigationPlaceWire(
            id = destination.id,
            title = destination.title,
            subtitle = destination.subtitle,
            latitude = destination.coordinate.latitude,
            longitude = destination.coordinate.longitude,
        ),
        languageTag = languageTag,
        alternativesLimit = alternativesLimit,
    )

    private fun decodeRoutePlan(wire: NavigationRouteResponseWire): RoutePlan {
        if (wire.schemaVersion != 1 || wire.routes.isEmpty()) throw MalformedNavigationResponseException()
        val destination = decodePlace(wire.destination)
        val routes = wire.routes.map { route ->
            RouteAlternative(
                id = route.id,
                distanceMeters = route.distanceMeters,
                durationSeconds = route.durationSeconds,
                geometry = route.geometry.map { GeoCoordinate(it.latitude, it.longitude) },
                maneuvers = route.maneuvers.map { maneuver ->
                    RouteManeuver(
                        id = maneuver.id,
                        kind = decodeManeuverKind(maneuver.kind),
                        instruction = maneuver.instruction,
                        streetName = maneuver.streetName,
                        shapeIndex = maneuver.shapeIndex,
                        distanceMeters = maneuver.distanceMeters,
                    )
                },
            )
        }
        return RoutePlan(destination, routes)
    }

    private fun decodeManeuverKind(value: String): ManeuverKind =
        runCatching { ManeuverKind.valueOf(value) }.getOrElse { ManeuverKind.UNKNOWN }

    private class MalformedNavigationResponseException : IllegalArgumentException()

    @Serializable
    private data class GeoCoordinateWire(val latitude: Double, val longitude: Double)

    @Serializable
    private data class NavigationPlaceWire(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val latitude: Double,
        val longitude: Double,
    )

    @Serializable
    private data class NavigationManeuverWire(
        val id: String,
        val kind: String,
        val instruction: String,
        val streetName: String? = null,
        val shapeIndex: Int,
        val distanceMeters: Double,
    )

    @Serializable
    private data class NavigationRouteWire(
        val id: String,
        val distanceMeters: Double,
        val durationSeconds: Long,
        val geometry: List<GeoCoordinateWire>,
        val maneuvers: List<NavigationManeuverWire>,
    )

    @Serializable
    private data class NavigationRouteResponseWire(
        val schemaVersion: Int = 1,
        val destination: NavigationPlaceWire,
        // Kept for compatibility with an older backend response. Route identity is transport-agnostic.
        val profile: String? = null,
        val routes: List<NavigationRouteWire>,
    )

    @Serializable
    private data class NavigationRouteRequestWire(
        val origin: GeoCoordinateWire,
        val destination: NavigationPlaceWire,
        val languageTag: String,
        val alternativesLimit: Int,
    )

    @Serializable
    private data class NavigationErrorWire(
        val code: String? = null,
        val message: String? = null,
        val details: Map<String, String>? = null,
    )

    private companion object {
        const val DEFAULT_BASE_URL = "https://volty.sodove.ru/v1"
        const val MAX_RESPONSE_CHARS = 2_000_000
        val json = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
            isLenient = false
        }
    }
}

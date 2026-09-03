package ru.sodovaya.volty.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GraphHopperNavigationProvider(
    private val config: AppConfig,
    private val client: HttpClient = graphHopperHttpClient(config),
    private val json: Json = Json { ignoreUnknownKeys = false; isLenient = false },
) : NavigationProvider {
    override suspend fun search(request: ProviderSearchRequest): ProviderResult<List<NavigationPlaceDto>> {
        val response = execute {
            client.get("$BASE_URL/geocode") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("q", request.query)
                parameter("locale", request.languageTag)
                parameter("limit", request.limit.coerceIn(1, MAX_GEOCODE_RESULTS))
                request.near?.let { parameter("point", "${it.latitude},${it.longitude}") }
                parameter("key", requireNotNull(config.graphHopperApiKey))
            }
        } ?: return ProviderResult.Unavailable
        return when (response) {
            is ProviderHttpResponse.RateLimited -> ProviderResult.RateLimited(response.retryAfterSeconds)
            is ProviderHttpResponse.Failed -> ProviderResult.Unavailable
            is ProviderHttpResponse.Ok -> parseGeocode(response.body)
        }
    }

    override suspend fun routes(request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse> {
        val response = execute {
            client.get("$BASE_URL/route") {
                header(HttpHeaders.UserAgent, USER_AGENT)
                parameter("point", "${request.origin.latitude},${request.origin.longitude}")
                parameter("point", "${request.destination.latitude},${request.destination.longitude}")
                parameter("profile", requireNotNull(config.navigationProfileId))
                parameter("locale", request.languageTag)
                parameter("instructions", "true")
                parameter("calc_points", "true")
                parameter("points_encoded", "false")
                parameter("algorithm", "alternative_route")
                parameter("alternative_route.max_paths", request.alternativesLimit.coerceIn(1, MAX_ROUTE_ALTERNATIVES))
                parameter("key", requireNotNull(config.graphHopperApiKey))
            }
        } ?: return ProviderResult.Unavailable
        return when (response) {
            is ProviderHttpResponse.RateLimited -> ProviderResult.RateLimited(response.retryAfterSeconds)
            is ProviderHttpResponse.Failed -> ProviderResult.Unavailable
            is ProviderHttpResponse.Ok -> parseRoutes(response.body, request)
        }
    }

    private suspend fun execute(block: suspend () -> io.ktor.client.statement.HttpResponse): ProviderHttpResponse? = try {
        val response = block()
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.coerceAtLeast(1L)
        when {
            response.status == HttpStatusCode.TooManyRequests -> ProviderHttpResponse.RateLimited(retryAfter)
            response.status.value in 200..299 -> ProviderHttpResponse.Ok(response.bodyAsText())
            else -> ProviderHttpResponse.Failed
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        null
    }

    private fun parseGeocode(body: String): ProviderResult<List<NavigationPlaceDto>> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val hits = root["hits"]?.jsonArray ?: return ProviderResult.MalformedResponse
        val places = hits.take(MAX_GEOCODE_RESULTS).mapIndexed { index, element -> parsePlace(element.jsonObject, index) }
        ProviderResult.Success(places)
    } catch (_: Throwable) {
        ProviderResult.MalformedResponse
    }

    private fun parsePlace(hit: JsonObject, index: Int): NavigationPlaceDto {
        val point = hit["point"]?.jsonObject ?: throw MalformedNavigationResponseException()
        val latitude = point.requiredDouble("lat")
        val longitude = point.requiredDouble("lng")
        requireCoordinate(latitude, longitude)
        val title = hit.requiredString("name")
        val subtitle = listOf("city", "state", "country")
            .mapNotNull { hit[it]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
            .joinToString(", ")
            .takeIf(String::isNotBlank)
        val id = hit["osm_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: hit["place_id"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank)
            ?: "geocode-$index-${latitude.formatForId()}-${longitude.formatForId()}"
        return NavigationPlaceDto(id, title, subtitle, latitude, longitude)
    }

    private fun parseRoutes(body: String, request: ProviderRouteRequest): ProviderResult<NavigationRouteResponse> = try {
        val root = json.parseToJsonElement(body).jsonObject
        val paths = root["paths"]?.jsonArray ?: return ProviderResult.MalformedResponse
        if (paths.isEmpty()) return ProviderResult.NoRoute
        val routes = paths.take(request.alternativesLimit.coerceIn(1, MAX_ROUTE_ALTERNATIVES)).mapIndexed { index, element ->
            parseRoute(element.jsonObject, index)
        }
        if (routes.isEmpty()) ProviderResult.NoRoute else ProviderResult.Success(
            NavigationRouteResponse(
                destination = NavigationPlaceDto(
                    id = "coordinate-${request.destination.latitude.formatForId()}-${request.destination.longitude.formatForId()}",
                    title = "Destination",
                    subtitle = null,
                    latitude = request.destination.latitude,
                    longitude = request.destination.longitude,
                ),
                routes = routes,
            ),
        )
    } catch (_: Throwable) {
        ProviderResult.MalformedResponse
    }

    private fun parseRoute(path: JsonObject, index: Int): NavigationRouteDto {
        val geometryObject = path["points"]?.jsonObject ?: throw MalformedNavigationResponseException()
        val coordinateArray = geometryObject["coordinates"]?.jsonArray ?: throw MalformedNavigationResponseException()
        val geometry = coordinateArray.map { coordinate ->
            val pair = coordinate.jsonArray
            if (pair.size < 2) throw MalformedNavigationResponseException()
            val longitude = pair[0].jsonPrimitive.doubleOrNull ?: throw MalformedNavigationResponseException()
            val latitude = pair[1].jsonPrimitive.doubleOrNull ?: throw MalformedNavigationResponseException()
            requireCoordinate(latitude, longitude)
            GeoCoordinateDto(latitude, longitude)
        }
        if (geometry.size < 2) throw MalformedNavigationResponseException()
        val distance = path.requiredDouble("distance")
        require(distance >= 0.0)
        val durationMillis = path.requiredDouble("time")
        require(durationMillis > 0.0)
        val instructions = path["instructions"]?.jsonArray ?: throw MalformedNavigationResponseException()
        val maneuvers = instructions.mapIndexed { maneuverIndex, element ->
            val instruction = element.jsonObject
            val interval = instruction["interval"]?.jsonArray ?: throw MalformedNavigationResponseException()
            val shapeIndex = interval.firstOrNull()?.jsonPrimitive?.intOrNull ?: throw MalformedNavigationResponseException()
            require(shapeIndex in geometry.indices)
            val maneuverDistance = instruction.requiredDouble("distance")
            require(maneuverDistance.isFinite() && maneuverDistance >= 0.0)
            NavigationManeuverDto(
                id = "route-$index-maneuver-$maneuverIndex",
                kind = kindForSign(instruction["sign"]?.jsonPrimitive?.intOrNull ?: throw MalformedNavigationResponseException()),
                instruction = instruction.requiredString("text"),
                streetName = instruction["street_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank),
                shapeIndex = shapeIndex,
                distanceMeters = maneuverDistance,
            )
        }
        if (maneuvers.isEmpty() || maneuvers.last().kind != "ARRIVE") throw MalformedNavigationResponseException()
        return NavigationRouteDto(
            id = "route-${index + 1}",
            distanceMeters = distance,
            durationSeconds = kotlin.math.ceil(durationMillis / 1_000.0).toLong().coerceAtLeast(1L),
            geometry = geometry,
            maneuvers = maneuvers,
        )
    }

    private fun kindForSign(sign: Int): String = when (sign) {
        -98, U_TURN_SIGN -> "U_TURN"
        -3 -> "SHARP_LEFT"
        -2 -> "LEFT"
        -1 -> "SLIGHT_LEFT"
        0 -> "STRAIGHT"
        1 -> "SLIGHT_RIGHT"
        2 -> "RIGHT"
        3 -> "SHARP_RIGHT"
        4 -> "ARRIVE"
        6, 7, 8 -> "ROUNDABOUT"
        else -> "UNKNOWN"
    }

    private sealed interface ProviderHttpResponse {
        data class Ok(val body: String) : ProviderHttpResponse
        data class RateLimited(val retryAfterSeconds: Long?) : ProviderHttpResponse
        data object Failed : ProviderHttpResponse
    }

    private class MalformedNavigationResponseException : IllegalArgumentException()

    private companion object {
        const val BASE_URL = "https://graphhopper.com/api/1"
        const val MAX_GEOCODE_RESULTS = 8
        const val MAX_ROUTE_ALTERNATIVES = 3
        const val USER_AGENT = "Volty/0.1 navigation"
        const val U_TURN_SIGN = -8
    }
}

private fun graphHopperHttpClient(config: AppConfig): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    install(HttpTimeout) {
        connectTimeoutMillis = config.navigationConnectTimeoutMillis
        requestTimeoutMillis = config.navigationRequestTimeoutMillis
        socketTimeoutMillis = config.navigationRequestTimeoutMillis
    }
}

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.content
    ?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("missing string")

private fun JsonObject.requiredDouble(name: String): Double = this[name]?.jsonPrimitive?.doubleOrNull
    ?: throw IllegalArgumentException("missing number")

private fun requireCoordinate(latitude: Double, longitude: Double) {
    require(latitude.isFinite() && latitude in -90.0..90.0)
    require(longitude.isFinite() && longitude in -180.0..180.0)
}

private fun Double.formatForId(): String = java.lang.String.format(java.util.Locale.US, "%.6f", this)

package ru.sodovaya.volty.data.navigation

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.http.isSuccess
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import kotlin.math.abs
import kotlin.math.ceil

class OsmNavigationRepository(
    private val client: HttpClient = createNavigationHttpClient(),
) : NavigationRepository {
    override suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> = try {
        val language = requestLanguage(languageTag)
        val response = client.get {
            url(PHOTON_URL)
            parameter("q", query.trim().replace(WHITESPACE, " "))
            parameter("limit", 8)
            parameter("lang", language.photonLanguage)
            near?.let {
                parameter("lat", it.latitude)
                parameter("lon", it.longitude)
            }
            header(HttpHeaders.AcceptLanguage, language.acceptLanguage)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        val body = response.readBoundedBody()
        response.toResult(body) { text -> decodePhoton(text) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NavigationResult.Failure(NavigationFailure.Offline)
    }

    override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
        val language = requestLanguage(request.languageTag)
        val limit = request.alternativesLimit.coerceIn(1, MAX_ALTERNATIVES)
        var lastFailure: NavigationFailure = NavigationFailure.Offline
        var primaryPlan: RoutePlan? = null

        return try {
            for (endpoint in OSRM_URLS) {
                val result = try {
                    routeFromEndpoint(endpoint, request, language.acceptLanguage, limit)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    NavigationResult.Failure(NavigationFailure.Offline)
                }

                when (result) {
                    is NavigationResult.Success -> {
                        val previousPrimary = primaryPlan
                        if (previousPrimary == null) {
                            primaryPlan = result.value
                            if (result.value.alternatives.size >= limit) {
                                return NavigationResult.Success(result.value.withDeterministicRouteIds(limit))
                            }
                        } else {
                            return NavigationResult.Success(
                                previousPrimary.aggregateWith(result.value, limit),
                            )
                        }
                    }
                    is NavigationResult.Failure -> {
                        lastFailure = result.reason
                        val successfulPrimary = primaryPlan
                        if (successfulPrimary != null) {
                            return NavigationResult.Success(successfulPrimary.withDeterministicRouteIds(limit))
                        }
                        if (result.reason !is NavigationFailure.Offline &&
                            result.reason !is NavigationFailure.ProviderUnavailable &&
                            result.reason !is NavigationFailure.RateLimited
                        ) {
                            return result
                        }
                    }
                }
            }
            primaryPlan?.let { NavigationResult.Success(it.withDeterministicRouteIds(limit)) }
                ?: NavigationResult.Failure(lastFailure)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            primaryPlan?.let { NavigationResult.Success(it.withDeterministicRouteIds(limit)) }
                ?: NavigationResult.Failure(lastFailure)
        }
    }

    private suspend fun routeFromEndpoint(
        endpoint: String,
        request: RouteRequest,
        acceptLanguage: String,
        alternativesLimit: Int,
    ): NavigationResult<RoutePlan> {
        val origin = request.origin
        val destination = request.destination.coordinate
        val response = client.get {
            url(
                "$endpoint/${origin.longitude},${origin.latitude};" +
                    "${destination.longitude},${destination.latitude}",
            )
            parameter("overview", "full")
            parameter("geometries", "geojson")
            parameter("steps", true)
            parameter("alternatives", true)
            header(HttpHeaders.AcceptLanguage, acceptLanguage)
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        val body = response.readBoundedBody()
        return response.toResult(body) { text ->
            decodeRoutePlan(text, request, alternativesLimit)
        }
    }

    private fun decodePhoton(body: String): List<PlaceCandidate> {
        val root = body.asJsonObject()
        if (root.text("type") != "FeatureCollection") throw MalformedResponseException()
        val features = root.requiredArray("features")
        return features.mapIndexed { index, element -> decodePhotonFeature(element.jsonObject, index) }
    }

    private fun decodePhotonFeature(feature: JsonObject, index: Int): PlaceCandidate {
        val properties = feature["properties"]?.jsonObject ?: JsonObject(emptyMap())
        val geometry = feature.requiredObject("geometry")
        if (geometry.text("type") != "Point") throw MalformedResponseException()
        val coordinate = decodeCoordinate(geometry.requiredArray("coordinates"))
        val name = properties.text("name")
        val street = properties.text("street")
        val city = properties.firstText("city", "locality", "town", "village")
        val title = firstNonBlank(name, street, city, properties.text("state"), properties.text("country"))
            ?: "Без названия"
        val subtitle = addressSubtitle(properties)
        val osmType = properties.text("osm_type")
        val osmId = properties.text("osm_id")
        val id = if (osmType != null && osmId != null) {
            "$osmType:$osmId"
        } else {
            feature.text("id") ?: "photon:$index"
        }
        return PlaceCandidate(
            id = id,
            title = title,
            subtitle = subtitle,
            coordinate = coordinate,
        )
    }

    private fun addressSubtitle(properties: JsonObject): String? {
        val street = properties.text("street")
        val houseNumber = properties.firstText("housenumber", "house_number")
        val streetLine = listOfNotNull(street, houseNumber)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
        val parts = listOfNotNull(
            streetLine,
            properties.firstText("city", "locality", "town", "village"),
            properties.text("district"),
            properties.text("state"),
            properties.firstText("postcode", "postalcode"),
            properties.text("country"),
        ).distinct()
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun decodeRoutePlan(
        body: String,
        request: RouteRequest,
        alternativesLimit: Int,
    ): RoutePlan {
        val root = body.asJsonObject()
        when (root.text("code")) {
            "NoRoute" -> throw NoRouteResponseException()
            "Ok" -> Unit
            else -> throw MalformedResponseException()
        }
        val routes = root.requiredArray("routes")
        if (routes.isEmpty()) throw MalformedResponseException()
        val alternatives = routes.take(alternativesLimit).mapIndexed { index, element ->
            decodeRouteAlternative(element.jsonObject, index, request.languageTag)
        }
        if (alternatives.isEmpty()) throw MalformedResponseException()
        return RoutePlan(request.destination, alternatives)
    }

    private fun RoutePlan.aggregateWith(other: RoutePlan, limit: Int): RoutePlan {
        val alternatives = mutableListOf<RouteAlternative>()
        (this.alternatives + other.alternatives).forEach { candidate ->
            if (alternatives.size < limit && alternatives.none { it.isEquivalentTo(candidate) }) {
                alternatives += candidate
            }
        }
        return copy(
            alternatives = alternatives.mapIndexed { index, route ->
                route.withDeterministicId(index)
            },
        )
    }

    private fun RoutePlan.withDeterministicRouteIds(limit: Int): RoutePlan = copy(
        alternatives = alternatives.take(limit).mapIndexed { index, route ->
            route.withDeterministicId(index)
        },
    )

    private fun RouteAlternative.withDeterministicId(index: Int): RouteAlternative {
        val routeId = "osrm-route-${index + 1}"
        return copy(
            id = routeId,
            maneuvers = maneuvers.mapIndexed { maneuverIndex, maneuver ->
                val maneuverId = if (maneuverIndex == maneuvers.lastIndex && maneuver.kind == ManeuverKind.ARRIVE) {
                    "$routeId-arrive"
                } else {
                    "$routeId-step-${maneuverIndex + 1}"
                }
                maneuver.copy(id = maneuverId)
            },
        )
    }

    private fun RouteAlternative.isEquivalentTo(other: RouteAlternative): Boolean {
        val distanceTolerance = maxOf(
            ROUTE_DISTANCE_TOLERANCE_METERS,
            maxOf(distanceMeters, other.distanceMeters) * ROUTE_DISTANCE_TOLERANCE_RATIO,
        )
        val durationTolerance = maxOf(
            ROUTE_DURATION_TOLERANCE_SECONDS.toDouble(),
            maxOf(durationSeconds, other.durationSeconds).toDouble() * ROUTE_DURATION_TOLERANCE_RATIO,
        )
        return abs(distanceMeters - other.distanceMeters) <= distanceTolerance &&
            abs(durationSeconds.toDouble() - other.durationSeconds.toDouble()) <= durationTolerance &&
            geometriesEquivalent(geometry, other.geometry)
    }

    private fun geometriesEquivalent(
        first: List<GeoCoordinate>,
        second: List<GeoCoordinate>,
    ): Boolean = first.all { point -> second.any { it.isWithinRouteToleranceOf(point) } } &&
        second.all { point -> first.any { it.isWithinRouteToleranceOf(point) } }

    private fun GeoCoordinate.isWithinRouteToleranceOf(other: GeoCoordinate): Boolean {
        val latitudeDelta = latitude - other.latitude
        val longitudeDelta = longitude - other.longitude
        return latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta <=
            ROUTE_GEOMETRY_TOLERANCE_DEGREES * ROUTE_GEOMETRY_TOLERANCE_DEGREES
    }

    private fun decodeRouteAlternative(
        route: JsonObject,
        index: Int,
        languageTag: String,
    ): RouteAlternative {
        val routeId = "osrm-route-${index + 1}"
        val geometryObject = route.requiredObject("geometry")
        if (geometryObject.text("type") != "LineString") throw MalformedResponseException()
        val geometry = geometryObject.requiredArray("coordinates").map { decodeCoordinate(it.jsonArray) }
        if (geometry.size < 2) throw MalformedResponseException()

        val distance = route.requiredFiniteNumber("distance")
        val duration = route.requiredFiniteNumber("duration")
        if (duration <= 0.0) throw MalformedResponseException()
        val durationSeconds = ceil(duration).toLong().takeIf { it > 0L }
            ?: throw MalformedResponseException()

        val steps = route.requiredArray("legs").flatMap { leg ->
            leg.jsonObject.requiredArray("steps").map { it.jsonObject }
        }
        if (steps.isEmpty()) throw MalformedResponseException()

        val maneuvers = steps.mapIndexed { stepIndex, step ->
            decodeManeuver(step, stepIndex, routeId, geometry, languageTag)
        }.toMutableList()
        if (maneuvers.last().kind != ManeuverKind.ARRIVE) {
            maneuvers += RouteManeuver(
                id = "$routeId-arrive",
                kind = ManeuverKind.ARRIVE,
                instruction = localizedInstruction(
                    kind = ManeuverKind.ARRIVE,
                    streetName = null,
                    languageTag = languageTag,
                    roundaboutExit = null,
                ),
                streetName = null,
                shapeIndex = geometry.lastIndex,
                distanceMeters = 0.0,
            )
        }
        return RouteAlternative(
            id = routeId,
            distanceMeters = distance,
            durationSeconds = durationSeconds,
            geometry = geometry,
            maneuvers = maneuvers,
        )
    }

    private fun decodeManeuver(
        step: JsonObject,
        stepIndex: Int,
        routeId: String,
        geometry: List<GeoCoordinate>,
        languageTag: String,
    ): RouteManeuver {
        val maneuver = step.requiredObject("maneuver")
        val type = maneuver.text("type")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: throw MalformedResponseException()
        val modifier = maneuver.text("modifier")?.lowercase()?.replace('_', ' ')
        val kind = maneuverKind(type, modifier)
        val location = decodeCoordinate(maneuver.requiredArray("location"))
        val shapeIndex = nearestShapeIndex(location, geometry)
        val streetName = step.text("name")
        return RouteManeuver(
            id = "$routeId-step-${stepIndex + 1}",
            kind = kind,
            instruction = localizedInstruction(
                kind = kind,
                streetName = streetName,
                languageTag = languageTag,
                roundaboutExit = maneuver.text("exit"),
            ),
            streetName = streetName,
            shapeIndex = shapeIndex,
            distanceMeters = step.requiredFiniteNumber("distance"),
        )
    }

    private fun maneuverKind(type: String, modifier: String?): ManeuverKind = when (type) {
        "depart" -> ManeuverKind.DEPART
        "arrive" -> ManeuverKind.ARRIVE
        "uturn", "u-turn" -> ManeuverKind.U_TURN
        "roundabout", "rotary", "exit roundabout" -> ManeuverKind.ROUNDABOUT
        "continue", "new name" -> ManeuverKind.STRAIGHT
        else -> modifierKind(modifier) ?: ManeuverKind.UNKNOWN
    }

    private fun modifierKind(modifier: String?): ManeuverKind? = when (modifier) {
        "slight left" -> ManeuverKind.SLIGHT_LEFT
        "left" -> ManeuverKind.LEFT
        "sharp left" -> ManeuverKind.SHARP_LEFT
        "slight right" -> ManeuverKind.SLIGHT_RIGHT
        "right" -> ManeuverKind.RIGHT
        "sharp right" -> ManeuverKind.SHARP_RIGHT
        "straight" -> ManeuverKind.STRAIGHT
        "uturn", "u-turn" -> ManeuverKind.U_TURN
        else -> null
    }

    private fun localizedInstruction(
        kind: ManeuverKind,
        streetName: String?,
        languageTag: String,
        roundaboutExit: String?,
    ): String {
        val street = streetName?.trim()?.takeIf { it.isNotEmpty() }
        val russian = requestLanguage(languageTag).isRussian
        return if (russian) {
            when (kind) {
                ManeuverKind.DEPART -> street?.let { "Начните движение по $it" } ?: "Начните движение"
                ManeuverKind.STRAIGHT -> street?.let { "Продолжайте движение по $it" } ?: "Продолжайте движение прямо"
                ManeuverKind.SLIGHT_LEFT -> "Поверните слегка налево" + streetSuffix(street)
                ManeuverKind.LEFT -> "Поверните налево" + streetSuffix(street)
                ManeuverKind.SHARP_LEFT -> "Резко поверните налево" + streetSuffix(street)
                ManeuverKind.SLIGHT_RIGHT -> "Поверните слегка направо" + streetSuffix(street)
                ManeuverKind.RIGHT -> "Поверните направо" + streetSuffix(street)
                ManeuverKind.SHARP_RIGHT -> "Резко поверните направо" + streetSuffix(street)
                ManeuverKind.U_TURN -> "Развернитесь" + streetSuffix(street)
                ManeuverKind.ROUNDABOUT -> roundaboutInstruction(street, roundaboutExit)
                ManeuverKind.ARRIVE -> street?.let { "Прибудьте к $it" } ?: "Вы прибыли к месту назначения"
                ManeuverKind.UNKNOWN -> street?.let { "Продолжайте движение по $it" } ?: "Продолжайте движение"
            }
        } else {
            when (kind) {
                ManeuverKind.DEPART -> street?.let { "Depart on $it" } ?: "Depart"
                ManeuverKind.STRAIGHT -> street?.let { "Continue on $it" } ?: "Continue straight"
                ManeuverKind.SLIGHT_LEFT -> "Turn slightly left" + englishStreetSuffix(street)
                ManeuverKind.LEFT -> "Turn left" + englishStreetSuffix(street)
                ManeuverKind.SHARP_LEFT -> "Turn sharply left" + englishStreetSuffix(street)
                ManeuverKind.SLIGHT_RIGHT -> "Turn slightly right" + englishStreetSuffix(street)
                ManeuverKind.RIGHT -> "Turn right" + englishStreetSuffix(street)
                ManeuverKind.SHARP_RIGHT -> "Turn sharply right" + englishStreetSuffix(street)
                ManeuverKind.U_TURN -> "Make a U-turn" + englishStreetSuffix(street)
                ManeuverKind.ROUNDABOUT -> roundaboutEnglishInstruction(street, roundaboutExit)
                ManeuverKind.ARRIVE -> street?.let { "Arrive at $it" } ?: "You have arrived"
                ManeuverKind.UNKNOWN -> street?.let { "Continue on $it" } ?: "Continue"
            }
        }
    }

    private fun roundaboutInstruction(street: String?, exit: String?): String {
        val base = exit?.let { "На круговом движении сверните на $it-й съезд" }
            ?: "Проезжайте круговое движение"
        return if (street == null) base else "$base на $street"
    }

    private fun roundaboutEnglishInstruction(street: String?, exit: String?): String {
        val base = exit?.let { "At the roundabout, take exit $it" } ?: "Enter the roundabout"
        return if (street == null) base else "$base onto $street"
    }

    private fun streetSuffix(street: String?): String = street?.let { " на $it" } ?: ""

    private fun englishStreetSuffix(street: String?): String = street?.let { " onto $it" } ?: ""

    private fun decodeCoordinate(coordinates: JsonArray): GeoCoordinate {
        if (coordinates.size < 2) throw MalformedResponseException()
        val longitude = coordinates[0].jsonPrimitive.doubleOrNull ?: throw MalformedResponseException()
        val latitude = coordinates[1].jsonPrimitive.doubleOrNull ?: throw MalformedResponseException()
        if (!longitude.isFinite() || !latitude.isFinite()) throw MalformedResponseException()
        return try {
            GeoCoordinate(latitude = latitude, longitude = longitude)
        } catch (_: IllegalArgumentException) {
            throw MalformedResponseException()
        }
    }

    private fun nearestShapeIndex(location: GeoCoordinate, geometry: List<GeoCoordinate>): Int {
        var closestIndex = 0
        var closestDistance = Double.POSITIVE_INFINITY
        geometry.forEachIndexed { index, point ->
            val latitudeDelta = point.latitude - location.latitude
            val longitudeDelta = point.longitude - location.longitude
            val distance = latitudeDelta * latitudeDelta + longitudeDelta * longitudeDelta
            if (distance < closestDistance) {
                closestDistance = distance
                closestIndex = index
            }
        }
        return closestIndex
    }

    private suspend fun HttpResponse.readBoundedBody(): BoundedBody {
        val contentLength = headers[HttpHeaders.ContentLength]?.trim()?.toLongOrNull()
        if (contentLength != null && contentLength >= MAX_RESPONSE_BYTES) {
            return BoundedBody.TooLarge
        }

        val bytes = bodyAsChannel()
            .readRemaining(MAX_RESPONSE_BYTES)
            .readByteArray()
        if (bytes.size >= MAX_RESPONSE_BYTES) {
            return BoundedBody.TooLarge
        }
        val text = bytes.decodeToString()
        return if (text.length > MAX_RESPONSE_CHARS) {
            BoundedBody.TooLarge
        } else {
            BoundedBody.Text(text)
        }
    }

    private fun <T> HttpResponse.toResult(body: BoundedBody, decode: (String) -> T): NavigationResult<T> {
        if (!status.isSuccess()) {
            return when {
            status == HttpStatusCode.TooManyRequests ->
                NavigationResult.Failure(NavigationFailure.RateLimited(retryAfterSeconds()))
            status.value in 500..599 ->
                NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
            status.value in 400..499 ->
                NavigationResult.Failure(NavigationFailure.InvalidRequest("Navigation request was rejected"))
            else -> NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
            }
        }
        if (body is BoundedBody.TooLarge) {
            return NavigationResult.Failure(NavigationFailure.MalformedResponse)
        }
        val text = body.value
        return try {
            NavigationResult.Success(decode(text))
        } catch (_: NoRouteResponseException) {
            NavigationResult.Failure(NavigationFailure.NoRoute)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            NavigationResult.Failure(NavigationFailure.MalformedResponse)
        }
    }

    private fun HttpResponse.retryAfterSeconds(): Long {
        val value = headers[HttpHeaders.RetryAfter]?.trim()
            ?: return DEFAULT_RETRY_AFTER_SECONDS
        value.toLongOrNull()?.let { return it.coerceAtLeast(1L) }
        return try {
            ((value.fromHttpToGmtDate().timestamp - GMTDate().timestamp) / 1000L)
                .coerceAtLeast(1L)
        } catch (_: Exception) {
            DEFAULT_RETRY_AFTER_SECONDS
        }
    }

    private fun requestLanguage(languageTag: String): RequestLanguage {
        val requested = languageTag.trim()
        val baseLanguage = requested.substringBefore('-').substringBefore('_').lowercase()
            .ifBlank { "default" }
        val russian = baseLanguage.equals("ru", ignoreCase = true)
        val photonLanguage = when {
            russian -> "default"
            baseLanguage in SUPPORTED_PHOTON_LANGUAGES -> baseLanguage
            else -> "default"
        }
        return RequestLanguage(
            photonLanguage = photonLanguage,
            acceptLanguage = if (russian) "ru-RU,ru" else requested.ifBlank { baseLanguage },
            isRussian = russian,
        )
    }

    private fun String.asJsonObject(): JsonObject = try {
        json.parseToJsonElement(this).jsonObject
    } catch (_: Exception) {
        throw MalformedResponseException()
    }

    private sealed interface BoundedBody {
        val value: String

        data class Text(override val value: String) : BoundedBody

        data object TooLarge : BoundedBody {
            override val value: String
                get() = error("Oversized response has no materialized body")
        }
    }

    private fun JsonObject.requiredArray(key: String): JsonArray =
        this[key]?.jsonArray ?: throw MalformedResponseException()

    private fun JsonObject.requiredObject(key: String): JsonObject =
        this[key]?.jsonObject ?: throw MalformedResponseException()

    private fun JsonObject.text(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.firstText(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { text(it) }

    private fun JsonObject.requiredFiniteNumber(key: String): Double =
        textNumber(key)?.takeIf { it.isFinite() } ?: throw MalformedResponseException()

    private fun JsonObject.textNumber(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }

    private data class RequestLanguage(
        val photonLanguage: String,
        val acceptLanguage: String,
        val isRussian: Boolean,
    )

    private class MalformedResponseException : IllegalArgumentException()

    private class NoRouteResponseException : IllegalArgumentException()

    private companion object {
        const val PHOTON_URL = "https://photon.komoot.io/api/"
        val OSRM_URLS = listOf(
            "https://routing.openstreetmap.de/routed-car/route/v1/driving",
            "https://router.project-osrm.org/route/v1/driving",
        )
        const val USER_AGENT = "Volty/0.7.4 navigation (Photon + OSM OSRM)"
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val MAX_RESPONSE_BYTES = MAX_RESPONSE_CHARS * 4L + 1L
        const val MAX_ALTERNATIVES = 3
        const val ROUTE_DISTANCE_TOLERANCE_METERS = 25.0
        val WHITESPACE = Regex("\\s+")
        const val ROUTE_DISTANCE_TOLERANCE_RATIO = 0.02
        const val ROUTE_DURATION_TOLERANCE_SECONDS = 5L
        const val ROUTE_DURATION_TOLERANCE_RATIO = 0.05
        const val ROUTE_GEOMETRY_TOLERANCE_DEGREES = 0.0002
        const val DEFAULT_RETRY_AFTER_SECONDS = 60L
        val SUPPORTED_PHOTON_LANGUAGES = setOf("default", "de", "en", "fr", "it")
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            isLenient = false
        }
    }
}

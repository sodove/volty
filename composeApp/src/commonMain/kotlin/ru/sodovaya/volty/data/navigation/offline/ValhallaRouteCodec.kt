package ru.sodovaya.volty.data.navigation.offline

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.routing.RouteDiversityPolicy
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle

/** Valhalla JSON boundary shared by the Android wrapper and host-side fixtures. */
object ValhallaRouteCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun encodeRequest(request: RouteRequest): String = buildJsonObject {
        put("locations", buildJsonArray {
            add(location(request.origin))
            add(location(request.destination.coordinate))
        })
        // This is an engine costing name, not a Volty transport/profile selector.
        put("costing", "auto")
        put("alternates", (request.alternativesLimit - 1).coerceIn(0, MAX_ALTERNATES))
        put("costing_options", buildJsonObject {
            put("auto", buildJsonObject {
                put("top_speed", request.preferences.declaredTopSpeedKph)
                put("use_highways", request.style.highwayPreference())
                put("use_tolls", if (request.preferences.avoidTolls) 0.0 else 1.0)
                put("use_ferry", if (request.preferences.avoidFerries) 0.0 else 1.0)
                put("use_unpaved", if (request.preferences.avoidUnpaved) 0.0 else 1.0)
            })
        })
        put("directions_options", buildJsonObject {
            put("units", "kilometers")
            put("language", request.languageTag)
        })
    }.toString()

    fun decodeRoutePlan(
        body: String,
        request: RouteRequest,
    ): NavigationResult<RoutePlan> = try {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["trip"] == null) {
            val error = root["error"]?.jsonPrimitive?.content.orEmpty().lowercase()
            return if (error.contains("no route") || root["error_code"]?.jsonPrimitive?.intOrNull == NO_ROUTE_ERROR) {
                NavigationResult.Failure(NavigationFailure.NoRoute)
            } else {
                NavigationResult.Failure(NavigationFailure.MalformedResponse)
            }
        }
        val tripObjects = buildList {
            add(root["trip"]?.jsonObject ?: return@buildList)
            root["alternates"]?.jsonArray.orEmpty().forEach { alternate ->
                val alternateObject = alternate.jsonObject
                add(alternateObject["trip"]?.jsonObject ?: alternateObject)
            }
        }
        val candidates = tripObjects.mapIndexedNotNull { index, trip ->
            decodeAlternative(trip, request.languageTag, "offline-route-${index + 1}")
        }
        if (candidates.isEmpty()) return NavigationResult.Failure(NavigationFailure.MalformedResponse)
        val selected = RouteDiversityPolicy.select(
            candidates = candidates,
            limit = request.alternativesLimit.coerceIn(1, MAX_ROUTES),
        )
        NavigationResult.Success(RoutePlan(request.destination, selected))
    } catch (_: SerializationException) {
        NavigationResult.Failure(NavigationFailure.MalformedResponse)
    } catch (_: IllegalArgumentException) {
        NavigationResult.Failure(NavigationFailure.MalformedResponse)
    }

    private fun decodeAlternative(
        trip: JsonObject,
        languageTag: String,
        routeId: String,
    ): RouteAlternative? {
        val summary = trip["summary"]?.jsonObject ?: return null
        val distanceMeters = summary.number("length")?.times(1_000.0)?.takeIf { it > 0.0 } ?: return null
        val durationSeconds = summary.number("time")?.let { kotlin.math.ceil(it).toLong() }?.takeIf { it > 0L }
            ?: return null
        val legs = trip["legs"]?.jsonArray?.takeIf { it.isNotEmpty() } ?: return null
        val geometry = mutableListOf<GeoCoordinate>()
        val maneuvers = mutableListOf<DecodedManeuver>()
        legs.forEach { element ->
            val leg = element.jsonObject
            val legGeometry = decodePolyline6(leg["shape"]?.jsonPrimitive?.content ?: return null)
            if (legGeometry.size < 2) return null
            val geometryOffset = geometry.size
            geometry += if (geometry.isEmpty()) legGeometry else legGeometry.drop(1)
            leg["maneuvers"]?.jsonArray.orEmpty().forEach { maneuverElement ->
                val maneuver = maneuverElement.jsonObject
                val shapeIndex = maneuver["begin_shape_index"]?.jsonPrimitive?.intOrNull
                    ?.let { it + geometryOffset - if (geometryOffset > 0) 1 else 0 }
                    ?: return@forEach
                val kind = maneuver["type"]?.jsonPrimitive?.intOrNull?.toManeuverKind()
                    ?: ManeuverKind.UNKNOWN
                maneuvers += DecodedManeuver(
                    kind = kind,
                    instruction = maneuver.instruction(languageTag, kind),
                    streetName = maneuver["street_names"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.content,
                    shapeIndex = shapeIndex,
                    distanceMeters = maneuver.number("length")?.times(1_000.0)?.coerceAtLeast(0.0) ?: 0.0,
                )
            }
        }
        val safeManeuvers = maneuvers.toMutableList()
        if (safeManeuvers.firstOrNull()?.kind != ManeuverKind.DEPART) {
            safeManeuvers.add(0, DecodedManeuver(ManeuverKind.DEPART, localized(ManeuverKind.DEPART, languageTag), null, 0, 0.0))
        }
        if (safeManeuvers.lastOrNull()?.kind != ManeuverKind.ARRIVE) {
            safeManeuvers += DecodedManeuver(ManeuverKind.ARRIVE, localized(ManeuverKind.ARRIVE, languageTag), null, geometry.lastIndex, 0.0)
        }
        return RouteAlternative(
            id = routeId,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            geometry = geometry,
            maneuvers = safeManeuvers.mapIndexed { index, maneuver ->
                RouteManeuver(
                    id = "$routeId-step-${index + 1}",
                    kind = maneuver.kind,
                    instruction = maneuver.instruction,
                    streetName = maneuver.streetName,
                    shapeIndex = maneuver.shapeIndex.coerceIn(0, geometry.lastIndex),
                    distanceMeters = maneuver.distanceMeters,
                )
            },
        )
    }

    /** Valhalla uses the six-decimal variant of the standard encoded polyline. */
    fun decodePolyline6(encoded: String): List<GeoCoordinate> {
        if (encoded.isEmpty()) return emptyList()
        val points = mutableListOf<GeoCoordinate>()
        var index = 0
        var latitude = 0
        var longitude = 0
        while (index < encoded.length) {
            val latitudeDelta = nextValue(encoded, index).also { index = it.nextIndex }.value
            val longitudeDelta = nextValue(encoded, index).also { index = it.nextIndex }.value
            latitude += latitudeDelta
            longitude += longitudeDelta
            points += GeoCoordinate(latitude / 1_000_000.0, longitude / 1_000_000.0)
        }
        return points
    }

    private fun nextValue(encoded: String, start: Int): DecodedValue {
        var index = start
        var result = 0
        var shift = 0
        while (true) {
            if (index >= encoded.length || shift > 30) throw SerializationException("Invalid polyline")
            val value = encoded[index++].code - 63
            if (value !in 0..63) throw SerializationException("Invalid polyline character")
            result = result or ((value and 0x1f) shl shift)
            if (value < 0x20) break
            shift += 5
        }
        return DecodedValue(if ((result and 1) != 0) -(result shr 1) - 1 else result shr 1, index)
    }

    private fun location(point: GeoCoordinate) = buildJsonObject {
        put("lat", point.latitude)
        put("lon", point.longitude)
        put("type", "break")
    }

    private fun JsonObject.number(name: String): Double? =
        this[name]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.instruction(languageTag: String, kind: ManeuverKind): String =
        this["instruction"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: this["verbal_pre_transition_instruction"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: localized(kind, languageTag)

    private fun Int.toManeuverKind(): ManeuverKind = when (this) {
        1 -> ManeuverKind.DEPART
        2 -> ManeuverKind.ARRIVE
        3, 12, 13, 15, 16, 17, 18 -> ManeuverKind.STRAIGHT
        4, 11 -> ManeuverKind.SLIGHT_RIGHT
        5, 10 -> ManeuverKind.RIGHT
        6 -> ManeuverKind.SHARP_RIGHT
        7, 8 -> ManeuverKind.U_TURN
        9 -> ManeuverKind.SHARP_LEFT
        14 -> ManeuverKind.LEFT
        19 -> ManeuverKind.SLIGHT_LEFT
        24, 25, 26, 27 -> ManeuverKind.ROUNDABOUT
        else -> ManeuverKind.UNKNOWN
    }

    private fun localized(kind: ManeuverKind, languageTag: String): String = if (
        languageTag.lowercase().startsWith("ru")
    ) {
        when (kind) {
            ManeuverKind.DEPART -> "Начните движение"
            ManeuverKind.ARRIVE -> "Вы прибыли"
            ManeuverKind.STRAIGHT -> "Продолжайте движение прямо"
            ManeuverKind.LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT -> "Поверните налево"
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT -> "Поверните направо"
            ManeuverKind.U_TURN -> "Развернитесь"
            ManeuverKind.ROUNDABOUT -> "На круговом движении"
            ManeuverKind.UNKNOWN -> "Следуйте по маршруту"
        }
    } else {
        when (kind) {
            ManeuverKind.DEPART -> "Depart"
            ManeuverKind.ARRIVE -> "You have arrived"
            ManeuverKind.STRAIGHT -> "Continue straight"
            ManeuverKind.LEFT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SHARP_LEFT -> "Turn left"
            ManeuverKind.RIGHT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_RIGHT -> "Turn right"
            ManeuverKind.U_TURN -> "Make a U-turn"
            ManeuverKind.ROUNDABOUT -> "At the roundabout"
            ManeuverKind.UNKNOWN -> "Continue"
        }
    }

    private data class DecodedValue(val value: Int, val nextIndex: Int)

    private data class DecodedManeuver(
        val kind: ManeuverKind,
        val instruction: String,
        val streetName: String?,
        val shapeIndex: Int,
        val distanceMeters: Double,
    )

    private const val MAX_ALTERNATES = 2
    private const val MAX_ROUTES = 3
    private const val NO_ROUTE_ERROR = 171
}

private fun RouteStyle.highwayPreference(): Double = when (this) {
    RouteStyle.FAST_WITH_HIGHWAYS -> 1.0
    RouteStyle.FAST_WITHOUT_HIGHWAYS -> 0.0
    RouteStyle.CURVY -> 0.4
    RouteStyle.MAX_CURVY_TOURING -> 0.15
}

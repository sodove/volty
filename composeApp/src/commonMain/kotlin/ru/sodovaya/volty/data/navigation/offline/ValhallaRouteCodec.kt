package ru.sodovaya.volty.data.navigation.offline

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
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
import ru.sodovaya.volty.domain.navigation.routing.RouteProfilePolicy
import ru.sodovaya.volty.domain.navigation.routing.RouteStyle

/** Valhalla JSON boundary shared by the Android wrapper and host-side fixtures. */
object ValhallaRouteCodec {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun encodeRequest(request: RouteRequest): String = encodeRequest(
        request = request,
        alternativesLimit = request.alternativesLimit,
    )

    /** Encodes one deliberately small candidate request for iterative routing. */
    fun encodeRequest(
        request: RouteRequest,
        alternativesLimit: Int,
        avoidLocations: List<GeoCoordinate> = emptyList(),
        costing: ValhallaCosting = ValhallaCosting.AUTO,
    ): String = buildJsonObject {
        put("locations", buildJsonArray {
            add(location(request.origin))
            add(location(request.destination.coordinate))
        })
        // This is an engine costing name, not a Volty transport/profile selector.
        put("costing", costing.wireName)
        put("alternates", (alternativesLimit - 1).coerceIn(0, MAX_ALTERNATES))
        put("costing_options", buildJsonObject {
            put(costing.wireName, costing.options(request))
        })
        if (avoidLocations.isNotEmpty()) {
            put("avoid_locations", buildJsonArray {
                avoidLocations.forEach { add(avoidLocation(it)) }
            })
        }
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
        val safeTripObjects = if (RouteProfilePolicy.requiresHighwayFreeRoute(request)) {
            // use_highways is a soft factor. The response summary is the
            // provider's actual route-level highway verdict, so fail closed
            // when it is true or absent for a request that forbids highways.
            tripObjects.filter { trip ->
                trip["summary"]?.jsonObject?.get("has_highway")
                    ?.jsonPrimitive?.booleanOrNull == false
            }
        } else {
            tripObjects
        }
        if (safeTripObjects.isEmpty()) return NavigationResult.Failure(NavigationFailure.NoRoute)
        val candidates = safeTripObjects.mapIndexedNotNull { index, trip ->
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

    /**
     * Decodes provider facts needed for route safety and profile auditing.
     *
     * The regular [RouteAlternative] intentionally does not grow Valhalla fields. In particular,
     * a normal route response exposes `rough`, `toll`, `ferry`, `travel_mode`, and `travel_type`
     * on maneuvers, but it does not provide a reliable surface/access/steps verdict. Those latter
     * checks must come from a separate corpus/trace inspection instead of being guessed here.
     */
    fun decodeRouteEvidence(body: String): NavigationResult<ValhallaRouteEvidence> = try {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["trip"] == null) {
            val error = root["error"]?.jsonPrimitive?.content.orEmpty().lowercase()
            return if (error.contains("no route") || root["error_code"]?.jsonPrimitive?.intOrNull == NO_ROUTE_ERROR) {
                NavigationResult.Failure(NavigationFailure.NoRoute)
            } else {
                NavigationResult.Failure(NavigationFailure.MalformedResponse)
            }
        }
        val maneuvers = root["trip"]?.jsonObject?.get("legs")?.jsonArray.orEmpty()
            .flatMap { it.jsonObject["maneuvers"]?.jsonArray.orEmpty() }
            .map { it.jsonObject }
        if (maneuvers.isEmpty()) return NavigationResult.Failure(NavigationFailure.MalformedResponse)

        val travelModes = mutableSetOf<String>()
        val travelTypes = mutableSetOf<String>()
        var hasUnknownTravelModes = false
        maneuvers.forEach { maneuver ->
            val mode = maneuver["travel_mode"]?.jsonPrimitive?.content
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
            if (mode == null || mode !in KNOWN_TRAVEL_MODES) {
                hasUnknownTravelModes = true
            } else {
                travelModes += mode
            }
            maneuver["travel_type"]?.jsonPrimitive?.content
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotEmpty() }
                ?.let(travelTypes::add)
        }
        NavigationResult.Success(
            ValhallaRouteEvidence(
                travelModes = travelModes,
                travelTypes = travelTypes,
                hasRoughSegments = maneuvers.any { it["rough"]?.jsonPrimitive?.booleanOrNull == true },
                hasTollSegments = maneuvers.any { it["toll"]?.jsonPrimitive?.booleanOrNull == true },
                hasFerrySegments = maneuvers.any { it["ferry"]?.jsonPrimitive?.booleanOrNull == true },
                maneuverCount = maneuvers.size,
                hasUnknownTravelModes = hasUnknownTravelModes,
            ),
        )
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

    private fun avoidLocation(point: GeoCoordinate) = buildJsonObject {
        put("lat", point.latitude)
        put("lon", point.longitude)
    }

    private fun JsonObject.number(name: String): Double? =
        this[name]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.instruction(languageTag: String, kind: ManeuverKind): String =
        this["instruction"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: this["verbal_pre_transition_instruction"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: localized(kind, languageTag)

    private fun Int.toManeuverKind(): ManeuverKind = when (this) {
        // Pinned Valhalla 3.6.3 directions.proto. Types 2 and 3 are
        // start-right/start-left, not arrival; arrival is 4..6.
        1, 2, 3 -> ManeuverKind.DEPART
        4, 5, 6 -> ManeuverKind.ARRIVE
        7, 8, 17, 22, 25, 28, 29 -> ManeuverKind.STRAIGHT
        9 -> ManeuverKind.SLIGHT_RIGHT
        10, 18, 20, 37 -> ManeuverKind.RIGHT
        11 -> ManeuverKind.SHARP_RIGHT
        12, 13 -> ManeuverKind.U_TURN
        14 -> ManeuverKind.SHARP_LEFT
        15 -> ManeuverKind.LEFT
        16, 19, 21, 24, 38 -> ManeuverKind.SLIGHT_LEFT
        23 -> ManeuverKind.SLIGHT_RIGHT
        26, 27 -> ManeuverKind.ROUNDABOUT
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
    private val KNOWN_TRAVEL_MODES = setOf("drive", "pedestrian", "bicycle", "transit")
}

/** Provider evidence kept outside the rider-facing route model and UI. */
data class ValhallaRouteEvidence(
    val travelModes: Set<String>,
    val travelTypes: Set<String>,
    val hasRoughSegments: Boolean,
    val hasTollSegments: Boolean,
    val hasFerrySegments: Boolean,
    val maneuverCount: Int,
    val hasUnknownTravelModes: Boolean,
)

enum class ValhallaCosting(val wireName: String) {
    AUTO("auto"),
    MOTORCYCLE("motorcycle"),
    BICYCLE("bicycle"),
    PEDESTRIAN("pedestrian"),
}

private fun RouteStyle.highwayPreference(): Double = when (this) {
    RouteStyle.FAST_WITH_HIGHWAYS -> 1.0
    RouteStyle.FAST_WITHOUT_HIGHWAYS -> 0.0
    RouteStyle.CURVY -> 0.15
    RouteStyle.MAX_CURVY_TOURING -> 0.0
}

private fun RouteRequest.highwayPreference(): Double = if (
    preferences.declaredTopSpeedKph <= RouteProfilePolicy.LOW_SPEED_MAX_KPH
) {
    // A 20–30 km/h rider must never inherit auto's highway bias, including
    // when a specialised costing fails and generic auto is retried.
    0.0
} else {
    style.highwayPreference()
}

private fun RouteStyle.trackPreference(): Double = when (this) {
    RouteStyle.FAST_WITH_HIGHWAYS,
    RouteStyle.FAST_WITHOUT_HIGHWAYS,
    -> 0.0
    RouteStyle.CURVY -> 0.35
    RouteStyle.MAX_CURVY_TOURING -> 0.8
}

private fun RouteStyle.livingStreetPreference(): Double = when (this) {
    RouteStyle.FAST_WITH_HIGHWAYS -> 0.0
    RouteStyle.FAST_WITHOUT_HIGHWAYS -> 0.25
    RouteStyle.CURVY -> 0.45
    RouteStyle.MAX_CURVY_TOURING -> 0.65
}

private fun ValhallaCosting.options(request: RouteRequest): JsonObject = when (this) {
    ValhallaCosting.AUTO -> buildJsonObject {
        put("top_speed", request.preferences.declaredTopSpeedKph)
        put("use_highways", request.highwayPreference())
        put("use_tracks", request.style.trackPreference())
        put("use_living_streets", request.style.livingStreetPreference())
        // Valhalla 3.6.3 has no road-curvature signal. Keep the generic style
        // contract honest: styles tune highway willingness, while actual
        // diversity comes from iterative avoidance and geometry scoring.
        put("use_distance", 0.0)
        put("shortest", false)
        put("ignore_restrictions", false)
        put("ignore_access", false)
        put("ignore_oneways", false)
        put("ignore_closures", false)
        put("use_tolls", if (request.preferences.avoidTolls) 0.0 else 0.5)
        put("use_ferry", if (request.preferences.avoidFerries) 0.0 else 0.5)
        put("exclude_unpaved", request.preferences.avoidUnpaved)
    }
    ValhallaCosting.MOTORCYCLE -> buildJsonObject {
        put("top_speed", request.preferences.declaredTopSpeedKph)
        // The pinned Valhalla 3.6.3 motorcycle costing parses the plural key.
        put("use_highways", request.highwayPreference())
        put("use_trails", request.motorcycleTrailsPreference())
        put("use_tracks", request.motorcycleTracksPreference())
        put("use_tolls", if (request.preferences.avoidTolls) 0.0 else 0.5)
        put("use_ferry", if (request.preferences.avoidFerries) 0.0 else 0.5)
        put("exclude_unpaved", request.preferences.avoidUnpaved)
    }
    ValhallaCosting.BICYCLE -> buildJsonObject {
        put("cycling_speed", request.preferences.declaredTopSpeedKph.coerceIn(5, 60))
        put("bicycle_type", "Hybrid")
        put("use_roads", request.bicycleRoadPreference())
        put("use_hills", 0.5)
        put("avoid_bad_surfaces", if (request.preferences.avoidUnpaved) 1.0 else 0.25)
        put("use_ferry", if (request.preferences.avoidFerries) 0.0 else 0.5)
    }
    ValhallaCosting.PEDESTRIAN -> buildJsonObject {
        put("walking_speed", request.preferences.declaredTopSpeedKph.coerceIn(5, 25))
        put("walkway_factor", 0.65)
        put("alley_factor", 2.0)
        put("driveway_factor", 5.0)
        put("step_penalty", 180)
        put("use_ferry", if (request.preferences.avoidFerries) 0.0 else 0.5)
    }
}

private fun RouteRequest.motorcycleTrailsPreference(): Double = when {
    preferences.declaredTopSpeedKph <= RouteProfilePolicy.LOW_SPEED_MAX_KPH -> 0.0
    style == RouteStyle.MAX_CURVY_TOURING -> if (preferences.declaredTopSpeedKph <= 60) 0.8 else 0.6
    style == RouteStyle.CURVY -> if (preferences.declaredTopSpeedKph <= 60) 0.55 else 0.35
    else -> 0.0
}

private fun RouteRequest.motorcycleTracksPreference(): Double = when {
    preferences.declaredTopSpeedKph <= RouteProfilePolicy.LOW_SPEED_MAX_KPH -> 0.0
    style == RouteStyle.MAX_CURVY_TOURING -> if (preferences.declaredTopSpeedKph <= 60) 0.75 else 0.5
    style == RouteStyle.CURVY -> if (preferences.declaredTopSpeedKph <= 60) 0.4 else 0.25
    else -> 0.0
}

private fun RouteRequest.bicycleRoadPreference(): Double = when {
    preferences.declaredTopSpeedKph <= RouteProfilePolicy.LOW_SPEED_MAX_KPH -> 0.0
    style == RouteStyle.FAST_WITH_HIGHWAYS -> 1.0
    style == RouteStyle.FAST_WITHOUT_HIGHWAYS -> 0.15
    style == RouteStyle.CURVY -> 0.1
    else -> 0.0
}

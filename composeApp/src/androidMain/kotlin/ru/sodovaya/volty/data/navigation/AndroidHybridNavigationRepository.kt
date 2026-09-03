package ru.sodovaya.volty.data.navigation

import android.content.Context
import android.util.Log
import btools.mapaccess.OsmNode
import btools.router.FormatJson
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRoutingPackageManager
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.RouteManeuver
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.offline.OfflineCoverageResult
import ru.sodovaya.volty.domain.navigation.offline.OfflineRouteCalculationPolicy
import ru.sodovaya.volty.domain.navigation.offline.OfflineRoutingPolicy
import ru.sodovaya.volty.domain.navigation.offline.BRouterRouteProfilePolicy
import ru.sodovaya.volty.domain.navigation.routing.RouteDiversityPolicy
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Routes locally when the bundled/downloaded BRouter package covers both
 * endpoints, then falls back to the public OSM routers. Search remains online
 * because this class intentionally does not pretend that a routing graph is a
 * geocoder.
 */
class AndroidHybridNavigationRepository(
    private val online: NavigationRepository,
    private val packageManager: AndroidOfflineRoutingPackageManager,
    context: Context,
) : NavigationRepository {
    private val applicationContext = context.applicationContext

    override suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<ru.sodovaya.volty.domain.navigation.PlaceCandidate>> =
        online.search(query, near, languageTag)

    override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
        withContext(Dispatchers.IO) { ensureBundledPackage() }
        val manifest = packageManager.activeManifest
        val packageDirectory = packageManager.activePackageDirectory
        if (manifest != null && packageDirectory != null &&
            OfflineRoutingPolicy.coverage(
                manifest = manifest,
                origin = request.origin,
                destination = request.destination.coordinate,
            ) is OfflineCoverageResult.Covered
        ) {
            when (val local = routeOffline(request, packageDirectory)) {
                is NavigationResult.Success -> return local
                is NavigationResult.Failure -> Unit
            }
        }
        return online.routes(request)
    }

    private fun ensureBundledPackage() {
        runCatching {
            packageManager.installBundledAssets(applicationContext.assets)
        }
    }

    private suspend fun routeOffline(
        request: RouteRequest,
        packageDirectory: java.io.File,
    ): NavigationResult<RoutePlan> = withContext(Dispatchers.Default) {
        try {
            val budget = OfflineRouteCalculationPolicy.routeBudget(request.alternativesLimit)
            // BRouter calculates one alternative per RoutingEngine instance. Run the
            // bounded set concurrently so diversity costs roughly one route latency,
            // not three sequential route latencies.
            val candidates = coroutineScope {
                (0 until budget.maxAlternatives).map { alternativeIndex ->
                    async {
                        routeWithBRouter(
                            request = request,
                            packageDirectory = packageDirectory,
                            alternativeIndex = alternativeIndex,
                            maxRuntimeMillis = budget.maxRuntimeMillis,
                        )
                    }
                }.awaitAll().filterNotNull()
            }
            val alternatives = if (candidates.isEmpty()) {
                emptyList()
            } else {
                RouteDiversityPolicy.select(candidates, candidates.size.coerceAtMost(budget.maxAlternatives))
                    .mapIndexed { index, route -> route.withDeterministicId(index) }
            }
            if (alternatives.isEmpty()) {
                NavigationResult.Failure(NavigationFailure.NoRoute)
            } else {
                NavigationResult.Success(RoutePlan(request.destination, alternatives))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The online implementation can still produce a route when an
            // installed package is incomplete or does not contain a usable tile.
            Log.e(TAG, "Offline BRouter route failed", error)
            NavigationResult.Failure(NavigationFailure.Offline)
        }
    }

    private fun routeWithBRouter(
        request: RouteRequest,
        packageDirectory: java.io.File,
        alternativeIndex: Int,
        maxRuntimeMillis: Long,
    ): RouteAlternative? {
        val routingContext = RoutingContext().apply {
            localFunction = java.io.File(packageDirectory, PROFILE_FILE).absolutePath
            keyValues = BRouterRouteProfilePolicy
                .overrides(request.style, request.preferences)
                .asKeyValues()
            outputFormat = "json"
            turnInstructionMode = 1
            memoryclass = 64
            setAlternativeIdx(alternativeIndex)
        }
        val waypoints = arrayListOf(
            waypoint(request.origin, "from"),
            waypoint(request.destination.coordinate, request.destination.title),
        )
        val engine = RoutingEngine(
            null,
            null,
            packageDirectory,
            waypoints,
            routingContext,
            RoutingEngine.BROUTER_ENGINEMODE_ROUTING,
        ).apply {
            quite = true
        }
        engine.doRun(maxRuntimeMillis)
        val foundTrack = engine.getFoundTrack()
        val error = engine.getErrorMessage()
        if (error != null) {
            Log.w(TAG, "BRouter returned no route: $error")
        }
        val track = foundTrack?.takeIf { it.nodes != null && it.nodes.isNotEmpty() && error == null }
            ?: return null
        val json = FormatJson(routingContext).format(track)
        return decodeAlternative(json, request.languageTag, alternativeIndex)
    }

    private fun waypoint(coordinate: GeoCoordinate, name: String): OsmNodeNamed =
        OsmNodeNamed(
            OsmNode(
                // BRouter's internal node grid is shifted to positive world
                // coordinates: longitude [0, 360) and latitude [0, 180).
                // Passing raw GPS degrees makes it look for e.g. W120_S35.rd5
                // instead of the E60_N55 tile covering Yekaterinburg.
                (coordinate.longitude + BROUTER_LONGITUDE_OFFSET).toMicroDegrees(),
                (coordinate.latitude + BROUTER_LATITUDE_OFFSET).toMicroDegrees(),
            ),
        ).also { it.name = name }

    private fun decodeAlternative(
        jsonText: String,
        languageTag: String,
        alternativeIndex: Int,
    ): RouteAlternative? {
        val root = Json.parseToJsonElement(jsonText).jsonObject
        val feature = root["features"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val properties = feature["properties"]?.jsonObject ?: return null
        val geometry = feature["geometry"]?.jsonObject ?: return null
        if (geometry["type"]?.jsonPrimitive?.content != "LineString") return null
        val coordinates = geometry["coordinates"]?.jsonArray ?: return null
        val routeGeometry = coordinates.mapNotNull { element ->
            val point = element as? JsonArray ?: return@mapNotNull null
            if (point.size < 2) return@mapNotNull null
            val longitude = point[0].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            val latitude = point[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null
            runCatching { GeoCoordinate(latitude, longitude) }.getOrNull()
        }
        if (routeGeometry.size < 2) return null

        val distanceMeters = properties.number("track-length") ?: return null
        val durationSeconds = properties.number("total-time")
            ?.let { ceil(it).toLong() }
            ?.takeIf { it > 0L }
            ?: return null
        val routeId = "offline-route-${alternativeIndex + 1}"
        val maneuvers = decodeManeuvers(
            properties["voicehints"]?.jsonArray,
            routeGeometry,
            languageTag,
            routeId,
        ).toMutableList()
        if (maneuvers.isEmpty() || maneuvers.first().kind != ManeuverKind.DEPART) {
            maneuvers.add(
                0,
                RouteManeuver(
                    id = "$routeId-depart",
                    kind = ManeuverKind.DEPART,
                    instruction = localizedInstruction(ManeuverKind.DEPART, languageTag),
                    streetName = null,
                    shapeIndex = 0,
                    distanceMeters = 0.0,
                ),
            )
        }
        if (maneuvers.lastOrNull()?.kind != ManeuverKind.ARRIVE) {
            maneuvers += RouteManeuver(
                id = "$routeId-arrive",
                kind = ManeuverKind.ARRIVE,
                instruction = localizedInstruction(ManeuverKind.ARRIVE, languageTag),
                streetName = null,
                shapeIndex = routeGeometry.lastIndex,
                distanceMeters = 0.0,
            )
        }
        return RouteAlternative(
            id = routeId,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            geometry = routeGeometry,
            maneuvers = maneuvers.mapIndexed { index, maneuver ->
                maneuver.copy(
                    id = if (maneuver.kind == ManeuverKind.ARRIVE) {
                        "$routeId-arrive"
                    } else {
                        "$routeId-step-${index + 1}"
                    },
                )
            },
        )
    }

    private fun decodeManeuvers(
        hints: JsonArray?,
        geometry: List<GeoCoordinate>,
        languageTag: String,
        routeId: String,
    ): List<RouteManeuver> = hints.orEmpty().mapIndexedNotNull { index, element ->
        val hint = element as? JsonArray ?: return@mapIndexedNotNull null
        if (hint.size < 4) return@mapIndexedNotNull null
        val shapeIndex = hint[0].jsonPrimitive.intOrNull
            ?.coerceIn(0, geometry.lastIndex)
            ?: return@mapIndexedNotNull null
        val command = hint[1].jsonPrimitive.intOrNull ?: return@mapIndexedNotNull null
        val distanceMeters = hint[3].jsonPrimitive.doubleOrNull
            ?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return@mapIndexedNotNull null
        val kind = command.toManeuverKind()
        RouteManeuver(
            id = "$routeId-step-${index + 1}",
            kind = kind,
            instruction = localizedInstruction(kind, languageTag),
            streetName = null,
            shapeIndex = shapeIndex,
            distanceMeters = distanceMeters,
        )
    }.filter { it.kind != ManeuverKind.ARRIVE }

    private fun localizedInstruction(kind: ManeuverKind, languageTag: String): String {
        val russian = languageTag.lowercase().startsWith("ru")
        if (!russian) {
            return when (kind) {
                ManeuverKind.DEPART -> "Depart"
                ManeuverKind.STRAIGHT -> "Continue straight"
                ManeuverKind.SLIGHT_LEFT -> "Turn slightly left"
                ManeuverKind.LEFT -> "Turn left"
                ManeuverKind.SHARP_LEFT -> "Turn sharply left"
                ManeuverKind.SLIGHT_RIGHT -> "Turn slightly right"
                ManeuverKind.RIGHT -> "Turn right"
                ManeuverKind.SHARP_RIGHT -> "Turn sharply right"
                ManeuverKind.U_TURN -> "Make a U-turn"
                ManeuverKind.ROUNDABOUT -> "At the roundabout"
                ManeuverKind.ARRIVE -> "You have arrived"
                ManeuverKind.UNKNOWN -> "Continue"
            }
        }
        return when (kind) {
            ManeuverKind.DEPART -> "Начните движение"
            ManeuverKind.STRAIGHT -> "Продолжайте движение прямо"
            ManeuverKind.SLIGHT_LEFT -> "Плавно налево"
            ManeuverKind.LEFT -> "Поверните налево"
            ManeuverKind.SHARP_LEFT -> "Резко налево"
            ManeuverKind.SLIGHT_RIGHT -> "Плавно направо"
            ManeuverKind.RIGHT -> "Поверните направо"
            ManeuverKind.SHARP_RIGHT -> "Резко направо"
            ManeuverKind.U_TURN -> "Развернитесь"
            ManeuverKind.ROUNDABOUT -> "На круговом движении"
            ManeuverKind.ARRIVE -> "Вы прибыли к месту назначения"
            ManeuverKind.UNKNOWN -> "Следуйте по маршруту"
        }
    }

    private fun JsonObject.number(name: String): Double? =
        this[name]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 }

    private fun RouteAlternative.withDeterministicId(index: Int): RouteAlternative {
        val routeId = "offline-route-${index + 1}"
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

    private fun Int.toManeuverKind(): ManeuverKind = when (this) {
        1 -> ManeuverKind.STRAIGHT
        2 -> ManeuverKind.LEFT
        3 -> ManeuverKind.SLIGHT_LEFT
        4 -> ManeuverKind.SHARP_LEFT
        5 -> ManeuverKind.RIGHT
        6 -> ManeuverKind.SLIGHT_RIGHT
        7 -> ManeuverKind.SHARP_RIGHT
        8 -> ManeuverKind.SLIGHT_LEFT
        9 -> ManeuverKind.SLIGHT_RIGHT
        10, 11, 15 -> ManeuverKind.U_TURN
        13, 14 -> ManeuverKind.ROUNDABOUT
        else -> ManeuverKind.UNKNOWN
    }

    private fun Double.toMicroDegrees(): Int = (this * 1_000_000.0).roundToInt()

    private companion object {
        const val TAG = "VoltyOfflineRouting"
        const val BROUTER_LONGITUDE_OFFSET = 180.0
        const val BROUTER_LATITUDE_OFFSET = 90.0
        const val PROFILE_FILE = "volty.brf"
    }
}

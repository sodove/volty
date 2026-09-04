package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
import com.valhalla.valhalla.Valhalla
import com.valhalla.valhalla.config.ValhallaConfigFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRegionPackageStore.InstalledOfflineRegion
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.ManeuverKind
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.RouteAlternative
import ru.sodovaya.volty.domain.navigation.region.OfflineGeocoderRequest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionRuntime
import ru.sodovaya.volty.domain.navigation.routing.RouteAlternativePolicy
import ru.sodovaya.volty.domain.navigation.routing.RouteDiversityPolicy
import ru.sodovaya.volty.domain.navigation.routing.RouteProfile
import ru.sodovaya.volty.domain.navigation.routing.RouteProfilePolicy

/**
 * Android bridge for the published Valhalla Mobile AAR.
 *
 * The engine and config factory come from the published Android AAR. The
 * important detail is that the AAR owns the config schema: do not pass the
 * server/CLI JSON to its legacy string constructor. Build the config through
 * the version-matched factory and use the Context constructor exercised by the
 * AAR smoke harness. Keeping these calls typed also prevents R8 from
 * obfuscating classes that were previously looked up by string name.
 */
class AndroidOfflineValhallaRuntime(
    private val packageStore: AndroidOfflineRegionPackageStore,
    context: Context,
) : OfflineRegionRuntime {
    private val applicationContext = context.applicationContext

    override suspend fun search(
        regionId: String,
        request: OfflineGeocoderRequest,
    ): NavigationResult<List<PlaceCandidate>> = withContext(Dispatchers.IO) {
        val installed = packageStore.active(regionId)
            ?: return@withContext NavigationResult.Failure(NavigationFailure.Offline)
        AndroidOfflineFtsGeocoder(installed.searchDatabase, regionId).search(request)
    }

    override suspend fun routes(
        regionId: String,
        request: RouteRequest,
    ): NavigationResult<RoutePlan> = withContext(Dispatchers.Default) {
        val installed = packageStore.active(regionId)
            ?: return@withContext NavigationResult.Failure(NavigationFailure.Offline)
        try {
            invokeValhalla(installed, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            return@withContext NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: RuntimeException) {
            return@withContext NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }
    }

    private fun invokeValhalla(
        installed: InstalledOfflineRegion,
        request: RouteRequest,
    ): NavigationResult<RoutePlan> {
        val config = ValhallaConfigFactory.usingTileExtract(
            installed.routingTileExtract.absolutePath,
            null,
        )
        val engine = Valhalla(applicationContext, config)
        return try {
            var lastFailure: NavigationFailure = NavigationFailure.ProviderUnavailable
            for (profile in RouteProfilePolicy.profilesFor(request)) {
                when (val result = routeWithProfile(engine, request, profile)) {
                    is NavigationResult.Success -> return result
                    is NavigationResult.Failure -> lastFailure = result.reason
                }
            }
            NavigationResult.Failure(lastFailure)
        } finally {
            engine.close()
        }
    }

    private fun routeWithProfile(
        engine: Valhalla,
        request: RouteRequest,
        profile: RouteProfile,
    ): NavigationResult<RoutePlan> {
        val alternativesLimit = request.alternativesLimit.coerceIn(1, MAX_ROUTES)
        val singleRouteRequest = request.copy(alternativesLimit = 1)
        val costing = profile.toValhallaCosting()
        val primary = decodeCandidate(
            engine = engine,
            request = singleRouteRequest,
            avoidLocations = emptyList(),
            costing = costing,
        )
        val primaryPlan = when (primary) {
            is NavigationResult.Success -> primary.value
            is NavigationResult.Failure -> return primary
        }
        val candidates = mutableListOf(primaryPlan.alternatives.single())
        for (nextAlternativeIndex in 1 until alternativesLimit) {
            val avoidLocations = RouteAlternativePolicy.avoidLocationsFor(
                acceptedRoutes = candidates,
                nextAlternativeIndex = nextAlternativeIndex,
            )
            val candidate = try {
                decodeCandidate(
                    engine = engine,
                    request = singleRouteRequest,
                    avoidLocations = avoidLocations,
                    costing = costing,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                null
            }
            if (candidate is NavigationResult.Success) {
                candidates += candidate.value.alternatives.single()
            }
        }

        val diverse = RouteDiversityPolicy.select(candidates, alternativesLimit)
        val ordered = RouteAlternativePolicy.orderForStyle(
            candidates = diverse,
            style = request.style,
            limit = alternativesLimit,
        ).mapIndexed { index, route -> route.withDeterministicId(index) }
        return NavigationResult.Success(RoutePlan(request.destination, ordered))
    }

    private fun decodeCandidate(
        engine: Valhalla,
        request: RouteRequest,
        avoidLocations: List<ru.sodovaya.volty.domain.navigation.GeoCoordinate>,
        costing: ValhallaCosting,
    ): NavigationResult<RoutePlan> {
        val response = engine.routeRaw(
            ValhallaRouteCodec.encodeRequest(
                request = request,
                alternativesLimit = 1,
                avoidLocations = avoidLocations,
                costing = costing,
            ),
        )
        // Keep native invocation failures separate from a response that the
        // engine returned but the app cannot understand. The latter is a
        // malformed provider response, not an unavailable native engine.
        return ValhallaRouteCodec.decodeRoutePlan(response, request)
    }

    private fun RouteProfile.toValhallaCosting(): ValhallaCosting = when (this) {
        RouteProfile.GENERIC -> ValhallaCosting.AUTO
        RouteProfile.MOTORCYCLE -> ValhallaCosting.MOTORCYCLE
        RouteProfile.BICYCLE -> ValhallaCosting.BICYCLE
        RouteProfile.PEDESTRIAN -> ValhallaCosting.PEDESTRIAN
    }

    private fun RouteAlternative.withDeterministicId(
        index: Int,
    ): RouteAlternative {
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

    private companion object {
        const val MAX_ROUTES = 3
    }
}

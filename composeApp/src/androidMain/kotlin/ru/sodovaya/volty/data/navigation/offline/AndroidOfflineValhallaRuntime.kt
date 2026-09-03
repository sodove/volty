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
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest
import ru.sodovaya.volty.domain.navigation.region.OfflineGeocoderRequest
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionRuntime

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
        val response = try {
            invokeValhalla(installed, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: LinkageError) {
            return@withContext NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: RuntimeException) {
            return@withContext NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }

        // Keep native invocation failures separate from a response that the
        // engine returned but the app cannot understand. The latter is a
        // malformed provider response, not an unavailable native engine.
        ValhallaRouteCodec.decodeRoutePlan(response, request)
    }

    private fun invokeValhalla(
        installed: InstalledOfflineRegion,
        request: RouteRequest,
    ): String {
        val config = ValhallaConfigFactory.usingTileExtract(
            installed.routingTileExtract.absolutePath,
            null,
        )
        val engine = Valhalla(applicationContext, config)
        return try {
            engine.routeRaw(ValhallaRouteCodec.encodeRequest(request))
        } finally {
            engine.close()
        }
    }
}

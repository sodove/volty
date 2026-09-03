package ru.sodovaya.volty.data.navigation.offline

import java.lang.reflect.InvocationTargetException
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
 * Reflection is deliberate while the AAR gate is still pending: this source can
 * land independently of the binary dependency and cannot silently make the
 * current production APK claim that Valhalla is installed. DI should bind this
 * only after the ARM64/x86_64 wrapper smoke passes.
 */
class AndroidOfflineValhallaRuntime(
    private val packageStore: AndroidOfflineRegionPackageStore,
) : OfflineRegionRuntime {
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
            val response = invokeValhalla(installed, request)
            ValhallaRouteCodec.decodeRoutePlan(response, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ClassNotFoundException) {
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: NoSuchMethodException) {
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: ReflectiveOperationException) {
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: LinkageError) {
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        } catch (_: RuntimeException) {
            NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }
    }

    private fun invokeValhalla(
        installed: InstalledOfflineRegion,
        request: RouteRequest,
    ): String {
        val type = Class.forName(VALHALLA_CLASS)
        val engine = type.getConstructor(String::class.java)
            .newInstance(installed.routingConfig.absolutePath)
        return try {
            type.getMethod("routeRaw", String::class.java)
                .invoke(engine, ValhallaRouteCodec.encodeRequest(request)) as? String
                ?: throw IllegalStateException("Valhalla returned a non-string response")
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("Valhalla route failed", error.targetException)
        }
    }

    private companion object {
        const val VALHALLA_CLASS = "com.valhalla.valhalla.Valhalla"
    }
}

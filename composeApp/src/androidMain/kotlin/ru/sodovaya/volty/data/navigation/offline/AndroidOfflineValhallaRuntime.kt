package ru.sodovaya.volty.data.navigation.offline

import android.content.Context
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
 * Reflection keeps this adapter independently deployable while the Android
 * dependency and regional catalog are being rolled out. The important detail
 * is that the AAR owns the config schema: do not pass the server/CLI JSON to
 * its legacy string constructor. Build the config through the version-matched
 * factory and use the Context constructor exercised by the AAR smoke harness.
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
        val factoryType = Class.forName(VALHALLA_CONFIG_FACTORY_CLASS)
        val factory = factoryType.getField("INSTANCE").get(null)
        val configFactory = factoryType.methods.firstOrNull { method ->
            method.name == "usingTileExtract" &&
                method.parameterTypes.contentEquals(
                    arrayOf(String::class.java, String::class.java),
                )
        } ?: throw NoSuchMethodException("ValhallaConfigFactory.usingTileExtract")
        val config = configFactory.invoke(
            factory,
            installed.routingTileExtract.absolutePath,
            null,
        ) ?: throw IllegalStateException("Valhalla config factory returned null")
        val constructor = type.constructors.firstOrNull { candidate ->
            val parameters = candidate.parameterTypes
            parameters.size == 2 &&
                parameters[0].isAssignableFrom(applicationContext.javaClass) &&
                parameters[1].isAssignableFrom(config.javaClass)
        } ?: throw NoSuchMethodException("Valhalla(Context, ValhallaConfig)")
        val engine = constructor.newInstance(applicationContext, config)
        return try {
            type.getMethod("routeRaw", String::class.java)
                .invoke(engine, ValhallaRouteCodec.encodeRequest(request)) as? String
                ?: throw IllegalStateException("Valhalla returned a non-string response")
        } catch (error: InvocationTargetException) {
            throw IllegalStateException("Valhalla route failed", error.targetException)
        } finally {
            runCatching { type.getMethod("close").invoke(engine) }
        }
    }

    private companion object {
        const val VALHALLA_CLASS = "com.valhalla.valhalla.Valhalla"
        const val VALHALLA_CONFIG_FACTORY_CLASS =
            "com.valhalla.valhalla.config.ValhallaConfigFactory"
    }
}

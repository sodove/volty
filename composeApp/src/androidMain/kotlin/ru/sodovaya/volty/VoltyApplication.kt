package ru.sodovaya.volty

import android.app.Application
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineMapSource
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineNavigationConfig
import ru.sodovaya.volty.di.androidModule
import ru.sodovaya.volty.di.appModule
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.usecase.AlertEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoltyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val koinApp = startKoin {
            androidContext(this@VoltyApplication)
            modules(appModule, androidModule)
        }
        val alertEngine = koinApp.koin.get<AlertEngine>()
        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        alertEngine.start(appScope)
        if (koinApp.koin.get<AndroidOfflineNavigationConfig>().enabled) {
            val packageRepository = koinApp.koin.get<OfflineRegionPackageRepository>()
            appScope.launch {
                runCatching { packageRepository.refreshCatalog() }
            }
            startAutomaticCurrentRegionDownload(
                scope = appScope,
                locationRepository = koinApp.koin.get<RideLocationRepository>(),
                packageRepository = packageRepository,
                mapSource = koinApp.koin.get<AndroidOfflineMapSource>(),
            )
        }
    }

    /**
     * Keeps the current region warm even when the map composable is not on screen.
     * This observes already-owned location updates; it deliberately does not ask
     * for permission or create a new location demand.
     */
    private fun startAutomaticCurrentRegionDownload(
        scope: CoroutineScope,
        locationRepository: RideLocationRepository,
        packageRepository: OfflineRegionPackageRepository,
        mapSource: AndroidOfflineMapSource,
    ) {
        scope.launch {
            combine(locationRepository.state, packageRepository.states) { locationState, _ ->
                (locationState.status as? RideLocationStatus.Available)?.fix?.coordinate
            }.collect { coordinate ->
                mapSource.considerDownload(coordinate)
            }
        }
    }
}

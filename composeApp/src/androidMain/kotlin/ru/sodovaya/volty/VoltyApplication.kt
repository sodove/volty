package ru.sodovaya.volty

import android.app.Application
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineNavigationConfig
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackManager
import ru.sodovaya.volty.di.androidModule
import ru.sodovaya.volty.di.appModule
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.usecase.AlertEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.maplibre.android.MapLibre

class VoltyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(applicationContext)
        val koinApp = startKoin {
            androidContext(this@VoltyApplication)
            modules(appModule, androidModule)
        }
        koinApp.koin.get<ru.sodovaya.volty.data.voice.IncomingCallDiscovery>().start()
        koinApp.koin.get<ru.sodovaya.volty.data.nearby.AndroidPushStartup>().start()
        val alertEngine = koinApp.koin.get<AlertEngine>()
        koinApp.koin.get<OfflineMapPackManager>()
        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        alertEngine.start(appScope)
        appScope.launch {
            try { koinApp.koin.get<ru.sodovaya.volty.presentation.history.HistoryPreviewImageLoader>().reconcileDeletedRides() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.w("HistoryPreview", "Preview cleanup deferred", failure) }
        }
        appScope.launch {
            try { koinApp.koin.get<ru.sodovaya.volty.domain.history.HistoryMediaRepository>().reconcile() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.w("HistoryMedia", "Private media recovery deferred", failure) }
        }
        if (koinApp.koin.get<AndroidOfflineNavigationConfig>().enabled) {
            val packageRepository = koinApp.koin.get<OfflineRegionPackageRepository>()
            appScope.launch {
                runCatching { packageRepository.refreshCatalog() }
            }
        }
    }
}

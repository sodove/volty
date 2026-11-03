package ru.sodovaya.volty

import android.app.Application
import ru.sodovaya.volty.data.db.DatabaseOpenGate
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
import kotlinx.coroutines.flow.first
import org.koin.android.ext.koin.androidContext
import org.koin.core.Koin
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
        val koin = koinApp.koin
        registerActivityLifecycleCallbacks(koin.get<ru.sodovaya.volty.data.radio.AppVisibility>())
        val appScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        // The database opens (and, once, converts the ride history) on IO, never here. Resolving the
        // graph below is query-free (the repositories hold a driver that waits for the gate), but
        // starting what reads or writes it is not: that waits for Ready, still on the main thread.
        val gate = koin.get<DatabaseOpenGate>()
        gate.start(appScope)
        appScope.launch(Dispatchers.Main) {
            gate.awaitReady()
            startDatabaseDependentWork(koin, appScope)
        }
    }

    private fun startDatabaseDependentWork(koin: Koin, appScope: CoroutineScope) {
        koin.get<ru.sodovaya.volty.data.voice.IncomingCallDiscovery>().start()
        koin.get<ru.sodovaya.volty.data.nearby.AndroidPushStartup>().start()
        // Reacts to the offline radio's state (audio, transport, foreground service); no-op below Android 10.
        koin.get<ru.sodovaya.volty.data.radio.RadioRuntime>().attach()
        val alertEngine = koin.get<AlertEngine>()
        koin.get<OfflineMapPackManager>()
        alertEngine.start(appScope)
        // A crash mid-recording leaves a hidden IS_PENDING row; no recording of this process can be active yet.
        appScope.launch(Dispatchers.IO) { ru.sodovaya.volty.video.PendingVideoSweeper.run(applicationContext) }
        appScope.launch {
            try { koin.get<ru.sodovaya.volty.presentation.history.HistoryPreviewImageLoader>().reconcileDeletedRides() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.w("HistoryPreview", "Preview cleanup deferred", failure) }
        }
        // Folds stored unknown chains into runs and VACUUMs when worth it; never while recording (HistoryStorageMaintenance).
        appScope.launch {
            try {
                // A pass stopped by a live recording resumes once no ride is being recorded.
                val recording = koin.get<ru.sodovaya.volty.domain.history.HistoryRecordingRepository>()
                koin.get<ru.sodovaya.volty.data.history.HistoryStorageMaintenance>().runUntilSettled {
                    recording.states.first { !ru.sodovaya.volty.diagnostics.historyRecordingInProgress(it) }
                }
            }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.w("VoltyHistory", "History maintenance deferred", failure) }
        }
        appScope.launch {
            try { koin.get<ru.sodovaya.volty.domain.history.HistoryMediaRepository>().reconcile() }
            catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (failure: Exception) { android.util.Log.w("HistoryMedia", "Private media recovery deferred", failure) }
        }
        if (koin.get<AndroidOfflineNavigationConfig>().enabled) {
            val packageRepository = koin.get<OfflineRegionPackageRepository>()
            appScope.launch {
                runCatching { packageRepository.refreshCatalog() }
            }
            appScope.launch {
                runCatching {
                    val freed = koin.get<ru.sodovaya.volty.data.navigation.offline.AndroidOfflineStorageMeter>()
                        .reclaimUnusedLegacy()
                    if (freed > 0) android.util.Log.i("VoltyOffline", "Reclaimed $freed bytes of the retired BRouter store")
                }
            }
        }
    }
}

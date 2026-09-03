package ru.sodovaya.volty

import android.app.Application
import ru.sodovaya.volty.di.androidModule
import ru.sodovaya.volty.di.appModule
import ru.sodovaya.volty.domain.usecase.AlertEngine
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineNavigationConfig
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            appScope.launch {
                runCatching {
                    koinApp.koin.get<OfflineRegionPackageRepository>().refreshCatalog()
                }
            }
        }
    }
}

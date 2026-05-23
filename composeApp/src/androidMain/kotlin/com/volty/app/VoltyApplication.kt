package com.volty.app

import android.app.Application
import com.volty.app.di.androidModule
import com.volty.app.di.appModule
import com.volty.app.domain.usecase.AlertEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    }
}

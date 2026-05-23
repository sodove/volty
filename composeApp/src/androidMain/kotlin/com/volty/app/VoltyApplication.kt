package com.volty.app

import android.app.Application
import com.volty.app.di.androidModule
import com.volty.app.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VoltyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@VoltyApplication)
            modules(appModule, androidModule)
        }
    }
}

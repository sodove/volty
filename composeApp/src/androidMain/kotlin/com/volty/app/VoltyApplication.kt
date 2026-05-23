package com.volty.app

import android.app.Application

class VoltyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // startKoin is enabled in Task 3 once AppModule and AndroidModule exist
        // startKoin {
        //     androidContext(this@VoltyApplication)
        //     modules(appModule, androidModule)
        // }
    }
}

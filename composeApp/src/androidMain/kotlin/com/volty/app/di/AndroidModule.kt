package com.volty.app.di

import com.volty.app.data.db.SqlDriverFactory
import com.volty.app.data.prefs.DataStoreFactory
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    single { DataStoreFactory(androidContext()) }
}

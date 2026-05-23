package com.volty.app.di

import com.volty.app.data.db.SqlDriverFactory
import com.volty.app.data.prefs.DataStoreFactory
import com.volty.app.notification.AndroidNotifier
import com.volty.app.notification.Notifier
import com.volty.app.permissions.PermissionsChecker
import com.volty.app.service.ServiceController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    single { DataStoreFactory(androidContext()) }
    single { PermissionsChecker(androidContext()) }
    single<Notifier> { AndroidNotifier(androidContext()) }
    single { ServiceController(androidContext()) }
}

package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.notification.AndroidNotifier
import ru.sodovaya.volty.notification.AudibleAlarm
import ru.sodovaya.volty.notification.Notifier
import ru.sodovaya.volty.permissions.PermissionsChecker
import ru.sodovaya.volty.service.ServiceController
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    single { DataStoreFactory(androidContext()) }
    single { PermissionsChecker(androidContext()) }
    single<Notifier> { AndroidNotifier(androidContext()) }
    // A single: one speaker, so exactly one owner. Task 8's service drives it and
    // Task 9's settings screen previews through it; two instances would fight.
    single { AudibleAlarm(androidContext()) }
    single { ServiceController(androidContext()) }
    single { LogExporter(androidContext()) }
}

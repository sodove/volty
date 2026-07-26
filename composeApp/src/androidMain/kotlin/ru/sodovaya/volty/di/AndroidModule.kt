package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.notification.AndroidNotifier
import ru.sodovaya.volty.notification.AudibleAlarmHolder
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
    //
    // The *holder* is what is shared, not the alarm itself: `AudibleAlarm.release()`
    // is terminal, and MonitoringService.onDestroy has to call it, so a
    // process-scoped alarm would be permanently dead from the rider's second ride
    // onwards. The holder keeps the one-instance-at-a-time guarantee and rebuilds
    // after a release — see AudibleAlarmHolder's KDoc.
    single { AudibleAlarmHolder(androidContext()) }
    single { ServiceController(androidContext()) }
    single { LogExporter(androidContext()) }
}

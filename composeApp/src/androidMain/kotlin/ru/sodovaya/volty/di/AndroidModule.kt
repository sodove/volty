package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.data.social.AndroidSocialCredentialStore
import ru.sodovaya.volty.data.social.AndroidLiveKitVoiceRoomEngine
import ru.sodovaya.volty.data.social.AndroidLocationProvider
import ru.sodovaya.volty.data.social.SocialCredentialStore
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.notification.AlarmPreview
import ru.sodovaya.volty.notification.AndroidNotifier
import ru.sodovaya.volty.notification.AudibleAlarmHolder
import ru.sodovaya.volty.notification.HolderAlarmPreview
import ru.sodovaya.volty.notification.Notifier
import ru.sodovaya.volty.permissions.PermissionsChecker
import ru.sodovaya.volty.service.ServiceController
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.VoiceRoomEngine
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    single { DataStoreFactory(androidContext()) }
    single<SocialCredentialStore> { AndroidSocialCredentialStore(androidContext()) }
    single<LocationProvider> { AndroidLocationProvider(androidContext()) }
    single<VoiceRoomEngine> { AndroidLiveKitVoiceRoomEngine(androidContext()) }
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
    // The settings screen's "проверить сигнал" line to the speaker. Bound to the
    // HOLDER, never to an AudibleAlarm: `release()` is terminal and the service
    // calls it every time the rider parks, so a captured instance would be
    // permanently, silently dead from the second ride on.
    single<AlarmPreview> { HolderAlarmPreview(get()) }
    single { ServiceController(androidContext()) }
    single { LogExporter(androidContext()) }
}

package ru.sodovaya.volty.di

import android.content.Context
import android.os.Build
import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.data.ble.AndroidBleAdapterStateProvider
import ru.sodovaya.volty.data.ble.BleAdapterStateProvider
import ru.sodovaya.volty.data.location.AndroidRideLocationRepository
import ru.sodovaya.volty.data.navigation.AndroidHybridNavigationRepository
import ru.sodovaya.volty.data.navigation.OsmNavigationRepository
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRoutingPackageManager
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineNavigationConfig
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineNetworkStatus
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRegionPackageRepository
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRegionPackageStore
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineValhallaRuntime
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineMapSource
import ru.sodovaya.volty.domain.navigation.region.OfflineDownloadPreferences
import ru.sodovaya.volty.domain.navigation.region.OfflineFirstNavigationRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionRuntime
import ru.sodovaya.volty.data.prefs.AppPrefs
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
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    single { DataStoreFactory(androidContext()) }
    single<BleAdapterStateProvider> { AndroidBleAdapterStateProvider(androidContext()) }
    single<RideLocationRepository> { AndroidRideLocationRepository(androidContext()) }
    single<SocialCredentialStore> { AndroidSocialCredentialStore(androidContext()) }
    single<LocationProvider> { AndroidLocationProvider(get()) }
    single<VoiceRoomEngine> { AndroidLiveKitVoiceRoomEngine(androidContext(), get()) }
    single { AndroidOfflineRoutingPackageManager(androidContext()) }
    single { AndroidOfflineNavigationConfig.from(androidContext()) }
    single(named(APP_VERSION_CODE)) { currentAppVersionCode(androidContext()) }
    single<OfflineRegionManifestVerifier> {
        get<AndroidOfflineNavigationConfig>().verifier()
    }
    single<OfflineRegionCatalogVerifier> {
        get<AndroidOfflineNavigationConfig>().catalogVerifier()
    }
    single {
        AndroidOfflineRegionPackageStore(
            context = androidContext(),
            currentAppVersionCode = get<Int>(named(APP_VERSION_CODE)),
            manifestVerifier = get(),
        )
    }
    single<OfflineRegionPackageRepository> {
        AndroidOfflineRegionPackageRepository(
            context = androidContext(),
            catalogUrl = get<AndroidOfflineNavigationConfig>().catalogUrl,
            currentAppVersionCode = get<Int>(named(APP_VERSION_CODE)),
            manifestVerifier = get(),
            catalogVerifier = get(),
            packageStore = get(),
            preferences = {
                OfflineDownloadPreferences(
                    skipMeteredConfirmation = get<AppPrefs>()
                        .offlineSkipMeteredConfirmation.value,
                )
            },
        )
    }
    single<OfflineRegionRuntime> {
        AndroidOfflineValhallaRuntime(
            packageStore = get(),
            context = androidContext(),
        )
    }
    single<OfflineNetworkStatus> { AndroidOfflineNetworkStatus(androidContext()) }
    single(named(OFFLINE_DOWNLOAD_SCOPE)) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
    single {
        AndroidOfflineMapSource(
            packageStore = get(),
            packages = get(),
            downloadScope = get(named(OFFLINE_DOWNLOAD_SCOPE)),
        )
    }
    single(named(OFFLINE_FIRST_NAVIGATION)) {
        OfflineFirstNavigationRepository(
            online = get<OsmNavigationRepository>(),
            packages = get(),
            runtime = get(),
            network = get(),
            preferences = {
                OfflineDownloadPreferences(
                    skipMeteredConfirmation = get<AppPrefs>()
                        .offlineSkipMeteredConfirmation.value,
                )
            },
            downloadScope = get(named(OFFLINE_DOWNLOAD_SCOPE)),
        )
    }
    single<NavigationRepository> {
        if (get<AndroidOfflineNavigationConfig>().enabled) {
            get<OfflineFirstNavigationRepository>(named(OFFLINE_FIRST_NAVIGATION))
        } else {
            AndroidHybridNavigationRepository(
                online = get<OsmNavigationRepository>(),
                packageManager = get(),
                context = androidContext(),
            )
        }
    }
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

private const val OFFLINE_DOWNLOAD_SCOPE = "offline-region-downloads"
private const val OFFLINE_FIRST_NAVIGATION = "offline-first-navigation"
private const val APP_VERSION_CODE = "app-version-code"

private fun currentAppVersionCode(context: Context): Int {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    return versionCode.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

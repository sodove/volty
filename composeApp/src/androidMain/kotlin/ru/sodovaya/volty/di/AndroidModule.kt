package ru.sodovaya.volty.di

import android.content.Context
import android.os.Build
import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import ru.sodovaya.volty.BuildConfig
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
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineMapPackManager
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineMapPackStore
import ru.sodovaya.volty.data.navigation.offline.AndroidOfflineRegionPreparationStore
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackManager
import ru.sodovaya.volty.domain.navigation.region.OfflineDownloadPreferences
import ru.sodovaya.volty.domain.navigation.region.OfflineFirstNavigationRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifestVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionCatalogVerifier
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPackageRepository
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionRuntime
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPreparationCoordinator
import ru.sodovaya.volty.domain.navigation.region.DefaultOfflineRegionPreparationCoordinator
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionPreparationStore
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.data.social.AndroidSocialCredentialStore
import ru.sodovaya.volty.data.social.AndroidLiveKitVoiceRoomEngine
import ru.sodovaya.volty.data.social.AndroidLocationProvider
import ru.sodovaya.volty.data.social.HttpSocialTransport
import ru.sodovaya.volty.data.social.SocialCredentialStore
import ru.sodovaya.volty.data.nearby.AndroidInvitationDraftStore
import ru.sodovaya.volty.data.nearby.AndroidInvitationSecretGenerator
import ru.sodovaya.volty.di.nearby.NearbyCommunityAccountFactory
import ru.sodovaya.volty.di.nearby.VoiceRosterAccountFactory
import ru.sodovaya.volty.di.nearby.DirectCallRosterAccountFactory
import ru.sodovaya.volty.data.voice.VoiceConversationCallRosterFactory
import ru.sodovaya.volty.data.voice.VoiceAccountCoordinator
import ru.sodovaya.volty.data.voice.VoiceConversationStartup
import ru.sodovaya.volty.data.voice.AndroidVoiceAudioFactory
import ru.sodovaya.volty.data.voice.VoiceForegroundGate
import ru.sodovaya.volty.data.voice.AndroidVoiceForegroundController
import ru.sodovaya.volty.data.voice.IncomingCallActionRegistry
import ru.sodovaya.volty.data.voice.AndroidIncomingCallAccountFactory
import ru.sodovaya.volty.data.voice.IncomingCallAccountCoordinator
import ru.sodovaya.volty.presentation.root.nearby.NearbySharingSwitchGuard
import ru.sodovaya.volty.presentation.root.nearby.NearbyVoiceController
import ru.sodovaya.volty.presentation.root.nearby.NearbySharingController
import ru.sodovaya.volty.diagnostics.LogExporter
import ru.sodovaya.volty.notification.AlarmPreview
import ru.sodovaya.volty.notification.AndroidNotifier
import ru.sodovaya.volty.notification.AudibleAlarmHolder
import ru.sodovaya.volty.notification.HolderAlarmPreview
import ru.sodovaya.volty.notification.Notifier
import ru.sodovaya.volty.permissions.PermissionsChecker
import ru.sodovaya.volty.service.ServiceController
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.VoiceRoomEngine
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import org.maplibre.android.offline.OfflineManager

val androidModule = module {
    single { SqlDriverFactory(androidContext()) }
    // Inert until the future recording runtime awaits prepare(); one clock for BLE/GPS/detail.
    single {
        val store = get<ru.sodovaya.volty.data.history.SqlDelightHistoryStore>()
        ru.sodovaya.volty.data.history.HistoryRecordingClock(
            prepareGeneration = store::prepareRecordingClock,
            epochMillis = System::currentTimeMillis,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
        )
    }
    single { DataStoreFactory(androidContext()) }
    single<BleAdapterStateProvider> { AndroidBleAdapterStateProvider(androidContext()) }
    single<RideLocationRepository> { AndroidRideLocationRepository(androidContext()) }
    single<SocialCredentialStore> { AndroidSocialCredentialStore(androidContext()) }
    single(named(NEARBY_HTTP_CLIENT)) {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000L
                requestTimeoutMillis = 10_000L
                socketTimeoutMillis = 10_000L
            }
        }
    } onClose { it?.close() }
    single {
        val context = androidContext()
        NearbyCommunityAccountFactory(
            client = get(named(NEARBY_HTTP_CLIENT)),
            baseUrl = NEARBY_API_BASE_URL,
            storeForActor = { actor -> AndroidInvitationDraftStore(context, actor) },
            generator = AndroidInvitationSecretGenerator(),
        )
    }
    single {
        VoiceRosterAccountFactory(
            client = get(named(NEARBY_HTTP_CLIENT)),
            baseUrl = NEARBY_API_BASE_URL,
            queuedDispatcher = Dispatchers.Main,
            monotonicMillis = SystemClock::elapsedRealtime,
            // Cold per-subscription source. Cancellation belongs to the roster owner.
            pulses = flow { while (true) { delay(1_000L); emit(Unit) } },
        )
    }
    single {
        val gate = get<VoiceForegroundGate>()
        VoiceAccountCoordinator(
            client = get(named(NEARBY_HTTP_CLIENT)),
            baseUrl = NEARBY_API_BASE_URL,
            queuedDispatcher = Dispatchers.Main,
            // Each runtime receives its own cadence; ticks must not be divided among owners.
            pulses = flow { while (true) { delay(1_000L); emit(Unit) } },
            monotonicMillis = SystemClock::elapsedRealtime,
            nativeEnvironment = { gate.allows(it) },
        )
    }
    single<VoiceConversationCallRosterFactory> {
        DirectCallRosterAccountFactory(
            client = get(named(NEARBY_HTTP_CLIENT)),
            baseUrl = NEARBY_API_BASE_URL,
            queuedDispatcher = Dispatchers.Main,
            monotonicMillis = SystemClock::elapsedRealtime,
            pulses = flow { while (true) { delay(1_000L); emit(Unit) } },
        )
    }
    single {
        val application = androidContext()
        VoiceForegroundGate(
            permission = { androidx.core.content.ContextCompat.checkSelfPermission(application,
                android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            nextId = { java.util.UUID.randomUUID().toString() },
        )
    }
    // Use the same Android OkHttp stack as navigation for the social REST/WS
    // boundary. On the field emulator the platform-default Ktor engine failed
    // before an HTTP request reached the production reverse proxy, which the
    // transport reduced to a generic network error.
    single<SocialTransport> {
        HttpSocialTransport(
            HttpClient(OkHttp) {
                install(WebSockets)
                install(HttpTimeout) {
                    connectTimeoutMillis = 10_000L
                    requestTimeoutMillis = 15_000L
                    socketTimeoutMillis = 15_000L
                }
            },
        )
    }
    single<LocationProvider> { AndroidLocationProvider(get()) }
    single { AndroidVoiceAudioFactory(androidContext()) }
    single { AndroidVoiceForegroundController(androidContext(), get()) }
    single<ru.sodovaya.volty.data.voice.VoiceConversationChannelAudienceFactory> {
        ru.sodovaya.volty.di.nearby.ChannelSharingAudienceAccountFactory(
            get(named(NEARBY_HTTP_CLIENT)), NEARBY_API_BASE_URL, Dispatchers.Main, SystemClock::elapsedRealtime,
        )
    }
    single {
        val coordinator=get<VoiceAccountCoordinator>()
        val audio=get<AndroidVoiceAudioFactory>()
        VoiceConversationStartup(Dispatchers.Main,get<AndroidVoiceForegroundController>(),
            get<VoiceConversationCallRosterFactory>(),get<ru.sodovaya.volty.data.voice.VoiceConversationChannelAudienceFactory>(),
            get<ru.sodovaya.volty.data.voice.VoiceConversationChannelRosterFactory>()) {account ->
            coordinator.acquireBound(account,audio.createPrepared()).voice
        }
    }
    single<ru.sodovaya.volty.data.voice.VoiceConversationChannelRosterFactory> {
        val factory=get<VoiceRosterAccountFactory>()
        ru.sodovaya.volty.data.voice.VoiceConversationChannelRosterFactory {account,tag -> factory.create(account,tag)}
    }
    single {
        val application=androidContext()
        ru.sodovaya.volty.data.voice.ChannelLocationForegroundAuthority(
            permission={ru.sodovaya.volty.data.voice.channelLocationPermission(application)},
            nextId={java.util.UUID.randomUUID().toString()})
    }
    single {ru.sodovaya.volty.data.voice.AndroidChannelLocationForegroundController(androidContext(),get())}
    single {ru.sodovaya.volty.data.voice.NearbyLocationDemandCoordinator(get())}
    single {
        val coordinator=get<VoiceAccountCoordinator>()
        val audio=get<AndroidVoiceAudioFactory>()
        val telemetry=lazy {get<ru.sodovaya.volty.domain.social.SocialTelemetrySource>()}
        ru.sodovaya.volty.data.voice.ChannelSharingStartup(get(named(NEARBY_HTTP_CLIENT)),NEARBY_API_BASE_URL,
            Dispatchers.Main,get(),get(),get<ru.sodovaya.volty.data.voice.AndroidChannelLocationForegroundController>(),
            SystemClock::elapsedRealtime,System::currentTimeMillis,
            {telemetry.value.latest.value ?: ru.sodovaya.volty.domain.social.EarnedTelemetry()},
            get<ru.sodovaya.volty.data.voice.ChannelSharingRevokeJournal>(),{java.util.UUID.randomUUID().toString()}) {account ->
            coordinator.acquireBound(account,audio.createPrepared())
        }
    }
    single { IncomingCallActionRegistry(Dispatchers.Main) {java.util.UUID.randomUUID().toString()} }
    // Process owners. Screens/Activity borrow these and dispose only their launch boundaries.
    single { NearbySharingSwitchGuard(get(),get(),Dispatchers.Main) }
    single {
        val prefs=get<AppPrefs>()
        NearbyVoiceController(get(),get(),Dispatchers.Main,{java.util.UUID.randomUUID().toString()},
            prefs::readVoiceAudioPreferences,get<NearbySharingSwitchGuard>())
    }
    single { NearbySharingController(get(),get(),get(),get(),Dispatchers.Main,{java.util.UUID.randomUUID().toString()}) }
    single<ru.sodovaya.volty.presentation.root.nearby.NearbyOperationIds> {
        ru.sodovaya.volty.presentation.root.nearby.NearbyOperationIds {java.util.UUID.randomUUID().toString()}
    }
    single {
        AndroidIncomingCallAccountFactory(androidContext(), get(), Dispatchers.Main,
            flow {while (true) {delay(1_000L); emit(Unit)}}, SystemClock::elapsedRealtime,
            {java.util.UUID.randomUUID().toString()}, get(named(NEARBY_HTTP_CLIENT)), SOCIAL_FRIENDS_API_BASE_URL)
    }
    single { IncomingCallAccountCoordinator(Dispatchers.Main, get(), get<AndroidIncomingCallAccountFactory>()) }
    single { ru.sodovaya.volty.presentation.root.nearby.NearbyIncomingCallAcceptance(get(),get(),get(),Dispatchers.Main) }
    single { ru.sodovaya.volty.presentation.root.nearby.NearbyIncomingCallUi(get(),get(),get(),Dispatchers.Main,
        {java.util.UUID.randomUUID().toString()}) }
    single {
        val coordinator=get<VoiceAccountCoordinator>()
        val audio=get<AndroidVoiceAudioFactory>()
        ru.sodovaya.volty.data.voice.IncomingCallDiscovery(get(),Dispatchers.Main,get()) {account ->
            coordinator.acquireBound(account,audio.createPrepared()).voice
        }
    }
    single<VoiceRoomEngine> { AndroidLiveKitVoiceRoomEngine(androidContext(), get()) }
    single<ru.sodovaya.volty.data.nearby.PushInstallationStore> {ru.sodovaya.volty.data.nearby.AndroidPushInstallationStore(androidContext())}
    single<ru.sodovaya.volty.data.nearby.PushInstallationIdentityGenerator> {ru.sodovaya.volty.data.nearby.AndroidPushInstallationIdentityGenerator()}
    single {ru.sodovaya.volty.data.nearby.PushInstallationLedger(get(), get())}
    single<ru.sodovaya.volty.data.nearby.PushTokenSource> {ru.sodovaya.volty.data.nearby.AndroidFcmTokenSource(androidContext())}
    single {ru.sodovaya.volty.data.nearby.AndroidPushEvents()}
    single {
        val repository = get<ru.sodovaya.volty.data.social.DefaultSocialRepository>()
        val coordinator = get<VoiceAccountCoordinator>()
        val audio = get<AndroidVoiceAudioFactory>()
        ru.sodovaya.volty.data.voice.IncomingCallHintDispatch(Dispatchers.Main, {repository.authenticatedAccount.value}, get()) {account ->
            coordinator.acquireBound(account, audio.createPrepared()).voice
        }
    }
    single {
        val repository = get<ru.sodovaya.volty.data.social.DefaultSocialRepository>()
        val hints = get<ru.sodovaya.volty.data.voice.IncomingCallHintDispatch>()
        ru.sodovaya.volty.data.nearby.IncomingCallPushGate({repository.authenticatedAccount.value}, get(),
            ru.sodovaya.volty.data.nearby.AndroidCallPushDiagnostics::record) {account, call, beforePublish ->
            hints.submit(account, call, beforePublish)?.await() == true
        }
    }
    single {
        val repository = get<ru.sodovaya.volty.data.social.DefaultSocialRepository>()
        val hints = get<ru.sodovaya.volty.data.voice.IncomingCallHintDispatch>()
        val coordinator = get<VoiceAccountCoordinator>(); val audio = get<AndroidVoiceAudioFactory>()
        val client = get<HttpClient>(named(NEARBY_HTTP_CLIENT))
        val ledger = get<ru.sodovaya.volty.data.nearby.PushInstallationLedger>()
        ru.sodovaya.volty.data.nearby.PushRegistrationOwner(repository.authenticatedAccount, repository.applicationScope(),
            Dispatchers.Main, flow {while (true) {delay(30_000); emit(Unit)}},
            get<ru.sodovaya.volty.data.nearby.AndroidPushEvents>().tokens, get(), get(),
            {account -> ru.sodovaya.volty.data.nearby.AndroidObservedPushRegistration(
                ru.sodovaya.volty.data.nearby.AccountPushRegistration(account, client, NEARBY_API_BASE_URL))}) {account ->
            val binding = ledger.current()
            val runtime = coordinator.acquireBound(account, audio.createPrepared()).voice
            if (binding != null && binding.acknowledged && binding.intent.token != null &&
                binding.actorId == account.userId.value && repository.authenticatedAccount.value === account &&
                account.job.isActive && !runtime.retired.value) {
                val reply = runtime.incomingCalls(limit = 1)
                if (reply is ru.sodovaya.volty.data.nearby.IncomingCallReply.Ready)
                    reply.calls.firstOrNull()?.let {call ->
                        hints.submit(account, call.callId) {
                            val latest = ledger.current()
                            repository.authenticatedAccount.value === account && account.job.isActive && !runtime.retired.value &&
                                latest != null && latest.acknowledged && latest.actorId == binding.actorId &&
                                latest.intent.installationId == binding.intent.installationId &&
                                latest.intent.installationKey == binding.intent.installationKey &&
                                latest.intent.bindingId == binding.intent.bindingId &&
                                latest.intent.generation == binding.intent.generation &&
                                latest.intent.token == binding.intent.token
                        }?.await()
                    }
            }
        }
    }
    single {ru.sodovaya.volty.data.nearby.AndroidPushStartup(get(), get(), get(), get())}
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
    single { OfflineManager.getInstance(androidContext()) }
    single { AndroidOfflineMapPackStore() }
    single<OfflineMapPackManager> {
        AndroidOfflineMapPackManager(
            offlineManager = get(),
            store = get(),
            scope = get(named(OFFLINE_DOWNLOAD_SCOPE)),
            pixelRatio = androidContext().resources.displayMetrics.density,
        )
    }
    single<OfflineRegionPreparationStore> { AndroidOfflineRegionPreparationStore(androidContext()) }
    single<OfflineRegionPreparationCoordinator> {
        DefaultOfflineRegionPreparationCoordinator(
            mapPacks = get(),
            packages = get(),
            scope = get(named(OFFLINE_DOWNLOAD_SCOPE)),
            store = get(),
            network = get(),
            preferences = {
                OfflineDownloadPreferences(
                    skipMeteredConfirmation = get<AppPrefs>().offlineSkipMeteredConfirmation.value,
                )
            },
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
        if (BuildConfig.VOLTY_OFFLINE_RUNTIME_ENABLED) {
            get<OfflineFirstNavigationRepository>(named(OFFLINE_FIRST_NAVIGATION))
        } else {
            AndroidHybridNavigationRepository(
                online = get<OsmNavigationRepository>(),
                packageManager = get(),
                network = get(),
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
    single { ru.sodovaya.volty.diagnostics.VoiceDiagnosticLauncher(androidContext()) }
    single { ru.sodovaya.volty.diagnostics.voice.AndroidVoiceDiagnosticRuntime(androidContext(), get(), get()) }
}

private const val OFFLINE_DOWNLOAD_SCOPE = "offline-region-downloads"
private const val OFFLINE_FIRST_NAVIGATION = "offline-first-navigation"
private const val APP_VERSION_CODE = "app-version-code"
private const val NEARBY_HTTP_CLIENT = "nearby-http-client"
// Accounts/friends are retained on v1; this does not read or migrate legacy groups.
private const val SOCIAL_FRIENDS_API_BASE_URL = "https://volty.sodove.ru/v1"
private const val NEARBY_API_BASE_URL = "https://volty.sodove.ru/v2/nearby"

private fun currentAppVersionCode(context: Context): Int {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        packageInfo.versionCode.toLong()
    }
    return versionCode.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

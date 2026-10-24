package ru.sodovaya.volty.di

import kotlinx.coroutines.flow.map
import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.ble.BleAdapterStateProvider
import ru.sodovaya.volty.data.bms.ControllerConfigSource
import ru.sodovaya.volty.data.db.SqlDelightVehicleRepository
import ru.sodovaya.volty.data.db.SqlDelightRideHistoryRepository
import ru.sodovaya.volty.data.history.SqlDelightHistoryStore
import ru.sodovaya.volty.data.history.SqlDelightHistoryReadRepository
import ru.sodovaya.volty.domain.history.HistoryStore
import ru.sodovaya.volty.domain.history.HistoryReadRepository
import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.social.HttpSocialTransport
import ru.sodovaya.volty.data.social.LiveKitVoiceRoomRepository
import ru.sodovaya.volty.data.social.BmsSocialTelemetrySource
import ru.sodovaya.volty.data.social.DefaultSocialRideRuntime
import ru.sodovaya.volty.data.navigation.OsmNavigationRepository
import ru.sodovaya.volty.data.navigation.BmsNavigationEnergySource
import ru.sodovaya.volty.data.energy.BmsRangeSource
import ru.sodovaya.volty.data.energy.RangeSource
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.BleDiagnosticsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.repository.RideHistoryRepository
import ru.sodovaya.volty.domain.usecase.AlertEngine
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.SocialTelemetrySource
import ru.sodovaya.volty.domain.social.SocialShareSessionCoordinator
import ru.sodovaya.volty.domain.social.SocialRideRuntime
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationEnergySource
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

@OptIn(kotlin.time.ExperimentalTime::class)
val appModule = module {
    single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }
    single<ru.sodovaya.volty.data.voice.ChannelSharingRevokeJournal> {
        ru.sodovaya.volty.data.db.SqlDelightChannelSharingRevokeJournal(get(),kotlinx.coroutines.Dispatchers.IO)
    }
    single { AppPrefs(get<DataStoreFactory>().create()) }
    singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class
    single<ru.sodovaya.volty.domain.energy.VehicleEnergyStore> { ru.sodovaya.volty.data.db.SqlDelightVehicleEnergyStore(get()) }
    singleOf(::SqlDelightRideHistoryRepository) bind RideHistoryRepository::class
    single { SqlDelightHistoryStore(get()) } bind HistoryStore::class
    single { SqlDelightHistoryReadRepository(get()) } bind HistoryReadRepository::class
    single<ru.sodovaya.volty.domain.history.HistoryFavoriteRepository> {
        ru.sodovaya.volty.data.history.SqlDelightHistoryFavorites(get(), onDeleted = {
            get<ru.sodovaya.volty.domain.history.HistoryMediaRepository>().cleanupRetired()
        }) { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
    single<ru.sodovaya.volty.domain.history.HistoryMetricPresetStore> {
        ru.sodovaya.volty.data.history.DataStoreHistoryMetricPresets(get<DataStoreFactory>().create())
    }
    // TWO bindings, ONE instance: CAN discovery (G2 Task 5) needs the same live
    // links the connection does — a second KableBmsRepository would have none.
    // `binds` rather than a second `single { get<BmsRepository>() as … }` so the
    // cast cannot go stale if the implementation ever moves.
    single { KableBmsRepository(get(), get(), get(), get<BleAdapterStateProvider>(),
        get<ru.sodovaya.volty.data.history.HistoryRecordingRuntime>(), get<ru.sodovaya.volty.domain.energy.VehicleEnergyStore>()) } binds arrayOf(
        BmsRepository::class,
        CanDiscovery::class,
        ControllerConfigSource::class,
        ru.sodovaya.volty.data.bms.LearnedCellCountSource::class,
        BleDiagnosticsRepository::class
    )
    single { AlertEngine(get(), get()) }
    single<SocialTransport> { HttpSocialTransport() }
    // Android replaces the public repository binding with the hybrid online /
    // embedded-BRouter implementation. Keep the concrete online repository
    // available for it without defining the interface twice in Koin.
    single { OsmNavigationRepository() }
    single<NavigationEnergySource> { BmsNavigationEnergySource(get()) }
    single<RangeSource> { BmsRangeSource(get()) }
    single {
        DefaultSocialRepository(
            get(), get(),
            // An account opened offline is checked with the server again once internet returns.
            networkAvailable = getOrNull<ru.sodovaya.volty.domain.navigation.region.OfflineNetworkStatus>()?.changes
                ?.map { it != ru.sodovaya.volty.domain.navigation.region.OfflineNetworkAvailability.OFFLINE }
                ?: kotlinx.coroutines.flow.emptyFlow(),
        )
    } bind SocialRepository::class
    single<VoiceRoomRepository> { LiveKitVoiceRoomRepository(get(), get()) }
    single<SocialTelemetrySource> { BmsSocialTelemetrySource(get()) }
    single { SocialShareSessionCoordinator(get(), get()) }
    single<SocialRideRuntime> { DefaultSocialRideRuntime(get(), get(), get(), get()) }
}

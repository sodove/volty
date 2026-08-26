package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.ble.BleAdapterStateProvider
import ru.sodovaya.volty.data.bms.ControllerConfigSource
import ru.sodovaya.volty.data.db.SqlDelightVehicleRepository
import ru.sodovaya.volty.data.db.SqlDelightRideHistoryRepository
import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.data.social.DefaultSocialRepository
import ru.sodovaya.volty.data.social.HttpSocialTransport
import ru.sodovaya.volty.data.social.LiveKitVoiceRoomRepository
import ru.sodovaya.volty.data.social.BmsSocialTelemetrySource
import ru.sodovaya.volty.data.social.DefaultSocialRideRuntime
import ru.sodovaya.volty.domain.repository.BmsRepository
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
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

val appModule = module {
    single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }
    single { AppPrefs(get<DataStoreFactory>().create()) }
    singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class
    singleOf(::SqlDelightRideHistoryRepository) bind RideHistoryRepository::class
    // TWO bindings, ONE instance: CAN discovery (G2 Task 5) needs the same live
    // links the connection does — a second KableBmsRepository would have none.
    // `binds` rather than a second `single { get<BmsRepository>() as … }` so the
    // cast cannot go stale if the implementation ever moves.
    single { KableBmsRepository(get(), get(), get(), get<BleAdapterStateProvider>()) } binds arrayOf(
        BmsRepository::class,
        CanDiscovery::class,
        ControllerConfigSource::class
    )
    single { AlertEngine(get(), get()) }
    single<SocialTransport> { HttpSocialTransport() }
    singleOf(::DefaultSocialRepository) bind SocialRepository::class
    single<VoiceRoomRepository> { LiveKitVoiceRoomRepository(get(), get()) }
    single<SocialTelemetrySource> { BmsSocialTelemetrySource(get()) }
    single { SocialShareSessionCoordinator(get(), get()) }
    single<SocialRideRuntime> { DefaultSocialRideRuntime(get(), get(), get(), get()) }
}

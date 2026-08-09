package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.bms.ControllerConfigSource
import ru.sodovaya.volty.data.db.SqlDelightVehicleRepository
import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.CanDiscovery
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.usecase.AlertEngine
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

val appModule = module {
    single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }
    single { AppPrefs(get<DataStoreFactory>().create()) }
    singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class
    // TWO bindings, ONE instance: CAN discovery (G2 Task 5) needs the same live
    // links the connection does — a second KableBmsRepository would have none.
    // `binds` rather than a second `single { get<BmsRepository>() as … }` so the
    // cast cannot go stale if the implementation ever moves.
    singleOf(::KableBmsRepository) binds arrayOf(
        BmsRepository::class,
        CanDiscovery::class,
        ControllerConfigSource::class
    )
    single { AlertEngine(get(), get()) }
}

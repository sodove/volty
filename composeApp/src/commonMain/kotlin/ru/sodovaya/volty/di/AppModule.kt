package ru.sodovaya.volty.di

import ru.sodovaya.volty.data.ble.KableBmsRepository
import ru.sodovaya.volty.data.db.SqlDelightVehicleRepository
import ru.sodovaya.volty.data.db.SqlDriverFactory
import ru.sodovaya.volty.data.db.VoltyDatabaseProvider
import ru.sodovaya.volty.data.prefs.AppPrefs
import ru.sodovaya.volty.data.prefs.DataStoreFactory
import ru.sodovaya.volty.domain.repository.BmsRepository
import ru.sodovaya.volty.domain.repository.VehicleRepository
import ru.sodovaya.volty.domain.usecase.AlertEngine
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }
    single { AppPrefs(get<DataStoreFactory>().create()) }
    singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class
    singleOf(::KableBmsRepository) bind BmsRepository::class
    single { AlertEngine(get(), get()) }
}

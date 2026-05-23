package com.volty.app.di

import com.volty.app.data.ble.KableBmsRepository
import com.volty.app.data.db.SqlDelightVehicleRepository
import com.volty.app.data.db.SqlDriverFactory
import com.volty.app.data.db.VoltyDatabaseProvider
import com.volty.app.data.prefs.AppPrefs
import com.volty.app.data.prefs.DataStoreFactory
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.VehicleRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    single { VoltyDatabaseProvider(get<SqlDriverFactory>().create()) }
    single { AppPrefs(get<DataStoreFactory>().create()) }
    singleOf(::SqlDelightVehicleRepository) bind VehicleRepository::class
    singleOf(::KableBmsRepository) bind BmsRepository::class
}

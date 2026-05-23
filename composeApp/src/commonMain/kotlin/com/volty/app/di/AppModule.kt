package com.volty.app.di

import com.volty.app.data.ble.KableBmsRepository
import com.volty.app.data.memory.InMemoryVehicleRepository
import com.volty.app.domain.repository.BmsRepository
import com.volty.app.domain.repository.VehicleRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val appModule = module {
    singleOf(::InMemoryVehicleRepository) bind VehicleRepository::class
    singleOf(::KableBmsRepository) bind BmsRepository::class
}

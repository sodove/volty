package com.volty.app.domain.repository

import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow

interface VehicleRepository {
    val vehicles: Flow<List<Vehicle>>
    suspend fun get(id: String): Vehicle?
    suspend fun upsert(vehicle: Vehicle)
    suspend fun delete(id: String)
    suspend fun touch(id: String)
}

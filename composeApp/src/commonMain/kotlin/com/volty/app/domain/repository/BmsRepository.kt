package com.volty.app.domain.repository

import com.volty.app.data.stats.MovingAvg
import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val bmsType: BmsType,
    val knownVehicle: Vehicle? = null
)

interface BmsRepository {
    val activeData: StateFlow<BmsData>
    val activeVehicle: StateFlow<Vehicle?>
    val connectionState: StateFlow<ConnectionState>

    fun scanAll(): Flow<DiscoveredDevice>
    suspend fun connect(vehicle: Vehicle): Result<Unit>
    suspend fun connectGuest(address: String, type: BmsType): Result<Unit>
    suspend fun disconnect()

    fun samples(window: Duration): Flow<List<BmsData>>
    fun movingAverage(window: Duration): StateFlow<MovingAvg>
}

package com.volty.app.domain.repository

import com.volty.app.domain.model.BmsData
import com.volty.app.domain.model.BmsType
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.model.Vehicle
import com.volty.app.domain.stats.MovingAvg
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

    /**
     * Cold flow of [MovingAvg] over the given [window], emitting on each new
     * sample. Callers should [kotlinx.coroutines.flow.stateIn] this into their
     * own [kotlinx.coroutines.CoroutineScope] so the collector is cancelled
     * with the consumer's lifecycle.
     */
    fun movingAverage(window: Duration): Flow<MovingAvg>
}

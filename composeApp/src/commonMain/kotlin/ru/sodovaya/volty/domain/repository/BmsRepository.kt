package ru.sodovaya.volty.domain.repository

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ConnectionState
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.stats.MovingAvg
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

data class DiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** Auto-detected BMS type, or `null` when the scanner did not recognize the device. */
    val bmsType: BmsType?,
    val knownVehicle: Vehicle? = null
)

interface BmsRepository {
    val activeData: StateFlow<BmsData>
    val activeVehicle: StateFlow<Vehicle?>
    val connectionState: StateFlow<ConnectionState>

    fun scanAll(): Flow<DiscoveredDevice>
    suspend fun connect(vehicle: Vehicle): Result<Unit>
    suspend fun connectGuest(address: String, type: BmsType): Result<Unit>

    /**
     * Connect to a simulated BMS. No BLE is involved: a [ru.sodovaya.volty.data.demo.DemoBmsSimulator]
     * feeds synthetic data through the same pipeline (Dashboard / Graph / cells /
     * notification) so reviewers and new users can exercise the full UI without
     * hardware. The synthetic demo vehicle (see [ru.sodovaya.volty.domain.model.isDemo])
     * is NEVER persisted.
     */
    suspend fun connectDemo(): Result<Unit>

    suspend fun disconnect()

    fun samples(window: Duration): Flow<List<BmsData>>

    /**
     * Cold flow of [MovingAvg] over the given [window], emitting on each new
     * sample. Callers should [kotlinx.coroutines.flow.stateIn] this into their
     * own [kotlinx.coroutines.CoroutineScope] so the collector is cancelled
     * with the consumer's lifecycle.
     */
    fun movingAverage(window: Duration): Flow<MovingAvg>

    /**
     * Called when the app comes back to the foreground. The repo verifies
     * sample freshness and forces a reconnect if the in-session watchdog didn't
     * catch a background drop (Doze, App-Standby, foreground service killed).
     * Idempotent — safe to call on every lifecycle ON_START.
     */
    suspend fun onAppResumed()
}

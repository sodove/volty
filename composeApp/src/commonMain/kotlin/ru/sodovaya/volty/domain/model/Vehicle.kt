package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    /** Never empty. Single-pack batteries — the overwhelming majority — hold exactly one. */
    val packs: List<Pack>,
    val topology: PackTopology = PackTopology.PARALLEL,
    val chemistry: Chemistry,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false
) {
    init {
        require(packs.isNotEmpty()) { "Vehicle must have at least one pack" }
    }
}

/**
 * Builds a conventional one-BMS vehicle. Keeps the pre-multi-pack parameter
 * names so call sites read the same as before.
 */
@OptIn(ExperimentalTime::class)
fun singlePackVehicle(
    id: String,
    name: String,
    iconKey: String,
    bmsType: BmsType,
    bmsAddress: String,
    chemistry: Chemistry,
    cellCount: Int? = null,
    averagingWindowMin: Int = 5,
    alertConfig: AlertConfig = AlertConfig(),
    createdAt: Instant,
    lastConnectedAt: Instant? = null,
    isPinned: Boolean = false
): Vehicle = Vehicle(
    id = id,
    name = name,
    iconKey = iconKey,
    packs = listOf(
        Pack(index = 0, label = name, bmsType = bmsType, bmsAddress = bmsAddress, cellCount = cellCount)
    ),
    topology = PackTopology.PARALLEL,
    chemistry = chemistry,
    averagingWindowMin = averagingWindowMin,
    alertConfig = alertConfig,
    createdAt = createdAt,
    lastConnectedAt = lastConnectedAt,
    isPinned = isPinned
)

/**
 * Primary-pack shortcuts. Every consumer that predates multi-pack support
 * reads these and keeps working unchanged: for a one-pack vehicle they are
 * the whole truth, and for a multi-pack one they describe the pack the
 * vehicle is identified and connected by.
 */
val Vehicle.primaryPack: Pack get() = packs.first()
val Vehicle.bmsType: BmsType get() = primaryPack.bmsType
val Vehicle.bmsAddress: String get() = primaryPack.bmsAddress
val Vehicle.cellCount: Int? get() = primaryPack.cellCount
val Vehicle.isMultiPack: Boolean get() = packs.size > 1

/** Cell count is auto-filled from live telemetry — see KableBmsRepository. */
fun Vehicle.withCellCount(count: Int): Vehicle =
    copy(packs = packs.mapIndexed { i, p -> if (i == 0) p.copy(cellCount = count) else p })

/**
 * Marker for transient (guest) vehicles synthesized by [BmsRepository.connectGuest].
 * Their [Vehicle.id] uses the sentinel prefix `guest:` so they are never confused
 * with persisted vehicles and never touched in the saved-vehicle store.
 */
const val GUEST_VEHICLE_ID_PREFIX: String = "guest:"

/**
 * True when this vehicle is a transient guest, not persisted in the
 * [ru.sodovaya.volty.domain.repository.VehicleRepository].
 */
val Vehicle.isGuest: Boolean get() = id.startsWith(GUEST_VEHICLE_ID_PREFIX)

/**
 * Sentinel id for the simulated "Try demo" vehicle synthesized by
 * [ru.sodovaya.volty.domain.repository.BmsRepository.connectDemo]. Like a guest,
 * it is never written to the saved-vehicle store.
 */
const val DEMO_VEHICLE_ID: String = "demo"

/**
 * True when this vehicle is the simulated demo battery (see [DEMO_VEHICLE_ID]).
 * Demo is non-persistent like a guest, but distinct: it has no real BLE device
 * behind it at all.
 */
val Vehicle.isDemo: Boolean get() = id == DEMO_VEHICLE_ID

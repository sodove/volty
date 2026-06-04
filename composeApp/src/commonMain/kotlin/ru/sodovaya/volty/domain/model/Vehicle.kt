package ru.sodovaya.volty.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class Vehicle(
    val id: String,
    val name: String,
    val iconKey: String,
    val bmsType: BmsType,
    val bmsAddress: String,
    val chemistry: Chemistry,
    val cellCount: Int? = null,
    val averagingWindowMin: Int = 5,
    val alertConfig: AlertConfig = AlertConfig(),
    val createdAt: Instant,
    val lastConnectedAt: Instant? = null,
    val isPinned: Boolean = false
)

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
 * it is never written to the saved-vehicle store, never offered for add-battery
 * prefill, and never auto-saved.
 */
const val DEMO_VEHICLE_ID: String = "demo"

/**
 * True when this vehicle is the simulated demo battery (see [DEMO_VEHICLE_ID]).
 * Demo is non-persistent like a guest, but distinct: it has no real BLE device
 * behind it at all.
 */
val Vehicle.isDemo: Boolean get() = id == DEMO_VEHICLE_ID

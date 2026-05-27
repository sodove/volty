package com.volty.app.domain.model

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
 * [com.volty.app.domain.repository.VehicleRepository].
 */
val Vehicle.isGuest: Boolean get() = id.startsWith(GUEST_VEHICLE_ID_PREFIX)

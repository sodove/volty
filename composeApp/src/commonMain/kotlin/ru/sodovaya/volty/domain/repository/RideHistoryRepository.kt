package ru.sodovaya.volty.domain.repository

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
data class RideSummary(
    val id: String,
    val vehicleId: String,
    val startedAt: Instant,
    val endedAt: Instant?
)

@OptIn(ExperimentalTime::class)
data class RidePoint(
    val metricKey: String,
    val timestamp: Instant,
    val value: Float,
    val cellIndex: Int? = null,
    val isKnown: Boolean = true
)

@OptIn(ExperimentalTime::class)
data class StoredRide(
    val summary: RideSummary,
    val points: List<RidePoint>
)

interface RideHistoryRepository {
    suspend fun startRide(summary: RideSummary)
    suspend fun appendPoint(rideId: String, point: RidePoint)
    suspend fun finishRide(rideId: String, endedAt: Instant)
    suspend fun listRides(vehicleId: String? = null): List<RideSummary>
    suspend fun loadRide(rideId: String): StoredRide?
    suspend fun deleteRide(rideId: String)
    suspend fun pruneOldest(keep: Int)
}

object NoOpRideHistoryRepository : RideHistoryRepository {
    override suspend fun startRide(summary: RideSummary) = Unit
    override suspend fun appendPoint(rideId: String, point: RidePoint) = Unit
    override suspend fun finishRide(rideId: String, endedAt: Instant) = Unit
    override suspend fun listRides(vehicleId: String?): List<RideSummary> = emptyList()
    override suspend fun loadRide(rideId: String): StoredRide? = null
    override suspend fun deleteRide(rideId: String) = Unit
    override suspend fun pruneOldest(keep: Int) = Unit
}

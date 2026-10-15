package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.data.location.LocationDemandCoordinator
import ru.sodovaya.volty.data.location.LocationDemandTicket
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.RideLocationRepository

internal typealias NearbyLocationDemandTicket = LocationDemandTicket

/** Dedicated Nearby instance; history uses a different coordinator/consumer. */
internal class NearbyLocationDemandCoordinator(repository: RideLocationRepository) {
    private val delegate = LocationDemandCoordinator(repository, LocationConsumer.NEARBY_CHANNEL_SHARING)
    val cleanupPending = delegate.cleanupPending
    fun newTicket() = delegate.newTicket()
    internal fun owns(repository: RideLocationRepository) = delegate.owns(repository)
    suspend fun acquire(ticket: NearbyLocationDemandTicket) = delegate.acquire(ticket)
    suspend fun release(ticket: NearbyLocationDemandTicket) = delegate.release(ticket)
}

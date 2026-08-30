package ru.sodovaya.volty.data.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.RideLocationFix
import ru.sodovaya.volty.domain.location.RideLocationRepository
import ru.sodovaya.volty.domain.location.RideLocationStatus
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSnapshot

/** Social adapter: sharing owns only the SOCIAL_SHARING demand. */
class AndroidLocationProvider(
    private val locationRepository: RideLocationRepository,
) : LocationProvider {
    override val requiredPermissions: List<String> = locationRepository.requiredPermissions
    override val updates: Flow<LocationSnapshot> = locationRepository.state
        .filter { LocationConsumer.SOCIAL_SHARING in it.demands }
        .map { it.status }
        .filterIsInstance<RideLocationStatus.Available>()
        .map { it.fix.toSnapshot() }

    override suspend fun start() {
        locationRepository.setDemand(LocationConsumer.SOCIAL_SHARING, enabled = true)
    }

    override suspend fun stop() {
        locationRepository.setDemand(LocationConsumer.SOCIAL_SHARING, enabled = false)
    }

    private fun RideLocationFix.toSnapshot() = LocationSnapshot(
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        accuracyMeters = accuracyMeters,
        capturedAtEpochMillis = capturedAtEpochMillis,
        staleAfterEpochMillis = capturedAtEpochMillis + LOCATION_STALE_AFTER_MILLIS,
    )

    private companion object {
        const val LOCATION_STALE_AFTER_MILLIS = 15_000L
    }
}

internal fun registerProvidersTransactional(
    providers: List<String>,
    register: (String) -> Unit,
    unregister: (String) -> Unit,
): List<String> {
    val registered = mutableListOf<String>()
    try {
        providers.forEach { provider ->
            register(provider)
            registered += provider
        }
        return registered
    } catch (error: Throwable) {
        registered.asReversed().forEach { provider ->
            runCatching { unregister(provider) }
        }
        throw error
    }
}

internal fun isLocationFromCurrentGeneration(capturedAtEpochMillis: Long, generationEpochMillis: Long): Boolean =
    capturedAtEpochMillis >= generationEpochMillis

internal fun shouldAcceptLocationUpdate(
    updateGeneration: Long,
    currentGeneration: Long,
    isStarted: Boolean,
): Boolean = isStarted && updateGeneration == currentGeneration

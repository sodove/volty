package ru.sodovaya.volty.domain.location

import kotlinx.coroutines.flow.StateFlow

interface RideLocationRepository {
    val requiredPermissions: List<String>
    val state: StateFlow<RideLocationState>
    suspend fun setDemand(consumer: LocationConsumer, enabled: Boolean)
    suspend fun refreshPermissionAndProviders()
}

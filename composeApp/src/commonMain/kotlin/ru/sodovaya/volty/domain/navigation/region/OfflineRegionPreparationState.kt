package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant

enum class OfflinePreparationTrigger {
    FIRST_MAP_OPEN,
    SETTINGS,
}

/** State of one navigation artifact, intentionally independent from the map pack. */
sealed interface OfflineNavigationComponentState {
    data object Missing : OfflineNavigationComponentState
    data object Preparing : OfflineNavigationComponentState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long?) : OfflineNavigationComponentState
    data object Ready : OfflineNavigationComponentState
    data object Paused : OfflineNavigationComponentState
    data class Failed(val code: String) : OfflineNavigationComponentState
}

data class OfflineRegionPreparationState(
    val regionId: String,
    val map: OfflineMapPackState,
    val search: OfflineNavigationComponentState,
    val routing: OfflineNavigationComponentState,
)

interface OfflineRegionPreparationCoordinator {
    val states: StateFlow<List<OfflineRegionPreparationState>>
    suspend fun prepareCurrentRegion(coordinate: GeoCoordinate, style: OfflineMapStyleVariant)
    suspend fun prepareExplicitRegion(regionId: String, style: OfflineMapStyleVariant)

    suspend fun retryPending() = Unit

    /** Same explicit action after the user approved a metered transfer. */
    suspend fun prepareExplicitRegionConfirmed(regionId: String, style: OfflineMapStyleVariant) {
        prepareExplicitRegion(regionId, style)
    }
    suspend fun pause(regionId: String)
    suspend fun retry(regionId: String)
}

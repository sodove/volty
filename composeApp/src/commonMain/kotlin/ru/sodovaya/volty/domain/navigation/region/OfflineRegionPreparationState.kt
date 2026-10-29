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

/**
 * What the offline data says about where the rider currently is.  It is
 * information only: nothing is downloaded because of it.
 */
sealed interface OfflineCurrentCoverage {
    /** No fix yet, or the catalog has not named a cell for it. */
    data object Unknown : OfflineCurrentCoverage

    /** The cell holding the fix is installed. */
    data class Covered(val regionId: String) : OfflineCurrentCoverage

    /** The cell holding the fix is not on the device: search and routing need a connection here. */
    data class NotAvailable(val regionId: String) : OfflineCurrentCoverage
}

object OfflineCurrentCoveragePolicy {
    /** Installed regions win over catalog cells that merely overlap the fix. */
    fun cellFor(coordinate: GeoCoordinate, states: List<OfflineRegionPackageState>): OfflineRegionPackageState? {
        val containing = states.filter { it.region.bounds.contains(coordinate) }
        return containing.filter { it.isInstalled }.minByOrNull { it.region.regionId }
            ?: containing.minByOrNull { it.region.regionId }
    }

    fun resolve(coordinate: GeoCoordinate, states: List<OfflineRegionPackageState>): OfflineCurrentCoverage {
        val cell = cellFor(coordinate, states) ?: return OfflineCurrentCoverage.Unknown
        return if (cell.isInstalled) OfflineCurrentCoverage.Covered(cell.region.regionId)
        else OfflineCurrentCoverage.NotAvailable(cell.region.regionId)
    }
}

interface OfflineRegionPreparationCoordinator {
    val states: StateFlow<List<OfflineRegionPreparationState>>

    /** Updated by [prepareCurrentRegion]; drives the honest "not available offline" notice. */
    val currentCoverage: StateFlow<OfflineCurrentCoverage>

    /**
     * Called on every map open with the rider's fix.  It only ever prepares the
     * MAP PACK of a region that is already installed.  It never asks the
     * repository to download or build a cell: an earlier revision did, which
     * silently downloaded neighbouring on-demand cells over Wi-Fi and raised a
     * mobile-data prompt for them.  Downloading is an explicit action
     * ([prepareExplicitRegion]).
     */
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

package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

/** Platform bridge for the installed Valhalla/search/map files of one region. */
interface OfflineRegionRuntime {
    suspend fun search(
        regionId: String,
        request: OfflineGeocoderRequest,
    ): NavigationResult<List<PlaceCandidate>>

    suspend fun routes(
        regionId: String,
        request: RouteRequest,
    ): NavigationResult<RoutePlan>
}

/** Reports the current connectivity class without making the domain Android-aware. */
fun interface OfflineNetworkStatus {
    fun current(): OfflineNetworkAvailability
}

/**
 * Chooses installed regional data first and keeps online parity while a missing
 * region downloads in the background. Search, route, and map callers can share
 * the same package repository and therefore cannot start duplicate downloads.
 */
class OfflineFirstNavigationRepository(
    private val online: NavigationRepository,
    private val packages: OfflineRegionPackageRepository,
    private val runtime: OfflineRegionRuntime,
    private val network: OfflineNetworkStatus,
    private val preferences: () -> OfflineDownloadPreferences,
    private val downloadScope: CoroutineScope,
) : NavigationRepository {
    private val scheduledDownloads = mutableSetOf<String>()
    private val catalogRefreshLock = Any()
    private var catalogRefreshAttempted = false
    private var catalogRefreshJob: Job? = null

    override suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> {
        val catalogRefresh = ensureCatalog()
        val request = OfflineGeocoderRequestPolicy.create(query, near, languageTag)
        if (request == null || near == null) return online.search(query, near, languageTag)

        return when (
            val decision = access(
                points = listOf(near),
                trigger = OfflineRegionDownloadTrigger.SEARCH,
            )
        ) {
            is OfflineRegionAccessDecision.UseOffline -> runtime.search(decision.regionId, request)
            is OfflineRegionAccessDecision.StartDownload -> {
                scheduleDownload(decision.regionId, decision.trigger)
                online.search(query, near, languageTag)
            }
            is OfflineRegionAccessDecision.WaitForDownload,
            is OfflineRegionAccessDecision.RequestMeteredApproval
            -> online.search(query, near, languageTag)
            is OfflineRegionAccessDecision.UseOnlineFallback -> {
                if (decision.missingRegionIds.isEmpty()) {
                    scheduleDownloadAfterCatalogRefresh(
                        refreshJob = catalogRefresh,
                        points = listOf(near),
                        trigger = OfflineRegionDownloadTrigger.SEARCH,
                    )
                } else {
                    scheduleMissingRegionDownload(decision, OfflineRegionDownloadTrigger.SEARCH)
                }
                online.search(query, near, languageTag)
            }
            OfflineRegionAccessDecision.UnavailableOffline ->
                NavigationResult.Failure(NavigationFailure.Offline)
        }
    }

    override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
        val catalogRefresh = ensureCatalog()
        return when (
            val decision = access(
                points = listOf(request.origin, request.destination.coordinate),
                trigger = OfflineRegionDownloadTrigger.ROUTE,
            )
        ) {
            is OfflineRegionAccessDecision.UseOffline -> runtime.routes(decision.regionId, request)
            is OfflineRegionAccessDecision.StartDownload -> {
                scheduleDownload(decision.regionId, decision.trigger)
                online.routes(request)
            }
            is OfflineRegionAccessDecision.RequestMeteredApproval
            -> online.routes(request)
            is OfflineRegionAccessDecision.UseOnlineFallback -> {
                if (decision.missingRegionIds.isEmpty()) {
                    scheduleDownloadAfterCatalogRefresh(
                        refreshJob = catalogRefresh,
                        points = listOf(request.origin, request.destination.coordinate),
                        trigger = OfflineRegionDownloadTrigger.ROUTE,
                    )
                } else {
                    scheduleMissingRegionDownload(decision, OfflineRegionDownloadTrigger.ROUTE)
                }
                online.routes(request)
            }
            is OfflineRegionAccessDecision.WaitForDownload -> if (
                network.current() == OfflineNetworkAvailability.OFFLINE
            ) {
                NavigationResult.Failure(NavigationFailure.Offline)
            } else {
                online.routes(request)
            }
            OfflineRegionAccessDecision.UnavailableOffline ->
                NavigationResult.Failure(NavigationFailure.Offline)
        }
    }

    /**
     * Application startup refreshes the catalog in the background. This
     * second guard closes the small race where the first search/route arrives
     * before startup refresh finishes. The request never waits for the catalog
     * network timeout; it continues through the online path immediately.
     */
    private fun ensureCatalog(): Job? {
        if (packages.states.value.isNotEmpty()) return null
        if (network.current() == OfflineNetworkAvailability.OFFLINE) return null
        return synchronized(catalogRefreshLock) {
            if (packages.states.value.isNotEmpty()) return@synchronized null
            catalogRefreshJob?.takeIf { it.isActive }?.let { return@synchronized it }
            if (catalogRefreshAttempted) return@synchronized null
            catalogRefreshAttempted = true
            downloadScope.launch {
                var refreshed = false
                try {
                    packages.refreshCatalog()
                    refreshed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A transient catalog/network failure must not permanently
                    // disable automatic region discovery for this process.
                } finally {
                    synchronized(catalogRefreshLock) {
                        if (!refreshed) catalogRefreshAttempted = false
                    }
                }
            }.also { catalogRefreshJob = it }
        }
    }

    private fun access(
        points: List<GeoCoordinate>,
        trigger: OfflineRegionDownloadTrigger,
    ): OfflineRegionAccessDecision = OfflineRegionAccessPolicy.decide(
        points = points,
        packages = packages.states.value.map { state ->
            OfflineRegionPackageSnapshot(state.region, state.status)
        },
        network = network.current(),
        trigger = trigger,
        preferences = preferences(),
        allowOnlineFallback = true,
    )

    private fun scheduleDownload(
        regionId: String,
        trigger: OfflineRegionDownloadTrigger,
    ) {
        synchronized(scheduledDownloads) {
            if (!scheduledDownloads.add(regionId)) return
        }
        downloadScope.launch {
            try {
                packages.requestDownload(regionId, trigger)
            } finally {
                synchronized(scheduledDownloads) { scheduledDownloads.remove(regionId) }
            }
        }
    }

    private fun scheduleMissingRegionDownload(
        decision: OfflineRegionAccessDecision.UseOnlineFallback,
        trigger: OfflineRegionDownloadTrigger,
    ) {
        decision.missingRegionIds.firstOrNull()?.let { regionId ->
            scheduleDownload(regionId, trigger)
        }
    }

    private fun scheduleDownloadAfterCatalogRefresh(
        refreshJob: Job?,
        points: List<GeoCoordinate>,
        trigger: OfflineRegionDownloadTrigger,
    ) {
        refreshJob ?: return
        downloadScope.launch {
            refreshJob.join()
            when (val decision = access(points, trigger)) {
                is OfflineRegionAccessDecision.StartDownload ->
                    scheduleDownload(decision.regionId, decision.trigger)

                is OfflineRegionAccessDecision.UseOnlineFallback ->
                    scheduleMissingRegionDownload(decision, trigger)

                else -> Unit
            }
        }
    }
}

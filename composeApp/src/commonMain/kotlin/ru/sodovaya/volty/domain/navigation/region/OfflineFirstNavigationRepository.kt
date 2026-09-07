package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.PlaceCandidateDeduplicationPolicy
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

/** Platform bridge for the installed Valhalla/search files of one region. */
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

/** Reports validated connectivity without making the domain Android-aware. */
fun interface OfflineNetworkStatus {
    fun current(): OfflineNetworkAvailability

    val changes: Flow<OfflineNetworkAvailability>
        get() = emptyFlow()
}

/**
 * Resolves installed regional search/routing data before using the online
 * adapter. Requests never acquire a package or refresh a catalog: preparation
 * is owned by the coordinator and explicit Settings actions.
 */
class OfflineFirstNavigationRepository(
    private val online: NavigationRepository,
    private val packages: OfflineRegionPackageRepository,
    private val runtime: OfflineRegionRuntime,
    private val network: OfflineNetworkStatus,
    @Suppress("UNUSED_PARAMETER") private val preferences: () -> OfflineDownloadPreferences,
    @Suppress("UNUSED_PARAMETER") private val downloadScope: CoroutineScope,
) : NavigationRepository {
    override suspend fun search(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> {
        val request = OfflineGeocoderRequestPolicy.create(query, near, languageTag)
            ?: return online.search(query, near, languageTag)
        if (near == null) return searchInstalledRegions(request, query, languageTag)

        val localRegion = installedRegionCovering(listOf(near))
            ?: return onlineOrOfflineSearch(query, near, languageTag)
        return when (val local = runtime.search(localRegion.region.regionId, request)) {
            is NavigationResult.Success -> {
                when (OfflineResourceFallbackPolicy.decide(
                    localCoverage = true,
                    network = network.current(),
                    localFailure = if (local.value.isEmpty()) LocalFailure.Empty else LocalFailure.None,
                    resource = OfflineResourceKind.SEARCH,
                )) {
                    OfflineResourceDecision.UseOnline -> online.search(query, near, languageTag)
                    OfflineResourceDecision.UseLocal,
                    OfflineResourceDecision.Unavailable -> local
                }
            }
            is NavigationResult.Failure -> {
                when (OfflineResourceFallbackPolicy.decide(
                    localCoverage = true,
                    network = network.current(),
                    localFailure = local.reason.toLocalFailure(),
                    resource = OfflineResourceKind.SEARCH,
                )) {
                    OfflineResourceDecision.UseOnline -> online.search(query, near, languageTag)
                    OfflineResourceDecision.UseLocal,
                    OfflineResourceDecision.Unavailable -> local
                }
            }
        }
    }

    override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
        val localRegion = installedRegionCovering(
            listOf(request.origin, request.destination.coordinate),
        ) ?: return onlineOrOfflineRoute(request)

        return when (val local = runtime.routes(localRegion.region.regionId, request)) {
            is NavigationResult.Success -> local
            is NavigationResult.Failure -> when (OfflineResourceFallbackPolicy.decide(
                localCoverage = true,
                network = network.current(),
                localFailure = local.reason.toLocalFailure(),
                resource = OfflineResourceKind.ROUTING,
            )) {
                // A local graph authoritatively says that this corridor has no
                // route. Do not mask that with an unbounded online retry.
                OfflineResourceDecision.UseLocal,
                OfflineResourceDecision.Unavailable -> local
                OfflineResourceDecision.UseOnline -> online.routes(request)
            }
        }
    }

    private suspend fun searchInstalledRegions(
        request: OfflineGeocoderRequest,
        rawQuery: String,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> {
        val regionIds = packages.states.value
            .asSequence()
            .filter { it.status == OfflineRegionPackageStatus.READY ||
                it.status == OfflineRegionPackageStatus.UPDATE_AVAILABLE }
            .map { it.region.regionId }
            .distinct()
            .sorted()
            .toList()
        if (regionIds.isEmpty()) return onlineOrOfflineSearch(rawQuery, null, languageTag)

        val results = coroutineScope {
            regionIds.map { regionId -> async { runtime.search(regionId, request) } }.awaitAll()
        }
        val candidates = results.filterIsInstance<NavigationResult.Success<List<PlaceCandidate>>>()
            .flatMap { result ->
                PlaceCandidateDeduplicationPolicy.deduplicate(result.value, request.query.limit)
            }
        if (candidates.isNotEmpty()) {
            return NavigationResult.Success(
                PlaceCandidateDeduplicationPolicy.deduplicate(
                    candidates,
                    request.query.limit,
                    mergeSameTitle = false,
                ),
            )
        }
        // No local match is a valid empty answer while offline; with a
        // validated network the online provider may know a newer place.
        if (network.current() != OfflineNetworkAvailability.OFFLINE) {
            return online.search(rawQuery, null, languageTag)
        }
        if (results.all { it is NavigationResult.Success }) {
            return NavigationResult.Success(emptyList())
        }
        return results.filterIsInstance<NavigationResult.Failure>().firstOrNull()
            ?: NavigationResult.Failure(NavigationFailure.Offline)
    }

    private suspend fun onlineOrOfflineSearch(
        query: String,
        near: GeoCoordinate?,
        languageTag: String,
    ): NavigationResult<List<PlaceCandidate>> =
        if (network.current() == OfflineNetworkAvailability.OFFLINE) {
            NavigationResult.Failure(NavigationFailure.Offline)
        } else {
            online.search(query, near, languageTag)
        }

    private suspend fun onlineOrOfflineRoute(
        request: RouteRequest,
    ): NavigationResult<RoutePlan> =
        if (network.current() == OfflineNetworkAvailability.OFFLINE) {
            NavigationResult.Failure(NavigationFailure.Offline)
        } else {
            online.routes(request)
        }

    private fun installedRegionCovering(points: List<GeoCoordinate>): OfflineRegionPackageState? =
        packages.states.value
            .asSequence()
            .filter { state ->
                (state.status == OfflineRegionPackageStatus.READY ||
                    state.status == OfflineRegionPackageStatus.UPDATE_AVAILABLE) &&
                    points.all(state.region.bounds::contains)
            }
            .sortedBy { it.region.regionId }
            .firstOrNull()

    private fun NavigationFailure.isRetryableLocal(): Boolean = when (this) {
        NavigationFailure.Offline,
        NavigationFailure.ProviderUnavailable,
        NavigationFailure.MalformedResponse -> true
        NavigationFailure.NoRoute,
        is NavigationFailure.RateLimited,
        is NavigationFailure.InvalidRequest -> false
    }

    private fun NavigationFailure.toLocalFailure(): LocalFailure = when {
        this == NavigationFailure.NoRoute -> LocalFailure.NoRoute
        isRetryableLocal() -> LocalFailure.Retryable
        else -> LocalFailure.None
    }
}

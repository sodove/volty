package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.NavigationFailure
import ru.sodovaya.volty.domain.navigation.NavigationRepository
import ru.sodovaya.volty.domain.navigation.NavigationResult
import ru.sodovaya.volty.domain.navigation.PlaceCandidate
import ru.sodovaya.volty.domain.navigation.RoutePlan
import ru.sodovaya.volty.domain.navigation.RouteRequest

class OfflineFirstNavigationRepositoryTest {
    @Test
    fun first_request_during_catalog_refresh_queues_the_current_region_after_refresh() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val packages = FakePackages(
            catalogLoadedOnStart = false,
            catalogRefreshGate = refreshGate,
        )
        val online = FakeNavigation()
        val repository = repository(packages, online)

        val result = repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")

        assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result)
        assertEquals(1, online.searchCalls)
        assertEquals(emptyList(), packages.downloads)

        refreshGate.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("ekb"), packages.downloads)
    }

    @Test
    fun failed_catalog_refresh_can_be_retried_for_a_later_request() = runTest {
        val packages = FakePackages(
            catalogLoadedOnStart = false,
            catalogRefreshFailures = 1,
        )
        val online = FakeNavigation()
        val repository = repository(packages, online)

        repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()
        assertEquals(emptyList(), packages.downloads)

        repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()

        assertEquals(listOf("ekb"), packages.downloads)
        assertEquals(2, online.searchCalls)
    }

    @Test
    fun missing_region_starts_one_background_download_and_keeps_online_search_available() = runTest {
        val packages = FakePackages()
        val online = FakeNavigation()
        val repository = repository(packages, online)

        val result = repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()

        assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result)
        assertEquals(1, online.searchCalls)
        assertEquals(listOf("ekb"), packages.downloads)
    }

    @Test
    fun installed_region_is_served_by_the_offline_runtime_without_online_route_call() = runTest {
        val packages = FakePackages(OfflineRegionPackageStatus.READY)
        val online = FakeNavigation()
        val runtime = FakeRuntime()
        val repository = repository(packages, online, runtime)
        val request = routeRequest()

        val result = repository.routes(request)

        assertIs<NavigationResult.Success<RoutePlan>>(result)
        assertEquals(1, runtime.routeCalls)
        assertEquals(0, online.routeCalls)
    }

    @Test
    fun metered_missing_region_stays_online_and_enters_download_queue_for_confirmation() = runTest {
        val packages = FakePackages()
        val online = FakeNavigation()
        val repository = repository(
            packages,
            online,
            network = OfflineNetworkAvailability.METERED,
        )

        val result = repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()

        assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result)
        assertEquals(1, online.searchCalls)
        assertEquals(listOf("ekb"), packages.downloads)
    }

    @Test
    fun missing_region_in_full_offline_mode_does_not_call_online() = runTest {
        val packages = FakePackages()
        val online = FakeNavigation()
        val repository = repository(packages, online, network = OfflineNetworkAvailability.OFFLINE)

        val result = repository.routes(routeRequest())

        assertEquals(NavigationFailure.Offline, assertIs<NavigationResult.Failure>(result).reason)
        assertEquals(0, online.routeCalls)
    }

    private fun repository(
        packages: FakePackages,
        online: FakeNavigation,
        runtime: FakeRuntime = FakeRuntime(),
        network: OfflineNetworkAvailability = OfflineNetworkAvailability.UNMETERED,
    ) = OfflineFirstNavigationRepository(
        online = online,
        packages = packages,
        runtime = runtime,
        network = OfflineNetworkStatus { network },
        preferences = { OfflineDownloadPreferences() },
        downloadScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun routeRequest() = RouteRequest(
        origin = GeoCoordinate(56.84, 60.61),
        destination = PlaceCandidate("dest", "Плотинка", null, GeoCoordinate(56.85, 60.62)),
        languageTag = "ru-RU",
    )

    private class FakePackages(
        private val status: OfflineRegionPackageStatus = OfflineRegionPackageStatus.NOT_INSTALLED,
        catalogLoadedOnStart: Boolean = true,
        private val catalogRefreshGate: CompletableDeferred<Unit>? = null,
        private var catalogRefreshFailures: Int = 0,
    ) : OfflineRegionPackageRepository {
        private val region = OfflineRegionManifest(
            "ekb",
            "Екатеринбург",
            OfflineRegionBounds(56.0, 59.0, 57.5, 62.0),
        )
        private val _states = MutableStateFlow(
            if (catalogLoadedOnStart) {
                listOf(OfflineRegionPackageState(region, null, status))
            } else {
                emptyList()
            },
        )
        val downloads = mutableListOf<String>()
        override val states = _states

        override suspend fun refreshCatalog() {
            catalogRefreshGate?.await()
            if (catalogRefreshFailures > 0) {
                catalogRefreshFailures -= 1
                throw IOException("temporary catalog failure")
            }
            if (_states.value.isEmpty()) {
                _states.value = listOf(OfflineRegionPackageState(region, null, status))
            }
        }

        override suspend fun requestDownload(
            regionId: String,
            trigger: OfflineRegionDownloadTrigger,
            meteredConfirmed: Boolean,
        ) {
            downloads += regionId
        }

        override suspend fun pauseDownload(regionId: String) = Unit
        override suspend fun resumeDownload(regionId: String) = Unit
        override suspend fun deletePackage(regionId: String) = Unit
    }

    private class FakeNavigation : NavigationRepository {
        var searchCalls = 0
        var routeCalls = 0
        override suspend fun search(query: String, near: GeoCoordinate?, languageTag: String): NavigationResult<List<PlaceCandidate>> {
            searchCalls++
            return NavigationResult.Success(emptyList())
        }
        override suspend fun routes(request: RouteRequest): NavigationResult<RoutePlan> {
            routeCalls++
            return NavigationResult.Failure(NavigationFailure.ProviderUnavailable)
        }
    }

    private class FakeRuntime : OfflineRegionRuntime {
        var routeCalls = 0
        override suspend fun search(regionId: String, request: OfflineGeocoderRequest): NavigationResult<List<PlaceCandidate>> =
            NavigationResult.Success(emptyList())
        override suspend fun routes(regionId: String, request: RouteRequest): NavigationResult<RoutePlan> {
            routeCalls++
            return NavigationResult.Success(RoutePlan(request.destination, listOf(route())))
        }
        private fun route() = ru.sodovaya.volty.domain.navigation.RouteAlternative(
            "offline", 100.0, 10L,
            listOf(GeoCoordinate(56.84, 60.61), GeoCoordinate(56.85, 60.62)),
            listOf(ru.sodovaya.volty.domain.navigation.RouteManeuver(
                "arrive", ru.sodovaya.volty.domain.navigation.ManeuverKind.ARRIVE,
                "Прибыли", null, 1, 0.0,
            )),
        )
    }
}

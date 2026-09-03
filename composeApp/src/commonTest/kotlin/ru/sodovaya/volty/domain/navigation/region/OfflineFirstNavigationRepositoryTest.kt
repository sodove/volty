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
    fun installed_region_does_not_suppress_catalog_refresh_after_a_failure() = runTest {
        val packages = FakePackages(
            status = OfflineRegionPackageStatus.READY,
            catalogRefreshFailures = 1,
        )
        val online = FakeNavigation()
        val repository = repository(packages, online)

        repository.routes(routeRequest())
        testScheduler.runCurrent()
        repository.routes(routeRequest())
        testScheduler.runCurrent()

        assertEquals(2, packages.catalogRefreshCalls)
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
    fun search_without_a_location_uses_every_installed_region() = runTest {
        val packages = FakePackages(
            status = OfflineRegionPackageStatus.READY,
            regionIds = listOf("ekb", "tyumen"),
        )
        val online = FakeNavigation()
        val runtime = FakeRuntime()
        val repository = repository(packages, online, runtime)

        val result = repository.search("Плотинка", near = null, languageTag = "ru-RU")

        assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result)
        assertEquals(listOf("ekb", "tyumen"), runtime.searchRegions)
        assertEquals(
            listOf("ekb:place", "shared", "tyumen:place"),
            assertIs<NavigationResult.Success<List<PlaceCandidate>>>(result).value.map { it.id },
        )
        assertEquals(0, online.searchCalls)
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
    fun next_request_retries_a_download_that_was_waiting_for_network() = runTest {
        val packages = FakePackages(status = OfflineRegionPackageStatus.WAITING_FOR_NETWORK)
        val online = FakeNavigation()
        val repository = repository(packages, online)

        repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()

        assertEquals(listOf("ekb"), packages.downloads)
    }

    @Test
    fun next_request_does_not_resume_a_download_paused_by_the_rider() = runTest {
        val packages = FakePackages(status = OfflineRegionPackageStatus.PAUSED)
        val online = FakeNavigation()
        val repository = repository(packages, online)

        repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")
        testScheduler.runCurrent()

        assertEquals(emptyList(), packages.downloads)
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

    @Test
    fun waiting_search_in_full_offline_mode_does_not_call_online() = runTest {
        val packages = FakePackages(status = OfflineRegionPackageStatus.WAITING_FOR_NETWORK)
        val online = FakeNavigation()
        val repository = repository(packages, online, network = OfflineNetworkAvailability.OFFLINE)

        val result = repository.search("Плотинка", GeoCoordinate(56.84, 60.61), "ru-RU")

        assertEquals(NavigationFailure.Offline, assertIs<NavigationResult.Failure>(result).reason)
        assertEquals(0, online.searchCalls)
        assertEquals(emptyList(), packages.downloads)
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
        private val regionIds: List<String> = listOf("ekb"),
    ) : OfflineRegionPackageRepository {
        private val _states = MutableStateFlow(
            if (catalogLoadedOnStart) {
                regionIds.map { regionId ->
                    OfflineRegionPackageState(
                        OfflineRegionManifest(
                            regionId,
                            regionId,
                            OfflineRegionBounds(56.0, 59.0, 57.5, 62.0),
                        ),
                        null,
                        status,
                    )
                }
            } else {
                emptyList()
            },
        )
        val downloads = mutableListOf<String>()
        var catalogRefreshCalls = 0
        override val states = _states

        override suspend fun refreshCatalog() {
            catalogRefreshCalls += 1
            catalogRefreshGate?.await()
            if (catalogRefreshFailures > 0) {
                catalogRefreshFailures -= 1
                throw IOException("temporary catalog failure")
            }
            if (_states.value.isEmpty()) {
                _states.value = regionIds.map { regionId ->
                    OfflineRegionPackageState(
                        OfflineRegionManifest(
                            regionId,
                            regionId,
                            OfflineRegionBounds(56.0, 59.0, 57.5, 62.0),
                        ),
                        null,
                        status,
                    )
                }
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
        val searchRegions = mutableListOf<String>()
        override suspend fun search(regionId: String, request: OfflineGeocoderRequest): NavigationResult<List<PlaceCandidate>> =
            NavigationResult.Success(
                listOf(
                    PlaceCandidate(
                        id = "$regionId:place",
                        title = "Place",
                        subtitle = regionId,
                        coordinate = GeoCoordinate(56.84, 60.61),
                    ),
                    PlaceCandidate(
                        id = "shared",
                        title = "Shared",
                        subtitle = null,
                        coordinate = GeoCoordinate(56.85, 60.62),
                    ),
                ),
            ).also { searchRegions += regionId }
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

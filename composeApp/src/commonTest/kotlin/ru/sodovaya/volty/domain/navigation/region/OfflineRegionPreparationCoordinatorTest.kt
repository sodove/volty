package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import ru.sodovaya.volty.domain.navigation.GeoCoordinate
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackDefinition
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackKey
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackManager
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineRegionPreparationCoordinatorTest {
    @Test
    fun repeated_first_map_open_for_one_region_starts_one_map_and_navigation_request() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val coordinator = coordinator(map, packages, backgroundScope)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, map.prepareCalls)
        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun settings_is_the_only_trigger_for_a_second_region() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(
            regionState(OfflineRegionPackageStatus.NOT_INSTALLED, "first"),
            regionState(OfflineRegionPackageStatus.NOT_INSTALLED, "second"),
        )
        val coordinator = coordinator(map, packages, backgroundScope)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        assertEquals(1, packages.requestCalls)
        coordinator.prepareExplicitRegion("second", OfflineMapStyleVariant.BRIGHT)

        assertEquals(2, packages.requestCalls)
        assertEquals(listOf("first", "second"), packages.requestedIds)
    }

    @Test
    fun map_and_navigation_components_are_exposed_independently() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.DOWNLOADING))
        val coordinator = coordinator(map, packages, backgroundScope)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        val state = coordinator.states.value.single()
        assertEquals(OfflineMapPackState.Ready, state.map)
        assertIs<OfflineNavigationComponentState.Downloading>(state.search)
        assertIs<OfflineNavigationComponentState.Downloading>(state.routing)
    }

    @Test
    fun metered_network_does_not_start_native_transfer_before_approval() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(0, map.prepareCalls)
        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun explicit_metered_approval_replays_the_waiting_request() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        assertEquals(0, map.prepareCalls)
        assertEquals(1, packages.requestCalls)

        coordinator.prepareExplicitRegionConfirmed("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, map.prepareCalls)
        assertEquals(2, packages.requestCalls)
    }

    @Test
    fun retry_releases_a_failed_key_for_an_explicit_second_attempt() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.FAILED))
        val coordinator = coordinator(map, packages, backgroundScope)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        coordinator.retry("first")

        assertEquals(2, map.prepareCalls)
        assertTrue(packages.requestCalls >= 2)
    }

    @Test
    fun concurrent_first_map_opens_are_serialized_and_idempotent() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val coordinator = coordinator(map, packages, backgroundScope)

        coroutineScope {
            repeat(20) {
                launch {
                    coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
                }
            }
        }

        assertEquals(1, map.prepareCalls)
        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun blocked_first_map_open_retries_when_network_becomes_validated() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val network = FakeNetwork(OfflineNetworkAvailability.OFFLINE)
        val coordinator = coordinator(map, packages, backgroundScope, network = network)
        runCurrent()

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        assertEquals(0, map.prepareCalls)
        assertEquals(1, packages.requestCalls)

        network.availability = OfflineNetworkAvailability.UNMETERED
        network.changes.emit(OfflineNetworkAvailability.UNMETERED)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, map.prepareCalls)
        assertEquals(2, packages.requestCalls)
    }

    @Test
    fun recovery_transition_while_waiting_for_package_request_is_replayed_after_waiter_finishes() = runTest {
        val map = FakeMapManager()
        val packageRequestGate = CompletableDeferred<Unit>()
        val packages = FakePackages(
            regionState(OfflineRegionPackageStatus.NOT_INSTALLED),
            firstRequestGate = packageRequestGate,
        )
        val network = FakeNetwork(OfflineNetworkAvailability.OFFLINE)
        val coordinator = coordinator(map, packages, backgroundScope, network = network)
        runCurrent()

        val firstOpen = launch {
            coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        }
        runCurrent()
        assertEquals(1, packages.requestCalls)
        assertEquals(0, map.prepareCalls)

        network.availability = OfflineNetworkAvailability.UNMETERED
        network.changes.emit(OfflineNetworkAvailability.UNMETERED)
        runCurrent()
        assertEquals(0, map.prepareCalls)

        packageRequestGate.complete(Unit)
        firstOpen.join()
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, map.prepareCalls)
        assertEquals(2, packages.requestCalls)
    }

    @Test
    fun repeated_first_map_open_while_waiting_does_not_repeat_package_request() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val network = FakeNetwork(OfflineNetworkAvailability.OFFLINE)
        val coordinator = coordinator(map, packages, backgroundScope, network = network)
        runCurrent()

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, packages.requestCalls)
        assertEquals(0, map.prepareCalls)
    }

    @Test
    fun recovery_between_policy_check_and_pending_publication_is_not_lost() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val network = FakeNetwork(
            initial = OfflineNetworkAvailability.OFFLINE,
            recoverAfterFirstRead = true,
        )
        val coordinator = coordinator(map, packages, backgroundScope, network = network)
        runCurrent()

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        advanceUntilIdle()

        assertEquals(1, map.prepareCalls)
        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun duplicate_allowed_network_transitions_do_not_start_duplicate_map_jobs() = runTest {
        val map = FakeMapManager(CompletableDeferred())
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val network = FakeNetwork(OfflineNetworkAvailability.OFFLINE)
        val coordinator = coordinator(map, packages, backgroundScope, network = network)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        runCurrent()
        network.availability = OfflineNetworkAvailability.UNMETERED
        val first = launch { coordinator.retryPending() }
        val second = launch { coordinator.retryPending() }
        runCurrent()

        assertEquals(1, map.prepareCalls)
        map.prepareGate!!.complete(Unit)
        first.join()
        second.join()
        advanceUntilIdle()
    }

    // ---- Offline-maps fixes (0.7.60): the false "download the map" prompt ----

    @Test
    fun ready_region_on_metered_network_never_requests_a_download() = runTest {
        // Deliberately incoherent fixture: READY + METERED + the other theme not yet
        // completed. A fixture where every contributor is "normal" cannot see that the
        // network gate used to run before the "already installed" gate.
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.DARK)
        advanceUntilIdle()

        assertEquals(0, packages.requestCalls)
        assertEquals(OfflineRegionPackageStatus.READY, packages.states.value.single().status)
    }

    @Test
    fun ready_region_on_wifi_prepares_only_the_map_pack_of_the_theme_in_use() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.DARK)

        assertEquals(0, packages.requestCalls)
        assertEquals(listOf(OfflineMapStyleVariant.DARK), map.preparedStyles)
    }

    @Test
    fun ready_region_with_a_missing_map_pack_waits_for_unmetered_without_prompting() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val network = FakeNetwork(OfflineNetworkAvailability.METERED)
        val coordinator = coordinator(map, packages, backgroundScope, network = network)
        runCurrent()

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
        assertEquals(0, map.prepareCalls)
        assertEquals(0, packages.requestCalls)

        network.availability = OfflineNetworkAvailability.UNMETERED
        network.changes.emit(OfflineNetworkAvailability.UNMETERED)
        runCurrent()
        advanceUntilIdle()

        assertEquals(1, map.prepareCalls)
        assertEquals(0, packages.requestCalls)
    }

    @Test
    fun a_region_overwritten_with_a_metered_prompt_is_still_treated_as_installed() = runTest {
        // status says AWAITING_METERED_APPROVAL, installedReleaseVersion says it is on the device.
        val map = FakeMapManager()
        val packages = FakePackages(
            regionState(OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL, installed = true),
        )
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
        advanceUntilIdle()

        assertEquals(0, packages.requestCalls)
    }

    @Test
    fun completed_installed_region_is_not_touched_even_on_a_metered_network() = runTest {
        val store = InMemoryOfflineRegionPreparationStore().apply {
            markCompleted("first", OfflineMapStyleVariant.BRIGHT, "r1")
        }
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = DefaultOfflineRegionPreparationCoordinator(
            mapPacks = map, packages = packages, scope = backgroundScope, store = store,
            network = OfflineNetworkStatus { OfflineNetworkAvailability.METERED },
        )

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)

        assertEquals(0, packages.requestCalls)
        assertEquals(0, map.prepareCalls)
    }

    @Test
    fun first_map_open_never_downloads_a_cell_that_is_not_installed() = runTest {
        for (network in listOf(OfflineNetworkAvailability.UNMETERED, OfflineNetworkAvailability.METERED, OfflineNetworkAvailability.OFFLINE)) {
            val map = FakeMapManager()
            val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
            val coordinator = coordinator(map, packages, backgroundScope, network)
            coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
            advanceUntilIdle()
            assertEquals(0, packages.requestCalls, "network=$network")
            assertEquals(0, map.prepareCalls, "network=$network")
        }
    }

    @Test
    fun leaving_the_installed_cell_reports_not_available_and_returning_reports_covered() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(
            regionState(OfflineRegionPackageStatus.READY, "home", installed = true),
            regionState(OfflineRegionPackageStatus.NOT_INSTALLED, "next", bounds = OfflineRegionBounds(57.0, 60.0, 58.0, 61.0)),
        )
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

        coordinator.prepareCurrentRegion(GeoCoordinate(57.5, 60.5), OfflineMapStyleVariant.BRIGHT)
        assertEquals(OfflineCurrentCoverage.NotAvailable("next"), coordinator.currentCoverage.value)
        assertEquals(0, packages.requestCalls)

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
        assertEquals(OfflineCurrentCoverage.Covered("home"), coordinator.currentCoverage.value)
    }

    @Test
    fun an_installed_region_wins_over_an_overlapping_empty_cell() {
        val installed = regionState(OfflineRegionPackageStatus.READY, "z-installed", installed = true)
        val empty = regionState(OfflineRegionPackageStatus.NOT_INSTALLED, "a-empty")

        val cell = OfflineCurrentCoveragePolicy.cellFor(GeoCoordinate(56.5, 60.5), listOf(empty, installed))

        assertEquals("z-installed", cell?.region?.regionId)
        assertEquals(
            OfflineCurrentCoverage.Unknown,
            OfflineCurrentCoveragePolicy.resolve(GeoCoordinate(0.0, 0.0), listOf(empty, installed)),
        )
    }

    @Test
    fun settings_prepares_the_theme_the_map_is_showing_not_bright() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)
        // The map observed DARK but its pack is not complete yet (preparation interrupted).
        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.DARK)
        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(listOf(OfflineMapStyleVariant.DARK), map.preparedStyles.distinct())
    }

    // ---- Review I1: explicit actions on an installed region that is NOT current must work ----

    private fun installedButBehind(status: OfflineRegionPackageStatus) =
        // installed r0, published r1: the old release is on the device, the new one is not.
        regionState(status, installedRelease = "r0")

    @Test
    fun explicit_update_of_an_installed_region_requests_the_package() = runTest {
        val packages = FakePackages(installedButBehind(OfflineRegionPackageStatus.UPDATE_AVAILABLE))
        val coordinator = coordinator(FakeMapManager(), packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun resume_of_a_paused_update_requests_the_package() = runTest {
        val packages = FakePackages(installedButBehind(OfflineRegionPackageStatus.PAUSED))
        val coordinator = coordinator(FakeMapManager(), packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

        coordinator.retry("first")

        assertEquals(1, packages.requestCalls)
    }

    @Test
    fun retry_of_a_failed_update_requests_the_package() = runTest {
        val packages = FakePackages(installedButBehind(OfflineRegionPackageStatus.FAILED))
        val coordinator = coordinator(FakeMapManager(), packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        coordinator.retry("first")

        assertTrue(packages.requestCalls >= 2)
    }

    @Test
    fun explicit_update_on_a_metered_network_still_asks_for_approval() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(installedButBehind(OfflineRegionPackageStatus.UPDATE_AVAILABLE))
        val coordinator = coordinator(map, packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, packages.requestCalls) // the repository turns this into the approval prompt
        assertEquals(0, map.prepareCalls)      // and nothing native starts before approval
    }

    @Test
    fun opening_the_map_never_updates_or_repairs_an_installed_region() = runTest {
        for (status in listOf(
            OfflineRegionPackageStatus.UPDATE_AVAILABLE,
            OfflineRegionPackageStatus.FAILED,
            OfflineRegionPackageStatus.PAUSED,
        )) {
            val packages = FakePackages(installedButBehind(status))
            val coordinator = coordinator(FakeMapManager(), packages, backgroundScope, OfflineNetworkAvailability.UNMETERED)

            coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
            advanceUntilIdle()

            assertEquals(0, packages.requestCalls, "status=$status")
        }
    }

    @Test
    fun explicit_action_on_a_current_installed_region_does_not_request_the_package() = runTest {
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = coordinator(FakeMapManager(), packages, backgroundScope, OfflineNetworkAvailability.METERED)

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)
        coordinator.retry("first")

        assertEquals(0, packages.requestCalls)
    }

    @Test
    fun concurrent_current_region_opens_start_one_map_job() = runTest {
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.READY, installed = true))
        val coordinator = coordinator(map, packages, backgroundScope)

        coroutineScope {
            repeat(20) {
                launch { coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT) }
            }
        }

        assertEquals(1, map.prepareCalls)
        assertEquals(0, packages.requestCalls)
    }

    @Test
    fun a_stale_completion_marker_does_not_block_an_explicit_reinstall() = runTest {
        // A deleted-then-redownloaded region keeps its durable completion marker.
        // The marker may only short-circuit an INSTALLED region.
        val store = InMemoryOfflineRegionPreparationStore().apply {
            markCompleted("first", OfflineMapStyleVariant.BRIGHT, "r1")
        }
        val map = FakeMapManager()
        val packages = FakePackages(regionState(OfflineRegionPackageStatus.NOT_INSTALLED))
        val coordinator = DefaultOfflineRegionPreparationCoordinator(
            mapPacks = map, packages = packages, scope = backgroundScope, store = store,
        )

        coordinator.prepareExplicitRegion("first", OfflineMapStyleVariant.BRIGHT)

        assertEquals(1, packages.requestCalls)
        assertEquals(1, map.prepareCalls)
    }

    private fun coordinator(
        map: FakeMapManager,
        packages: FakePackages,
        scope: kotlinx.coroutines.CoroutineScope,
        networkAvailability: OfflineNetworkAvailability? = null,
        network: OfflineNetworkStatus? = networkAvailability?.let { availability -> OfflineNetworkStatus { availability } },
    ): DefaultOfflineRegionPreparationCoordinator =
        DefaultOfflineRegionPreparationCoordinator(
            mapPacks = map,
            packages = packages,
            scope = scope,
            network = network,
        )

    private class FakeNetwork(
        initial: OfflineNetworkAvailability,
        private val recoverAfterFirstRead: Boolean = false,
    ) : OfflineNetworkStatus {
        var availability = initial
        override val changes = MutableSharedFlow<OfflineNetworkAvailability>(extraBufferCapacity = 4)
        private var reads = 0
        override fun current(): OfflineNetworkAvailability {
            val observed = availability
            reads++
            if (recoverAfterFirstRead && reads == 1) {
                availability = OfflineNetworkAvailability.UNMETERED
                changes.tryEmit(OfflineNetworkAvailability.UNMETERED)
            }
            return observed
        }
    }

    private class FakeMapManager(
        val prepareGate: CompletableDeferred<Unit>? = null,
    ) : OfflineMapPackManager {
        private val _states = MutableStateFlow<List<OfflineMapPackState>>(emptyList())
        override val states = _states
        private val byKey = mutableMapOf<OfflineMapPackKey, OfflineMapPackState>()
        var prepareCalls = 0
            private set
        val preparedStyles = mutableListOf<OfflineMapStyleVariant>()
        override fun state(key: OfflineMapPackKey): OfflineMapPackState? = byKey[key]
        override suspend fun prepare(definition: OfflineMapPackDefinition) {
            prepareCalls++
            preparedStyles += definition.key.style
            prepareGate?.await()
            byKey[definition.key] = OfflineMapPackState.Ready
            _states.value = byKey.values.toList()
        }
        override suspend fun pause(key: OfflineMapPackKey) = Unit
        override suspend fun resume(key: OfflineMapPackKey) = Unit
        override suspend fun invalidate(key: OfflineMapPackKey) = Unit
        override suspend fun delete(key: OfflineMapPackKey) = Unit
    }

    private class FakePackages(
        vararg initial: OfflineRegionPackageState,
        private val firstRequestGate: CompletableDeferred<Unit>? = null,
    ) : OfflineRegionPackageRepository {
        private val _states = MutableStateFlow(initial.toList())
        override val states = _states
        var requestCalls = 0
            private set
        val requestedIds = mutableListOf<String>()
        private var firstRequestAwaited = false
        override suspend fun refreshCatalog() = Unit
        override suspend fun requestDownload(regionId: String, trigger: OfflineRegionDownloadTrigger, meteredConfirmed: Boolean) {
            requestCalls++
            requestedIds += regionId
            if (!firstRequestAwaited) {
                firstRequestAwaited = true
                firstRequestGate?.await()
            }
        }
        override suspend fun pauseDownload(regionId: String) = Unit
        override suspend fun resumeDownload(regionId: String) = Unit
        override suspend fun deletePackage(regionId: String) = Unit
    }

    private fun regionState(
        status: OfflineRegionPackageStatus,
        id: String = "first",
        installed: Boolean = false,
        installedRelease: String? = if (installed) "r1" else null,
        bounds: OfflineRegionBounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
    ) = OfflineRegionPackageState(
        region = OfflineRegionManifest(
            regionId = id,
            displayName = id,
            bounds = bounds,
            mapPack = OfflineRegionMapPackMetadata(
                styleUrls = listOf("https://tiles.openfreemap.org/styles/bright"),
                bounds = bounds,
                minZoom = 5,
                maxZoom = 13,
                ofmStyleRevision = "r1",
            ),
        ),
        latestRelease = release(id),
        status = status,
        installedReleaseVersion = installedRelease,
        downloadedBytes = if (status == OfflineRegionPackageStatus.DOWNLOADING) 1L else 0L,
    )

    private fun release(id: String) = OfflineRegionPackageManifest(
        schemaVersion = 3,
        regionId = id,
        releaseVersion = "r1",
        createdAt = "2026-09-01T00:00:00Z",
        source = OfflineRegionSource(1, "2026-09-01T00:00:00Z", "src", "https://example.com/source.pbf", "a".repeat(64)),
        compatibility = OfflineRegionCompatibility(1, "valhalla", "valhalla-3.6.3", 1),
        coverage = OfflineRegionCoverage(listOf(60.0, 56.0, 61.0, 57.0), 1),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://example.com/routing", 10, 10, "a".repeat(64), "gzip"),
            search = OfflineRegionSearchArtifact("https://example.com/search", 10, 10, "a".repeat(64), 1, "gzip"),
        ),
        signature = OfflineRegionManifestSignature("key", "ed25519", "sig"),
    )
}

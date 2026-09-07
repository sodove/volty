package ru.sodovaya.volty.domain.navigation.region

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
        coordinator.prepareCurrentRegion(GeoCoordinate(56.6, 60.6), OfflineMapStyleVariant.BRIGHT)

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

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)
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

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)

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

        coordinator.prepareCurrentRegion(GeoCoordinate(56.5, 60.5), OfflineMapStyleVariant.BRIGHT)

        assertEquals(0, map.prepareCalls)
        assertEquals(1, packages.requestCalls)
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

    private fun coordinator(
        map: FakeMapManager,
        packages: FakePackages,
        scope: kotlinx.coroutines.CoroutineScope,
        networkAvailability: OfflineNetworkAvailability? = null,
    ): DefaultOfflineRegionPreparationCoordinator =
        DefaultOfflineRegionPreparationCoordinator(
            mapPacks = map,
            packages = packages,
            scope = scope,
            network = networkAvailability?.let { availability -> OfflineNetworkStatus { availability } },
        )

    private class FakeMapManager : OfflineMapPackManager {
        private val _states = MutableStateFlow<List<OfflineMapPackState>>(emptyList())
        override val states = _states
        private val byKey = mutableMapOf<OfflineMapPackKey, OfflineMapPackState>()
        var prepareCalls = 0
            private set
        override fun state(key: OfflineMapPackKey): OfflineMapPackState? = byKey[key]
        override suspend fun prepare(definition: OfflineMapPackDefinition) {
            prepareCalls++
            byKey[definition.key] = OfflineMapPackState.Ready
            _states.value = byKey.values.toList()
        }
        override suspend fun pause(key: OfflineMapPackKey) = Unit
        override suspend fun resume(key: OfflineMapPackKey) = Unit
        override suspend fun invalidate(key: OfflineMapPackKey) = Unit
        override suspend fun delete(key: OfflineMapPackKey) = Unit
    }

    private class FakePackages(vararg initial: OfflineRegionPackageState) : OfflineRegionPackageRepository {
        private val _states = MutableStateFlow(initial.toList())
        override val states = _states
        var requestCalls = 0
            private set
        val requestedIds = mutableListOf<String>()
        override suspend fun refreshCatalog() = Unit
        override suspend fun requestDownload(regionId: String, trigger: OfflineRegionDownloadTrigger, meteredConfirmed: Boolean) {
            requestCalls++
            requestedIds += regionId
        }
        override suspend fun pauseDownload(regionId: String) = Unit
        override suspend fun resumeDownload(regionId: String) = Unit
        override suspend fun deletePackage(regionId: String) = Unit
    }

    private fun regionState(status: OfflineRegionPackageStatus, id: String = "first") = OfflineRegionPackageState(
        region = OfflineRegionManifest(
            regionId = id,
            displayName = id,
            bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
            mapPack = OfflineRegionMapPackMetadata(
                styleUrls = listOf("https://tiles.openfreemap.org/styles/bright"),
                bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
                minZoom = 5,
                maxZoom = 13,
                ofmStyleRevision = "r1",
            ),
        ),
        latestRelease = release(id),
        status = status,
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

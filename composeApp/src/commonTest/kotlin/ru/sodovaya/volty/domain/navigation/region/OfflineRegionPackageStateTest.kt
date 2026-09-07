package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OfflineRegionPackageStateTest {
    @Test
    fun progress_uses_the_sum_of_published_component_sizes() {
        val state = OfflineRegionPackageState(
            region = region(),
            latestRelease = release(),
            status = OfflineRegionPackageStatus.DOWNLOADING,
            downloadedBytes = 20L,
        )

        assertEquals(55L, state.totalDownloadBytes)
        assertEquals(135L, state.totalInstalledBytes)
        assertEquals(20f / 55f, state.downloadProgress)
    }

    @Test
    fun progress_is_unknown_until_a_release_is_available() {
        val state = OfflineRegionPackageState(
            region = region(),
            latestRelease = null,
            status = OfflineRegionPackageStatus.NOT_INSTALLED,
        )

        assertEquals(0L, state.totalDownloadBytes)
        assertNull(state.downloadProgress)
    }

    @Test
    fun downloaded_bytes_are_rejected_without_a_published_release() {
        assertFailsWith<IllegalArgumentException> {
            OfflineRegionPackageState(
                region = region(),
                latestRelease = null,
                status = OfflineRegionPackageStatus.DOWNLOADING,
                downloadedBytes = 1L,
            )
        }
    }

    @Test
    fun downloaded_bytes_cannot_exceed_the_manifest_total() {
        assertFailsWith<IllegalArgumentException> {
            OfflineRegionPackageState(
                region = region(),
                latestRelease = release(),
                status = OfflineRegionPackageStatus.DOWNLOADING,
                downloadedBytes = 56L,
            )
        }
    }

    private fun region() = OfflineRegionManifest(
        regionId = "ru-sve-yekaterinburg-agglomeration",
        displayName = "Екатеринбург и агломерация",
        bounds = OfflineRegionBounds(56.30, 59.55, 57.25, 61.45),
    )

    private fun release() = OfflineRegionPackageManifest(
        schemaVersion = 3,
        regionId = "ru-sve-yekaterinburg-agglomeration",
        releaseVersion = "2026.09.1",
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(
            1L, "2026-09-02T00:00:00Z", "geofabrik-ural",
            "https://download.geofabrik.de/russia/ural-fed-district-latest.osm.pbf", checksum,
        ),
        compatibility = OfflineRegionCompatibility(28, "valhalla", "valhalla-3.6.3", 1),
        coverage = OfflineRegionCoverage(listOf(59.55, 56.30, 61.45, 57.25), 30),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://cdn.test/routing", 35L, 90L, checksum, "zstd"),
            search = OfflineRegionSearchArtifact("https://cdn.test/search", 20L, 45L, checksum, 1),
        ),
        signature = OfflineRegionManifestSignature("key", "ed25519", "signature"),
    )

    private companion object {
        val checksum = "a".repeat(64)
    }
}

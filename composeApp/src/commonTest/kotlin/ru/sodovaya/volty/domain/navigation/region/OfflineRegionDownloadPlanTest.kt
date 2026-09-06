package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineRegionDownloadPlanTest {
    @Test
    fun creates_a_deterministic_three_component_plan() {
        val manifest = release()

        val result = OfflineRegionDownloadPlanFactory.create(manifest, currentAppVersionCode = 28)

        val plan = assertIs<OfflineRegionDownloadPlanResult.Ready>(result).plan
        assertEquals("ru-sve-ekb", plan.regionId)
        assertEquals("2026.09.1", plan.releaseVersion)
        assertEquals(
            listOf(
                "routing/valhalla-routing.tar.gz",
                "search/places.sqlite.gz",
                "map/map.pmtiles",
            ),
            plan.artifacts.map(OfflineRegionArtifactDownload::relativePath),
        )
        assertEquals(30L, plan.totalDownloadBytes)
        assertEquals(300L, plan.totalInstalledBytes)
    }

    @Test
    fun rejects_a_release_that_is_not_compatible_with_the_app() {
        val result = OfflineRegionDownloadPlanFactory.create(release(minAppVersionCode = 29), 28)

        val rejected = assertIs<OfflineRegionDownloadPlanResult.Rejected>(result)
        assertEquals(
            OfflineRegionManifestErrorCode.APP_VERSION_TOO_OLD,
            rejected.errors.single().code,
        )
    }

    @Test
    fun rejects_routing_tiles_built_for_a_different_mobile_engine() {
        val result = OfflineRegionDownloadPlanFactory.create(
            release(routingDataVersion = "valhalla-3.8.3"),
            currentAppVersionCode = 28,
        )

        val rejected = assertIs<OfflineRegionDownloadPlanResult.Rejected>(result)
        assertTrue(
            rejected.errors.any {
                it.code == OfflineRegionManifestErrorCode.INVALID_ROUTING_DATA_VERSION
            },
        )
    }

    @Test
    fun resume_policy_only_appends_a_strictly_shorter_partial_file() {
        assertEquals(
            OfflineRegionResumeDecision.Resume(12L),
            OfflineRegionResumePolicy.decide(partialBytes = 12L, expectedBytes = 30L),
        )
        assertEquals(
            OfflineRegionResumeDecision.Restart,
            OfflineRegionResumePolicy.decide(partialBytes = 30L, expectedBytes = 30L),
        )
        assertEquals(
            OfflineRegionResumeDecision.Restart,
            OfflineRegionResumePolicy.decide(partialBytes = 31L, expectedBytes = 30L),
        )
        assertEquals(
            OfflineRegionResumeDecision.Restart,
            OfflineRegionResumePolicy.decide(partialBytes = -1L, expectedBytes = 30L),
        )
    }

    private fun release(
        minAppVersionCode: Int = 28,
        routingDataVersion: String = "valhalla-3.6.3",
    ) = OfflineRegionPackageManifest(
        schemaVersion = 2,
        regionId = "ru-sve-ekb",
        releaseVersion = "2026.09.1",
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(1L, "2026-09-02T00:00:00Z"),
        compatibility = OfflineRegionCompatibility(minAppVersionCode, "valhalla", routingDataVersion, 1, 1),
        coverage = OfflineRegionCoverage(listOf(59.0, 56.0, 62.0, 57.5), 20),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://cdn.test/routing", 10L, 100L, checksum, "gzip"),
            search = OfflineRegionSearchArtifact("https://cdn.test/search", 10L, 100L, checksum, 1, "gzip"),
            map = OfflineRegionMapArtifact("https://cdn.test/map", 10L, 100L, checksum, "pmtiles", 5, 14, 1),
        ),
        signature = OfflineRegionManifestSignature("release", "ed25519", "signature"),
    )

    private companion object {
        val checksum = "a".repeat(64)
    }
}

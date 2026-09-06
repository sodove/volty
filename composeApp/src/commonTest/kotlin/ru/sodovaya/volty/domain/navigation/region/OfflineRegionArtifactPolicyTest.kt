package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineRegionArtifactPolicyTest {
    @Test
    fun exact_three_component_observations_are_accepted() {
        val manifest = validManifest()

        assertEquals(
            OfflineRegionArtifactValidation.Valid(manifest),
            OfflineRegionArtifactPolicy.validate(manifest, validObservations()),
        )
    }

    @Test
    fun missing_component_is_rejected_before_activation() {
        val observations = validObservations().filterNot { it.component == OfflineRegionComponent.MAP }

        val invalid = assertIs<OfflineRegionArtifactValidation.Invalid>(
            OfflineRegionArtifactPolicy.validate(validManifest(), observations),
        )

        assertTrue(invalid.errors.any { it.code == OfflineRegionArtifactErrorCode.MISSING_COMPONENT })
    }

    @Test
    fun checksum_and_size_mismatches_are_reported_per_component() {
        val observations = validObservations().map {
            if (it.component == OfflineRegionComponent.SEARCH) {
                it.copy(downloadBytes = 1L, sha256 = "b".repeat(64))
            } else {
                it
            }
        }

        val invalid = assertIs<OfflineRegionArtifactValidation.Invalid>(
            OfflineRegionArtifactPolicy.validate(validManifest(), observations),
        )

        assertTrue(invalid.errors.any { it.code == OfflineRegionArtifactErrorCode.DOWNLOAD_SIZE_MISMATCH })
        assertTrue(invalid.errors.any { it.code == OfflineRegionArtifactErrorCode.CHECKSUM_MISMATCH })
    }

    @Test
    fun duplicate_or_unknown_components_are_not_silently_overwritten() {
        val observations = validObservations() + OfflineRegionArtifactObservation(
            component = OfflineRegionComponent.SEARCH,
            downloadBytes = 20L,
            installedBytes = 45L,
            sha256 = checksum,
        ) + OfflineRegionArtifactObservation(
            component = OfflineRegionComponent.MAP,
            downloadBytes = 80L,
            installedBytes = 95L,
            sha256 = checksum,
        )

        val invalid = assertIs<OfflineRegionArtifactValidation.Invalid>(
            OfflineRegionArtifactPolicy.validate(validManifest(), observations),
        )

        assertEquals(2, invalid.errors.count { it.code == OfflineRegionArtifactErrorCode.DUPLICATE_COMPONENT })
    }

    private fun validManifest() = OfflineRegionPackageManifest(
        schemaVersion = 2,
        regionId = "ru-sve-yekaterinburg-agglomeration",
        releaseVersion = "2026.09.1",
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(1L, "2026-09-02T00:00:00Z"),
        compatibility = OfflineRegionCompatibility(28, "valhalla", "tiles-1", 1, 1),
        coverage = OfflineRegionCoverage(listOf(59.55, 56.30, 61.45, 57.25), 30),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://cdn.test/routing", 35L, 90L, checksum, "zstd"),
            search = OfflineRegionSearchArtifact("https://cdn.test/search", 20L, 45L, checksum, 1),
            map = OfflineRegionMapArtifact("https://cdn.test/map", 80L, 95L, checksum, "pmtiles", 7, 16, 1),
        ),
        signature = OfflineRegionManifestSignature("key", "ed25519", "signature"),
    )

    private fun validObservations() = listOf(
        OfflineRegionArtifactObservation(OfflineRegionComponent.ROUTING, 35L, 90L, checksum),
        OfflineRegionArtifactObservation(OfflineRegionComponent.SEARCH, 20L, 45L, checksum),
        OfflineRegionArtifactObservation(OfflineRegionComponent.MAP, 80L, 95L, checksum),
    )

    private companion object {
        val checksum = "a".repeat(64)
    }
}

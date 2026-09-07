package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineRegionArtifactPolicyTest {
    @Test
    fun exact_navigation_component_observations_are_accepted() {
        val manifest = validManifest()

        assertEquals(
            OfflineRegionArtifactValidation.Valid(manifest),
            OfflineRegionArtifactPolicy.validate(manifest, validObservations()),
        )
    }

    @Test
    fun missing_component_is_rejected_before_activation() {
        val observations = validObservations().filterNot { it.component == OfflineRegionComponent.SEARCH }

        val invalid = assertIs<OfflineRegionArtifactValidation.Invalid>(
            OfflineRegionArtifactPolicy.validate(validManifest(), observations),
        )

        assertEquals(
            listOf(
                OfflineRegionArtifactValidationError(
                    OfflineRegionArtifactErrorCode.MISSING_COMPONENT,
                    OfflineRegionComponent.SEARCH,
                ),
            ),
            invalid.errors,
        )
    }

    @Test
    fun checksum_and_size_mismatches_are_reported_per_component() {
        val observations = validObservations().map {
            if (it.component == OfflineRegionComponent.SEARCH) {
                it.copy(downloadBytes = 1L, installedBytes = 1L, sha256 = "b".repeat(64))
            } else {
                it
            }
        }

        val invalid = assertIs<OfflineRegionArtifactValidation.Invalid>(
            OfflineRegionArtifactPolicy.validate(validManifest(), observations),
        )

        assertTrue(invalid.errors.any { it.code == OfflineRegionArtifactErrorCode.DOWNLOAD_SIZE_MISMATCH })
        assertTrue(invalid.errors.any { it.code == OfflineRegionArtifactErrorCode.INSTALLED_SIZE_MISMATCH })
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

        assertEquals(
            setOf(
                OfflineRegionArtifactValidationError(
                    OfflineRegionArtifactErrorCode.DUPLICATE_COMPONENT,
                    OfflineRegionComponent.SEARCH,
                ),
                OfflineRegionArtifactValidationError(
                    OfflineRegionArtifactErrorCode.UNEXPECTED_COMPONENT,
                    OfflineRegionComponent.MAP,
                ),
            ),
            invalid.errors.toSet(),
        )
        assertEquals(2, invalid.errors.size)
    }

    private fun validManifest() = OfflineRegionPackageManifest(
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

    private fun validObservations() = listOf(
        OfflineRegionArtifactObservation(OfflineRegionComponent.ROUTING, 35L, 90L, checksum),
        OfflineRegionArtifactObservation(OfflineRegionComponent.SEARCH, 20L, 45L, checksum),
    )

    private companion object {
        val checksum = "a".repeat(64)
    }
}

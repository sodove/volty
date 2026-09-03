package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfflineRegionPackageManifestTest {
    @Test
    fun valid_manifest_accepts_the_three_component_release() {
        assertEquals(
            emptyList(),
            OfflineRegionPackageManifestPolicy.validate(
                manifest = validManifest(),
                currentAppVersionCode = 28,
            ),
        )
    }

    @Test
    fun manifest_reports_incompatible_engine_format_sizes_and_signature() {
        val invalid = validManifest(
            compatibility = validManifest().compatibility.copy(
                minAppVersionCode = 29,
                routingEngine = "brouter",
                mapSchemaVersion = 0,
            ),
            components = validManifest().components.copy(
                map = mapArtifact(format = "mbtiles", downloadBytes = 0L),
            ),
            signature = OfflineRegionManifestSignature(
                keyId = "",
                algorithm = "rsa",
                value = "",
            ),
        )

        val errors = OfflineRegionPackageManifestPolicy.validate(invalid, currentAppVersionCode = 28)

        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.APP_VERSION_TOO_OLD })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.UNSUPPORTED_ROUTING_ENGINE })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_MAP_SCHEMA })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_ARTIFACT_SIZE })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_MAP_FORMAT })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_SIGNATURE })
    }

    @Test
    fun manifest_requires_all_three_artifacts_and_unique_release_identity() {
        val invalid = validManifest(
            regionId = "",
            releaseVersion = "",
            components = OfflineRegionComponents(
                routing = routingArtifact(sha256 = checksum),
                search = searchArtifact(sha256 = checksum),
                map = mapArtifact(sha256 = "bad"),
            ),
        )

        val errors = OfflineRegionPackageManifestPolicy.validate(invalid, currentAppVersionCode = 28)

        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_REGION_ID })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_RELEASE_VERSION })
        assertTrue(errors.any { it.code == OfflineRegionManifestErrorCode.INVALID_CHECKSUM })
    }

    private fun validManifest(
        regionId: String = "ru-sve-yekaterinburg-agglomeration",
        releaseVersion: String = "2026.09.1",
        compatibility: OfflineRegionCompatibility = OfflineRegionCompatibility(
            minAppVersionCode = 28,
            routingEngine = "valhalla",
            routingDataVersion = "valhalla-tiles-2026.09.1",
            mapSchemaVersion = 1,
            searchSchemaVersion = 1,
        ),
        components: OfflineRegionComponents = OfflineRegionComponents(
            routing = routingArtifact(downloadBytes = 35_000_000L),
            search = searchArtifact(downloadBytes = 20_000_000L),
            map = mapArtifact(downloadBytes = 80_000_000L),
        ),
        signature: OfflineRegionManifestSignature = OfflineRegionManifestSignature(
            keyId = "volty-navigation-2026",
            algorithm = "ed25519",
            value = "signature",
        ),
    ) = OfflineRegionPackageManifest(
        schemaVersion = 2,
        regionId = regionId,
        releaseVersion = releaseVersion,
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(
            osmReplicationSequence = 1L,
            osmTimestamp = "2026-09-02T00:00:00Z",
        ),
        compatibility = compatibility,
        coverage = OfflineRegionCoverage(
            bbox = listOf(59.55, 56.30, 61.45, 57.25),
            routingBufferKm = 30,
        ),
        components = components,
        signature = signature,
    )

    private fun routingArtifact(
        downloadBytes: Long = 1_000_000L,
        sha256: String = checksum,
    ) = OfflineRegionRoutingArtifact(
        url = "https://cdn.example.test/region.bin",
        downloadBytes = downloadBytes,
        installedBytes = 2_000_000L,
        sha256 = sha256,
        compression = "zstd",
    )

    private fun searchArtifact(
        downloadBytes: Long = 1_000_000L,
        sha256: String = checksum,
    ) = OfflineRegionSearchArtifact(
        url = "https://cdn.example.test/region.bin",
        downloadBytes = downloadBytes,
        installedBytes = 2_000_000L,
        sha256 = sha256,
        schemaVersion = 1,
    )

    private fun mapArtifact(
        format: String = "pmtiles",
        downloadBytes: Long = 1_000_000L,
        sha256: String = checksum,
    ) = OfflineRegionMapArtifact(
        url = "https://cdn.example.test/region.bin",
        downloadBytes = downloadBytes,
        installedBytes = 2_000_000L,
        sha256 = sha256,
        format = format,
        minZoom = 7,
        maxZoom = 16,
        vectorLayerSchema = 1,
    )

    private companion object {
        const val checksum = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}

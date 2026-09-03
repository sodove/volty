package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineRegionCatalogTest {
    @Test
    fun parses_a_catalog_with_a_latest_release() {
        val result = OfflineRegionCatalogCodec.parse(
            """
            {
              "schemaVersion": 2,
              "generatedAt": "2026-09-03T00:00:00Z",
              "catalogSignature": {"keyId": "key", "algorithm": "ed25519", "value": "signature"},
              "regions": [{
                "region": {
                  "regionId": "ru-sve-ekb",
                  "displayName": "Екатеринбург",
                  "bounds": {"south": 56.0, "west": 59.0, "north": 57.5, "east": 62.0}
                },
                "latestRelease": ${releaseJson()}
              }]
            }
            """.trimIndent(),
        )

        val catalog = assertIs<OfflineRegionCatalogParseResult.Success>(result).catalog
        assertEquals("ru-sve-ekb", catalog.regions.single().region.regionId)
        assertEquals("2026.09.1", catalog.regions.single().latestRelease?.releaseVersion)
    }

    @Test
    fun rejects_unknown_catalog_fields() {
        val result = OfflineRegionCatalogCodec.parse(
            """
            {
              "schemaVersion": 2,
              "generatedAt": "2026-09-03T00:00:00Z",
              "catalogSignature": {"keyId": "key", "algorithm": "ed25519", "value": "signature"},
              "regions": [],
              "unexpected": true
            }
            """.trimIndent(),
        )

        assertIs<OfflineRegionCatalogParseResult.Failure>(result)
    }

    @Test
    fun validation_rejects_duplicate_regions_and_mismatched_release() {
        val region = region("ru-sve-ekb")
        val errors = OfflineRegionCatalogPolicy.validate(
            OfflineRegionCatalog(
                schemaVersion = 2,
                generatedAt = "2026-09-03T00:00:00Z",
                regions = listOf(
                    OfflineRegionCatalogEntry(region, null),
                    OfflineRegionCatalogEntry(
                        region("ru-sve-ekb"),
                        release(regionId = "ru-sve-other"),
                    ),
                ),
                signature = catalogSignature(),
            ),
            currentAppVersionCode = 28,
        )

        assertEquals(
            listOf(
                OfflineRegionCatalogErrorCode.DUPLICATE_REGION_ID,
                OfflineRegionCatalogErrorCode.RELEASE_REGION_MISMATCH,
            ),
            errors.map(OfflineRegionCatalogValidationError::code),
        )
    }

    @Test
    fun validation_accepts_catalog_without_downloadable_releases() {
        val result = OfflineRegionCatalogPolicy.validate(
            OfflineRegionCatalog(
                schemaVersion = 2,
                generatedAt = "2026-09-03T00:00:00Z",
                regions = listOf(OfflineRegionCatalogEntry(region("ru-sve-ekb"), null)),
                signature = catalogSignature(),
            ),
            currentAppVersionCode = 28,
        )

        assertEquals(emptyList(), result)
    }

    @Test
    fun validation_rejects_an_unsigned_catalog_envelope() {
        val errors = OfflineRegionCatalogPolicy.validate(
            OfflineRegionCatalog(
                schemaVersion = 2,
                generatedAt = "2026-09-03T00:00:00Z",
                regions = emptyList(),
                signature = OfflineRegionCatalogSignature("UNSIGNED_DEV", "ed25519", "UNSIGNED"),
            ),
            currentAppVersionCode = 28,
        )

        assertEquals(OfflineRegionCatalogErrorCode.INVALID_SIGNATURE, errors.single().code)
    }

    @Test
    fun catalog_signing_payload_excludes_the_signature_envelope() {
        val catalog = OfflineRegionCatalog(
            schemaVersion = 2,
            generatedAt = "2026-09-03T00:00:00Z",
            regions = emptyList(),
            signature = catalogSignature(),
        )

        val payload = OfflineRegionCatalogCodec.signingPayload(catalog)

        assertFalse(payload.contains("catalogSignature"))
        assertEquals(
            payload,
            OfflineRegionCatalogCodec.signingPayload(
                catalog.copy(signature = OfflineRegionCatalogSignature("other", "ed25519", "different")),
            ),
        )
    }

    @Test
    fun signature_policy_rejects_unverified_latest_releases_before_download() {
        val catalog = OfflineRegionCatalog(
            schemaVersion = 2,
            generatedAt = "2026-09-03T00:00:00Z",
            regions = listOf(OfflineRegionCatalogEntry(region("ru-sve-ekb"), release())),
            signature = catalogSignature(),
        )

        val unverified = OfflineRegionCatalogSignaturePolicy.unverifiedReleaseIds(
            catalog = catalog,
            verifier = OfflineRegionManifestVerifier { false },
        )

        assertEquals(listOf("ru-sve-ekb"), unverified)
    }

    @Test
    fun validation_rejects_a_region_that_is_outside_its_release_coverage() {
        val release = release().copy(
            coverage = OfflineRegionCoverage(listOf(60.0, 56.5, 61.0, 57.0), 20),
        )
        val errors = OfflineRegionCatalogPolicy.validate(
            OfflineRegionCatalog(
                schemaVersion = 2,
                generatedAt = "2026-09-03T00:00:00Z",
                regions = listOf(OfflineRegionCatalogEntry(region("ru-sve-ekb"), release)),
                signature = catalogSignature(),
            ),
            currentAppVersionCode = 28,
        )

        assertEquals(
            OfflineRegionCatalogErrorCode.REGION_BOUNDS_MISMATCH,
            errors.single().code,
        )
    }

    private fun region(regionId: String) = OfflineRegionManifest(
        regionId = regionId,
        displayName = "Екатеринбург",
        bounds = OfflineRegionBounds(56.0, 59.0, 57.5, 62.0),
    )

    private fun catalogSignature() = OfflineRegionCatalogSignature("key", "ed25519", "signature")

    private fun release(regionId: String = "ru-sve-ekb") = OfflineRegionPackageManifest(
        schemaVersion = 2,
        regionId = regionId,
        releaseVersion = "2026.09.1",
        createdAt = "2026-09-03T00:00:00Z",
        source = OfflineRegionSource(1L, "2026-09-02T00:00:00Z"),
        compatibility = OfflineRegionCompatibility(
            28,
            "valhalla",
            OfflineRegionPackageManifestPolicy.EXPECTED_ROUTING_DATA_VERSION,
            1,
            1,
        ),
        coverage = OfflineRegionCoverage(listOf(59.0, 56.0, 62.0, 57.5), 20),
        components = OfflineRegionComponents(
            routing = OfflineRegionRoutingArtifact("https://cdn.test/routing", 1L, 1L, checksum, "gzip"),
            search = OfflineRegionSearchArtifact("https://cdn.test/search", 1L, 1L, checksum, 1),
            map = OfflineRegionMapArtifact("https://cdn.test/map", 1L, 1L, checksum, "pmtiles", 5, 14, 1),
        ),
        signature = OfflineRegionManifestSignature("key", "ed25519", "signature"),
    )

    private fun releaseJson() = """
      {
        "schemaVersion": 2,
        "regionId": "ru-sve-ekb",
        "releaseVersion": "2026.09.1",
        "createdAt": "2026-09-03T00:00:00Z",
        "source": {"osmReplicationSequence": 1, "osmTimestamp": "2026-09-02T00:00:00Z"},
        "compatibility": {"minAppVersionCode": 28, "routingEngine": "valhalla", "routingDataVersion": "${OfflineRegionPackageManifestPolicy.EXPECTED_ROUTING_DATA_VERSION}", "mapSchemaVersion": 1, "searchSchemaVersion": 1},
        "coverage": {"bbox": [59.0, 56.0, 62.0, 57.5], "routingBufferKm": 20},
        "components": {
          "routing": {"url": "https://cdn.test/routing", "downloadBytes": 1, "installedBytes": 1, "sha256": "$checksum", "compression": "gzip"},
          "search": {"url": "https://cdn.test/search", "downloadBytes": 1, "installedBytes": 1, "sha256": "$checksum", "schemaVersion": 1},
          "map": {"url": "https://cdn.test/map", "downloadBytes": 1, "installedBytes": 1, "sha256": "$checksum", "format": "pmtiles", "minZoom": 5, "maxZoom": 14, "vectorLayerSchema": 1}
        },
        "manifestSignature": {"keyId": "key", "algorithm": "ed25519", "value": "signature"}
      }
    """.trimIndent()

    private companion object {
        val checksum = "a".repeat(64)
    }
}

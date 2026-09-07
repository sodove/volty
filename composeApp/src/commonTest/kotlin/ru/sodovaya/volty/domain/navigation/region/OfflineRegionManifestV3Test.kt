package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackState

class OfflineRegionManifestV3Test {
    @Test
    fun map_metadata_and_readiness_are_separate_from_navigation_progress() {
        val manifest = assertIs<OfflineRegionManifestParseResult.Success>(OfflineRegionPackageManifestCodec.parse(v3ManifestJson())).manifest
        val bounds = OfflineRegionBounds(56.0, 59.0, 57.5, 62.0)
        val region = OfflineRegionManifest("ru-sve-ekb", "Екатеринбург", bounds,
            mapPack = OfflineRegionMapPackMetadata(listOf("https://tiles.openfreemap.org/styles/bright"), bounds, 5, 13, null))
        val state = OfflineRegionPackageState(region, manifest, OfflineRegionPackageStatus.DOWNLOADING,
            downloadedBytes = 20, mapPackState = OfflineMapPackState.Ready)

        assertEquals(OfflineRegionPackageStatus.DOWNLOADING, state.navigationStatus)
        assertEquals(OfflineMapPackState.Ready, state.mapPackState)
        assertEquals(55L, state.totalDownloadBytes)
        assertEquals(135L, state.totalInstalledBytes)
        assertEquals(20f / 55f, state.downloadProgress)
        assertEquals(OfflineMapPackState.Missing, state.copy(mapPackState = OfflineMapPackState.Missing, status = OfflineRegionPackageStatus.READY).mapPackState)
        val catalog = OfflineRegionCatalog(2, "2026-09-03T00:00:00Z", listOf(OfflineRegionCatalogEntry(region, manifest)), OfflineRegionCatalogSignature("key", "ed25519", "sig"))
        val parsed = assertIs<OfflineRegionCatalogParseResult.Success>(OfflineRegionCatalogCodec.parse(OfflineRegionCatalogCodec.encode(catalog))).catalog
        assertEquals(region.mapPack, parsed.regions.single().region.mapPack)
        assertEquals(emptyList(), OfflineRegionCatalogPolicy.validate(parsed, 31))
    }

    @Test
    fun legacy_decoder_exposes_navigation_but_ignores_map_and_is_read_only() {
        val legacy = requireNotNull(OfflineRegionLegacyManifestCodec.parse(legacyManifestJson()))
        assertEquals(setOf(OfflineRegionComponent.MAP), legacy.ignoredComponents)
        assertEquals(35L, legacy.navigationManifest.components.routing.downloadBytes)
        assertEquals(20L, legacy.navigationManifest.components.search.downloadBytes)
        assertEquals(emptyList(), OfflineRegionPackageManifestPolicy.validateLegacy(legacy, 31))
        assertIs<OfflineRegionDownloadPlanResult.Rejected>(OfflineRegionDownloadPlanFactory.create(legacy.navigationManifest, 31))
        assertIs<OfflineRegionManifestParseResult.Failure>(OfflineRegionPackageManifestCodec.parse(legacyManifestJson()))
        assertNull(OfflineRegionLegacyManifestCodec.parse(v3ManifestJson()))
        val state = OfflineRegionPackageState(OfflineRegionManifest("ru-sve-ekb", "Екатеринбург", OfflineRegionBounds(56.0,59.0,57.5,62.0)), legacy.navigationManifest, OfflineRegionPackageStatus.READY)
        assertEquals(55L, state.totalDownloadBytes)
        assertEquals(OfflineMapPackState.Missing, state.mapPackState)
    }
    @Test
    fun routing_and_search_release_with_pinned_source_validates() {
        val manifest = assertIs<OfflineRegionManifestParseResult.Success>(
            OfflineRegionPackageManifestCodec.parse(v3ManifestJson()),
        ).manifest

        assertEquals(emptyList(), OfflineRegionPackageManifestPolicy.validate(manifest, 31))
        val plan = assertIs<OfflineRegionDownloadPlanResult.Ready>(
            OfflineRegionDownloadPlanFactory.create(manifest, 31),
        ).plan
        assertEquals(listOf(OfflineRegionComponent.ROUTING, OfflineRegionComponent.SEARCH), plan.artifacts.map { it.component })
        assertEquals(55L, plan.totalDownloadBytes)
        assertEquals(135L, plan.totalInstalledBytes)
    }

    @Test
    fun v3_rejects_a_downloadable_map_even_when_its_metadata_looks_valid() {
        val text = v3ManifestJson().replace("\"components\": {", "\"components\": {\"map\": $legacyMapJson,")
        assertIs<OfflineRegionManifestParseResult.Failure>(OfflineRegionPackageManifestCodec.parse(text))
    }

    @Test
    fun provenance_changes_the_signed_payload_and_invalid_provenance_is_rejected() {
        val manifest = assertIs<OfflineRegionManifestParseResult.Success>(
            OfflineRegionPackageManifestCodec.parse(v3ManifestJson()),
        ).manifest
        val changed = assertIs<OfflineRegionManifestParseResult.Success>(
            OfflineRegionPackageManifestCodec.parse(v3ManifestJson().replace("https://source.test/ekb-20260902.osm.pbf", "http://source.test/latest.osm.pbf")),
        ).manifest
        assertTrue(OfflineRegionPackageManifestCodec.signingPayload(manifest) != OfflineRegionPackageManifestCodec.signingPayload(changed))
        assertTrue(OfflineRegionPackageManifestPolicy.validate(changed, 31).any { it.code == OfflineRegionManifestErrorCode.INVALID_SOURCE })
    }

    @Test
    fun each_source_identity_field_is_required_and_validated() {
        val manifest = assertIs<OfflineRegionManifestParseResult.Success>(OfflineRegionPackageManifestCodec.parse(v3ManifestJson())).manifest
        listOf(
            manifest.source.copy(sourceId = ""),
            manifest.source.copy(sourceUrl = "https://"),
            manifest.source.copy(sourceSha256 = "bad"),
            manifest.source.copy(osmTimestamp = "yesterday"),
            manifest.source.copy(osmReplicationSequence = -1),
        ).forEach { source ->
            assertTrue(OfflineRegionPackageManifestPolicy.validate(manifest.copy(source = source), 31).any { it.code == OfflineRegionManifestErrorCode.INVALID_SOURCE })
        }
        assertFailsWith<IllegalArgumentException> {
            OfflineRegionPackageManifestCodec.encode(requireNotNull(OfflineRegionLegacyManifestCodec.parse(legacyManifestJson())).navigationManifest)
        }
    }

    @Test
    fun malformed_map_metadata_is_rejected_by_catalog_decoding() {
        val valid = OfflineRegionManifest("ru-sve-ekb", "Екатеринбург", OfflineRegionBounds(56.0, 59.0, 57.5, 62.0),
            OfflineRegionMapPackMetadata(listOf("https://tiles.openfreemap.org/styles/bright"), OfflineRegionBounds(56.0, 59.0, 57.5, 62.0), 5, 13))
        val text = OfflineRegionCatalogCodec.encode(OfflineRegionCatalog(2, "2026-09-03T00:00:00Z", listOf(OfflineRegionCatalogEntry(valid, onDemand = OfflineRegionOnDemand(true))), OfflineRegionCatalogSignature("key", "ed25519", "sig")))
        assertIs<OfflineRegionCatalogParseResult.Failure>(OfflineRegionCatalogCodec.parse(text.replace("https://tiles.openfreemap.org/styles/bright", "https://untrusted.test/style")))
        assertIs<OfflineRegionCatalogParseResult.Failure>(OfflineRegionCatalogCodec.parse(text.replace("\"maxZoom\":13", "\"maxZoom\":4")))
    }
}

internal val legacyMapJson = """{"url":"https://cdn.test/map","downloadBytes":8000,"installedBytes":8000,"sha256":"${"a".repeat(64)}","format":"pmtiles","minZoom":5,"maxZoom":13,"vectorLayerSchema":1}"""

internal fun v3ManifestJson() = """
    {
      "schemaVersion":3,
      "regionId":"ru-sve-ekb",
      "releaseVersion":"2026.09.1",
      "createdAt":"2026-09-03T00:00:00Z",
      "source":{"osmReplicationSequence":1,"osmTimestamp":"2026-09-02T00:00:00Z","sourceId":"geofabrik-ekb-20260902","sourceUrl":"https://source.test/ekb-20260902.osm.pbf","sourceSha256":"${"b".repeat(64)}"},
      "compatibility":{"minAppVersionCode":28,"routingEngine":"valhalla","routingDataVersion":"valhalla-3.6.3","searchSchemaVersion":1},
      "coverage":{"bbox":[59.0,56.0,62.0,57.5],"routingBufferKm":20},
      "components": {
        "routing":{"url":"https://cdn.test/routing","downloadBytes":35,"installedBytes":90,"sha256":"${"a".repeat(64)}","compression":"gzip"},
        "search":{"url":"https://cdn.test/search","downloadBytes":20,"installedBytes":45,"sha256":"${"a".repeat(64)}","schemaVersion":1,"compression":"gzip"}
      },
      "manifestSignature":{"keyId":"release","algorithm":"ed25519","value":"signature"}
    }
""".trimIndent()

internal fun legacyManifestJson(): String = v3ManifestJson()
    .replace("\"schemaVersion\":3", "\"schemaVersion\":2")
    .replace(",\"sourceId\":\"geofabrik-ekb-20260902\",\"sourceUrl\":\"https://source.test/ekb-20260902.osm.pbf\",\"sourceSha256\":\"${"b".repeat(64)}\"", "")
    .replace("\"searchSchemaVersion\":1", "\"mapSchemaVersion\":1,\"searchSchemaVersion\":1")
    .replace("\"components\": {", "\"components\": {\"map\": $legacyMapJson,")

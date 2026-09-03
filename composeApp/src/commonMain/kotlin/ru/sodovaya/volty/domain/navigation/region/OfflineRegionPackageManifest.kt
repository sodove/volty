package ru.sodovaya.volty.domain.navigation.region

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable
data class OfflineRegionSource(
    val osmReplicationSequence: Long,
    val osmTimestamp: String,
)

@Serializable
data class OfflineRegionCompatibility(
    val minAppVersionCode: Int,
    val routingEngine: String,
    val routingDataVersion: String,
    val mapSchemaVersion: Int,
    val searchSchemaVersion: Int,
)

@Serializable
data class OfflineRegionCoverage(
    val bbox: List<Double>,
    val routingBufferKm: Int,
    val polygonUrl: String? = null,
)

@Serializable
data class OfflineRegionRoutingArtifact(
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
    val compression: String,
)

@Serializable
data class OfflineRegionSearchArtifact(
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
    val schemaVersion: Int,
    val compression: String? = null,
)

@Serializable
data class OfflineRegionMapArtifact(
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    val vectorLayerSchema: Int,
    val compression: String? = null,
)

@Serializable
data class OfflineRegionComponents(
    val routing: OfflineRegionRoutingArtifact,
    val search: OfflineRegionSearchArtifact,
    val map: OfflineRegionMapArtifact,
)

@Serializable
data class OfflineRegionManifestSignature(
    val keyId: String,
    val algorithm: String,
    val value: String,
)

@Serializable
data class OfflineRegionPackageManifest(
    val schemaVersion: Int,
    val regionId: String,
    val releaseVersion: String,
    val createdAt: String,
    val source: OfflineRegionSource,
    val compatibility: OfflineRegionCompatibility,
    val coverage: OfflineRegionCoverage,
    val components: OfflineRegionComponents,
    @SerialName("manifestSignature")
    val signature: OfflineRegionManifestSignature,
)

enum class OfflineRegionManifestParseError {
    MALFORMED_MANIFEST,
}

sealed interface OfflineRegionManifestParseResult {
    data class Success(val manifest: OfflineRegionPackageManifest) : OfflineRegionManifestParseResult

    data class Failure(val error: OfflineRegionManifestParseError) : OfflineRegionManifestParseResult
}

/** Decodes the signed release envelope without silently accepting a newer/unknown shape. */
object OfflineRegionPackageManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    }

    fun parse(jsonText: String): OfflineRegionManifestParseResult = try {
        OfflineRegionManifestParseResult.Success(
            json.decodeFromString<OfflineRegionPackageManifest>(jsonText),
        )
    } catch (_: SerializationException) {
        malformed()
    } catch (_: IllegalArgumentException) {
        malformed()
    }

    fun encode(manifest: OfflineRegionPackageManifest): String = json.encodeToString(manifest)

    private fun malformed() = OfflineRegionManifestParseResult.Failure(
        OfflineRegionManifestParseError.MALFORMED_MANIFEST,
    )
}

enum class OfflineRegionManifestErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_REGION_ID,
    INVALID_RELEASE_VERSION,
    INVALID_CREATED_AT,
    INVALID_SOURCE,
    APP_VERSION_TOO_OLD,
    UNSUPPORTED_ROUTING_ENGINE,
    INVALID_ROUTING_DATA_VERSION,
    INVALID_MAP_SCHEMA,
    INVALID_SEARCH_SCHEMA,
    INVALID_ROUTING_BUFFER,
    INVALID_BBOX,
    INVALID_ARTIFACT_URL,
    INVALID_ARTIFACT_SIZE,
    INVALID_CHECKSUM,
    INVALID_COMPONENT_SCHEMA,
    INVALID_MAP_FORMAT,
    INVALID_MAP_ZOOM,
    INVALID_VECTOR_LAYER_SCHEMA,
    INVALID_SIGNATURE,
}

data class OfflineRegionManifestValidationError(
    val code: OfflineRegionManifestErrorCode,
    val detail: String,
)

object OfflineRegionPackageManifestPolicy {
    const val CURRENT_SCHEMA_VERSION: Int = 2
    const val EXPECTED_ROUTING_ENGINE: String = "valhalla"
    const val EXPECTED_MAP_FORMAT: String = "pmtiles"

    fun validate(
        manifest: OfflineRegionPackageManifest,
        currentAppVersionCode: Int,
    ): List<OfflineRegionManifestValidationError> {
        val errors = mutableListOf<OfflineRegionManifestValidationError>()
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            errors += error(OfflineRegionManifestErrorCode.UNSUPPORTED_SCHEMA_VERSION, manifest.schemaVersion)
        }
        if (!REGION_ID_PATTERN.matches(manifest.regionId)) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_REGION_ID, manifest.regionId)
        }
        if (!RELEASE_VERSION_PATTERN.matches(manifest.releaseVersion)) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_RELEASE_VERSION, manifest.releaseVersion)
        }
        if (!isValidTimestamp(manifest.createdAt)) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_CREATED_AT, manifest.createdAt)
        }
        if (manifest.source.osmReplicationSequence < 0L ||
            !isValidTimestamp(manifest.source.osmTimestamp)
        ) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_SOURCE, manifest.source.toString())
        }

        if (manifest.compatibility.minAppVersionCode < 0 ||
            manifest.compatibility.minAppVersionCode > currentAppVersionCode
        ) {
            errors += error(
                OfflineRegionManifestErrorCode.APP_VERSION_TOO_OLD,
                manifest.compatibility.minAppVersionCode,
            )
        }
        if (manifest.compatibility.routingEngine.lowercase() != EXPECTED_ROUTING_ENGINE) {
            errors += error(
                OfflineRegionManifestErrorCode.UNSUPPORTED_ROUTING_ENGINE,
                manifest.compatibility.routingEngine,
            )
        }
        if (manifest.compatibility.routingDataVersion.isBlank()) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_ROUTING_DATA_VERSION,
                manifest.compatibility.routingDataVersion,
            )
        }
        if (manifest.compatibility.mapSchemaVersion < 1) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_MAP_SCHEMA,
                manifest.compatibility.mapSchemaVersion,
            )
        }
        if (manifest.compatibility.searchSchemaVersion < 1) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_SEARCH_SCHEMA,
                manifest.compatibility.searchSchemaVersion,
            )
        }
        if (manifest.coverage.routingBufferKm !in 0..100) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_ROUTING_BUFFER,
                manifest.coverage.routingBufferKm,
            )
        }
        if (!isValidBbox(manifest.coverage.bbox)) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_BBOX, "coverage.bbox")
        }

        validateBaseArtifact(
            name = "routing",
            url = manifest.components.routing.url,
            downloadBytes = manifest.components.routing.downloadBytes,
            installedBytes = manifest.components.routing.installedBytes,
            sha256 = manifest.components.routing.sha256,
            errors = errors,
        )
        if (manifest.components.routing.compression.isBlank()) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_COMPONENT_SCHEMA, "routing.compression")
        }
        validateBaseArtifact(
            name = "search",
            url = manifest.components.search.url,
            downloadBytes = manifest.components.search.downloadBytes,
            installedBytes = manifest.components.search.installedBytes,
            sha256 = manifest.components.search.sha256,
            errors = errors,
        )
        if (manifest.components.search.schemaVersion < 1) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_COMPONENT_SCHEMA,
                "search.schemaVersion",
            )
        }
        validateBaseArtifact(
            name = "map",
            url = manifest.components.map.url,
            downloadBytes = manifest.components.map.downloadBytes,
            installedBytes = manifest.components.map.installedBytes,
            sha256 = manifest.components.map.sha256,
            errors = errors,
        )
        if (manifest.components.map.format.lowercase() != EXPECTED_MAP_FORMAT) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_MAP_FORMAT, manifest.components.map.format)
        }
        if (manifest.components.map.minZoom !in 0..24 ||
            manifest.components.map.maxZoom !in 0..24 ||
            manifest.components.map.minZoom > manifest.components.map.maxZoom
        ) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_MAP_ZOOM, "map")
        }
        if (manifest.components.map.vectorLayerSchema < 1) {
            errors += error(
                OfflineRegionManifestErrorCode.INVALID_VECTOR_LAYER_SCHEMA,
                manifest.components.map.vectorLayerSchema,
            )
        }

        if (manifest.signature.keyId.isBlank() ||
            manifest.signature.algorithm.lowercase() != "ed25519" ||
            manifest.signature.value.isBlank()
        ) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_SIGNATURE, manifest.signature.toString())
        }
        return errors
    }

    private fun validateBaseArtifact(
        name: String,
        url: String,
        downloadBytes: Long,
        installedBytes: Long,
        sha256: String,
        errors: MutableList<OfflineRegionManifestValidationError>,
    ) {
        if (!url.startsWith("https://")) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_ARTIFACT_URL, name)
        }
        if (downloadBytes <= 0L || installedBytes <= 0L) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_ARTIFACT_SIZE, name)
        }
        if (!SHA256_PATTERN.matches(sha256)) {
            errors += error(OfflineRegionManifestErrorCode.INVALID_CHECKSUM, name)
        }
    }

    private fun isValidBbox(bbox: List<Double>): Boolean {
        if (bbox.size != 4 || bbox.any { !it.isFinite() }) return false
        val west = bbox[0]
        val south = bbox[1]
        val east = bbox[2]
        val north = bbox[3]
        return west in -180.0..180.0 && east in -180.0..180.0 &&
            south in -90.0..90.0 && north in -90.0..90.0 &&
            west <= east && south <= north
    }

    private fun isValidTimestamp(value: String): Boolean = value.isNotBlank() &&
        runCatching { Instant.parse(value) }.isSuccess

    private fun error(
        code: OfflineRegionManifestErrorCode,
        detail: Any?,
    ) = OfflineRegionManifestValidationError(code, detail.toString())

    private val REGION_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val RELEASE_VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}

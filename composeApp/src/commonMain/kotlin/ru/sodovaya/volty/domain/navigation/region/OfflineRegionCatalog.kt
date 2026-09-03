package ru.sodovaya.volty.domain.navigation.region

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable
data class OfflineRegionCatalog(
    val schemaVersion: Int,
    val generatedAt: String,
    val regions: List<OfflineRegionCatalogEntry>,
)

@Serializable
data class OfflineRegionCatalogEntry(
    val region: OfflineRegionManifest,
    val latestRelease: OfflineRegionPackageManifest? = null,
)

enum class OfflineRegionCatalogParseError {
    MALFORMED_CATALOG,
}

sealed interface OfflineRegionCatalogParseResult {
    data class Success(val catalog: OfflineRegionCatalog) : OfflineRegionCatalogParseResult

    data class Failure(val error: OfflineRegionCatalogParseError) : OfflineRegionCatalogParseResult
}

object OfflineRegionCatalogCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    }

    fun parse(jsonText: String): OfflineRegionCatalogParseResult = try {
        OfflineRegionCatalogParseResult.Success(
            json.decodeFromString<OfflineRegionCatalog>(jsonText),
        )
    } catch (_: SerializationException) {
        OfflineRegionCatalogParseResult.Failure(OfflineRegionCatalogParseError.MALFORMED_CATALOG)
    } catch (_: IllegalArgumentException) {
        OfflineRegionCatalogParseResult.Failure(OfflineRegionCatalogParseError.MALFORMED_CATALOG)
    }

    fun encode(catalog: OfflineRegionCatalog): String = json.encodeToString(catalog)
}

enum class OfflineRegionCatalogErrorCode {
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_GENERATED_AT,
    DUPLICATE_REGION_ID,
    RELEASE_REGION_MISMATCH,
    REGION_BOUNDS_MISMATCH,
    RELEASE_INVALID,
}

data class OfflineRegionCatalogValidationError(
    val code: OfflineRegionCatalogErrorCode,
    val detail: String,
)

object OfflineRegionCatalogPolicy {
    const val CURRENT_SCHEMA_VERSION: Int = 1

    fun validate(
        catalog: OfflineRegionCatalog,
        currentAppVersionCode: Int,
    ): List<OfflineRegionCatalogValidationError> {
        val errors = mutableListOf<OfflineRegionCatalogValidationError>()
        if (catalog.schemaVersion != CURRENT_SCHEMA_VERSION) {
            errors += OfflineRegionCatalogValidationError(
                OfflineRegionCatalogErrorCode.UNSUPPORTED_SCHEMA_VERSION,
                catalog.schemaVersion.toString(),
            )
        }
        if (runCatching { Instant.parse(catalog.generatedAt) }.isFailure) {
            errors += OfflineRegionCatalogValidationError(
                OfflineRegionCatalogErrorCode.INVALID_GENERATED_AT,
                catalog.generatedAt,
            )
        }

        val duplicateIds = catalog.regions.groupingBy { it.region.regionId }.eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateIds.forEach { regionId ->
            errors += OfflineRegionCatalogValidationError(
                OfflineRegionCatalogErrorCode.DUPLICATE_REGION_ID,
                regionId,
            )
        }

        catalog.regions.forEach { entry ->
            entry.latestRelease?.let { release ->
                if (release.regionId != entry.region.regionId) {
                    errors += OfflineRegionCatalogValidationError(
                        OfflineRegionCatalogErrorCode.RELEASE_REGION_MISMATCH,
                        entry.region.regionId,
                    )
                }
                if (!release.coverage.bbox.covers(entry.region.bounds)) {
                    errors += OfflineRegionCatalogValidationError(
                        OfflineRegionCatalogErrorCode.REGION_BOUNDS_MISMATCH,
                        entry.region.regionId,
                    )
                }
                OfflineRegionPackageManifestPolicy.validate(release, currentAppVersionCode)
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        errors += OfflineRegionCatalogValidationError(
                            OfflineRegionCatalogErrorCode.RELEASE_INVALID,
                            entry.region.regionId,
                        )
                    }
            }
        }
        return errors
    }

    private fun List<Double>.covers(bounds: OfflineRegionBounds): Boolean {
        if (size != 4) return false
        return this[0] <= bounds.west &&
            this[1] <= bounds.south &&
            this[2] >= bounds.east &&
            this[3] >= bounds.north
    }
}

/**
 * Cryptographic gate for releases advertised by a catalog.
 *
 * Structural catalog validation is deliberately separate from signature
 * verification because the verifier is platform-provided. Callers must run
 * this gate before downloading any advertised artifact.
 */
object OfflineRegionCatalogSignaturePolicy {
    fun unverifiedReleaseIds(
        catalog: OfflineRegionCatalog,
        verifier: OfflineRegionManifestVerifier,
    ): List<String> = catalog.regions.mapNotNull { entry ->
        val release = entry.latestRelease ?: return@mapNotNull null
        entry.region.regionId.takeUnless {
            runCatching { verifier.verify(release) }.getOrDefault(false)
        }
    }
}

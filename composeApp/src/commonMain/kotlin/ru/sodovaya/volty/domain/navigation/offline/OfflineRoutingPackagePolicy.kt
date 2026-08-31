package ru.sodovaya.volty.domain.navigation.offline

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

@Serializable
data class OfflineRegionBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    fun contains(coordinate: GeoCoordinate): Boolean =
        coordinate.latitude in south..north && coordinate.longitude in west..east
}

@Serializable
data class OfflinePackageFile(
    val name: String,
    val sha256: String,
)

@Serializable
data class OfflineRoutingPackageManifest(
    val formatVersion: Int,
    val packageVersion: Int,
    val packageId: String,
    val bounds: OfflineRegionBounds,
    val files: List<OfflinePackageFile>,
)

enum class OfflineManifestErrorCode {
    MALFORMED_MANIFEST,
    UNSUPPORTED_FORMAT_VERSION,
    INVALID_PACKAGE_VERSION,
    INVALID_PACKAGE_ID,
    INVALID_BOUNDS,
    NO_PAYLOAD_FILES,
    INVALID_FILE_NAME,
    DUPLICATE_FILE_NAME,
    INVALID_CHECKSUM,
    MISSING_FILE,
    UNEXPECTED_FILE,
    CHECKSUM_MISMATCH,
}

data class OfflineManifestValidationError(
    val code: OfflineManifestErrorCode,
    val detail: String,
)

sealed interface OfflineManifestParseResult {
    data class Success(val manifest: OfflineRoutingPackageManifest) : OfflineManifestParseResult

    data class Failure(val error: OfflineManifestValidationError) : OfflineManifestParseResult
}

sealed interface OfflinePackageValidation {
    data class Valid(val manifest: OfflineRoutingPackageManifest) : OfflinePackageValidation

    data class Invalid(val errors: List<OfflineManifestValidationError>) : OfflinePackageValidation
}

enum class OfflineCoverageEndpoint {
    ORIGIN,
    DESTINATION,
}

sealed interface OfflineCoverageResult {
    val requiresNetwork: Boolean

    data object Covered : OfflineCoverageResult {
        override val requiresNetwork: Boolean = false
    }

    data class Uncovered(val endpoint: OfflineCoverageEndpoint) : OfflineCoverageResult {
        override val requiresNetwork: Boolean = false
    }
}

enum class OfflinePackageInstallFailure {
    VALIDATION_FAILED,
    INTERRUPTED,
    STORAGE_FAILED,
}

sealed interface OfflinePackageInstallResult {
    data class Installed(val manifest: OfflineRoutingPackageManifest) : OfflinePackageInstallResult

    data class Rejected(
        val failure: OfflinePackageInstallFailure,
        val errors: List<OfflineManifestValidationError> = emptyList(),
    ) : OfflinePackageInstallResult
}

sealed interface OfflinePackageActivationDecision {
    data class Activate(val manifest: OfflineRoutingPackageManifest) : OfflinePackageActivationDecision

    data class RetainPrevious(
        val previous: OfflineRoutingPackageManifest?,
        val failure: OfflinePackageInstallFailure,
        val deleteStaging: Boolean,
    ) : OfflinePackageActivationDecision
}

object OfflinePackageManifestCodec {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
        isLenient = false
    }

    fun parse(jsonText: String): OfflineManifestParseResult = try {
        OfflineManifestParseResult.Success(
            json.decodeFromString<OfflineRoutingPackageManifest>(jsonText),
        )
    } catch (_: SerializationException) {
        malformedManifest()
    } catch (_: IllegalArgumentException) {
        malformedManifest()
    }

    private fun malformedManifest() = OfflineManifestParseResult.Failure(
        OfflineManifestValidationError(
            code = OfflineManifestErrorCode.MALFORMED_MANIFEST,
            detail = "manifest.json is not a valid offline routing manifest",
        ),
    )
}

object OfflineRoutingPolicy {
    const val CURRENT_FORMAT_VERSION: Int = 1
    const val MANIFEST_FILE_NAME: String = "manifest.json"

    fun parseManifest(jsonText: String): OfflineManifestParseResult =
        OfflinePackageManifestCodec.parse(jsonText)

    fun validatePackage(
        manifest: OfflineRoutingPackageManifest,
        actualSha256ByFileName: Map<String, String>,
    ): OfflinePackageValidation {
        val errors = validateManifest(manifest).toMutableList()
        val declaredNames = manifest.files.map { it.name }
        val declaredNameSet = declaredNames.toSet()

        manifest.files.forEach { file ->
            if (file.name in declaredNameSet && file.name in actualSha256ByFileName) {
                val actualChecksum = actualSha256ByFileName.getValue(file.name)
                if (!actualChecksum.equals(file.sha256, ignoreCase = true)) {
                    errors += OfflineManifestValidationError(
                        code = OfflineManifestErrorCode.CHECKSUM_MISMATCH,
                        detail = file.name,
                    )
                }
            } else if (file.name !in actualSha256ByFileName) {
                errors += OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.MISSING_FILE,
                    detail = file.name,
                )
            }
        }
        actualSha256ByFileName.keys
            .filter { it !in declaredNameSet }
            .forEach { name ->
                errors += OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.UNEXPECTED_FILE,
                    detail = name,
                )
            }

        return if (errors.isEmpty()) {
            OfflinePackageValidation.Valid(manifest)
        } else {
            OfflinePackageValidation.Invalid(errors)
        }
    }

    fun validateManifest(manifest: OfflineRoutingPackageManifest): List<OfflineManifestValidationError> {
        val errors = mutableListOf<OfflineManifestValidationError>()
        if (manifest.formatVersion != CURRENT_FORMAT_VERSION) {
            errors += OfflineManifestValidationError(
                code = OfflineManifestErrorCode.UNSUPPORTED_FORMAT_VERSION,
                detail = manifest.formatVersion.toString(),
            )
        }
        if (manifest.packageVersion <= 0) {
            errors += OfflineManifestValidationError(
                code = OfflineManifestErrorCode.INVALID_PACKAGE_VERSION,
                detail = manifest.packageVersion.toString(),
            )
        }
        if (!PACKAGE_ID_PATTERN.matches(manifest.packageId)) {
            errors += OfflineManifestValidationError(
                code = OfflineManifestErrorCode.INVALID_PACKAGE_ID,
                detail = manifest.packageId,
            )
        }
        if (!manifest.bounds.isValid()) {
            errors += OfflineManifestValidationError(
                code = OfflineManifestErrorCode.INVALID_BOUNDS,
                detail = manifest.bounds.toString(),
            )
        }
        if (manifest.files.isEmpty()) {
            errors += OfflineManifestValidationError(
                code = OfflineManifestErrorCode.NO_PAYLOAD_FILES,
                detail = "files",
            )
        }

        val names = mutableSetOf<String>()
        manifest.files.forEach { file ->
            if (!PAYLOAD_FILE_PATTERN.matches(file.name) || file.name == MANIFEST_FILE_NAME) {
                errors += OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.INVALID_FILE_NAME,
                    detail = file.name,
                )
            }
            if (!names.add(file.name)) {
                errors += OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.DUPLICATE_FILE_NAME,
                    detail = file.name,
                )
            }
            if (!SHA256_PATTERN.matches(file.sha256)) {
                errors += OfflineManifestValidationError(
                    code = OfflineManifestErrorCode.INVALID_CHECKSUM,
                    detail = file.name,
                )
            }
        }
        return errors
    }

    fun coverage(
        manifest: OfflineRoutingPackageManifest,
        origin: GeoCoordinate,
        destination: GeoCoordinate,
    ): OfflineCoverageResult = when {
        !manifest.bounds.contains(origin) -> OfflineCoverageResult.Uncovered(OfflineCoverageEndpoint.ORIGIN)
        !manifest.bounds.contains(destination) ->
            OfflineCoverageResult.Uncovered(OfflineCoverageEndpoint.DESTINATION)
        else -> OfflineCoverageResult.Covered
    }

    private fun OfflineRegionBounds.isValid(): Boolean =
        south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite() &&
            south in -90.0..90.0 && north in -90.0..90.0 &&
            west in -180.0..180.0 && east in -180.0..180.0 &&
            south <= north && west <= east

    private val PACKAGE_ID_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val PAYLOAD_FILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,254}")
    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
}

object OfflinePackageInstallPolicy {
    fun decide(
        previous: OfflineRoutingPackageManifest?,
        validation: OfflinePackageValidation,
        failure: OfflinePackageInstallFailure?,
    ): OfflinePackageActivationDecision = if (
        failure == null && validation is OfflinePackageValidation.Valid
    ) {
        OfflinePackageActivationDecision.Activate(validation.manifest)
    } else {
        OfflinePackageActivationDecision.RetainPrevious(
            previous = previous,
            failure = failure ?: OfflinePackageInstallFailure.VALIDATION_FAILED,
            deleteStaging = true,
        )
    }
}

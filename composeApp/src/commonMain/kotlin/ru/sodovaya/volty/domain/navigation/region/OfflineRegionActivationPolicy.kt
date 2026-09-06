package ru.sodovaya.volty.domain.navigation.region

enum class OfflineRegionInstallFailure {
    MANIFEST_INVALID,
    ARTIFACT_INVALID,
    INTERRUPTED,
    STORAGE_FAILED,
}

sealed interface OfflineRegionActivationError {
    data class Manifest(val error: OfflineRegionManifestValidationError) : OfflineRegionActivationError

    data class Artifact(val error: OfflineRegionArtifactValidationError) : OfflineRegionActivationError
}

sealed interface OfflineRegionActivationDecision {
    data class Activate(val manifest: OfflineRegionPackageManifest) : OfflineRegionActivationDecision

    data class RetainPrevious(
        val previous: OfflineRegionPackageManifest?,
        val failure: OfflineRegionInstallFailure,
        val deleteStaging: Boolean,
        val errors: List<OfflineRegionActivationError> = emptyList(),
    ) : OfflineRegionActivationDecision
}

/** The last gate before a staged regional release becomes visible to any consumer. */
object OfflineRegionActivationPolicy {
    fun decide(
        previous: OfflineRegionPackageManifest?,
        candidate: OfflineRegionPackageManifest,
        manifestErrors: List<OfflineRegionManifestValidationError>,
        artifacts: OfflineRegionArtifactValidation,
        failure: OfflineRegionInstallFailure?,
    ): OfflineRegionActivationDecision {
        val errors = buildList {
            manifestErrors.forEach { add(OfflineRegionActivationError.Manifest(it)) }
            if (artifacts is OfflineRegionArtifactValidation.Invalid) {
                artifacts.errors.forEach { add(OfflineRegionActivationError.Artifact(it)) }
            }
        }
        val artifactsMatchCandidate = artifacts is OfflineRegionArtifactValidation.Valid &&
            artifacts.manifest == candidate
        if (failure == null && errors.isEmpty() && artifactsMatchCandidate) {
            return OfflineRegionActivationDecision.Activate(candidate)
        }

        return OfflineRegionActivationDecision.RetainPrevious(
            previous = previous,
            failure = failure ?: if (manifestErrors.isNotEmpty()) {
                OfflineRegionInstallFailure.MANIFEST_INVALID
            } else {
                OfflineRegionInstallFailure.ARTIFACT_INVALID
            },
            deleteStaging = true,
            errors = errors,
        )
    }
}

package ru.sodovaya.volty.domain.navigation.region

enum class OfflineRegionComponent {
    ROUTING,
    SEARCH,
    MAP,
}

data class OfflineRegionArtifactObservation(
    val component: OfflineRegionComponent,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
)

enum class OfflineRegionArtifactErrorCode {
    MISSING_COMPONENT,
    DUPLICATE_COMPONENT,
    UNEXPECTED_COMPONENT,
    DOWNLOAD_SIZE_MISMATCH,
    INSTALLED_SIZE_MISMATCH,
    CHECKSUM_MISMATCH,
}

data class OfflineRegionArtifactValidationError(
    val code: OfflineRegionArtifactErrorCode,
    val component: OfflineRegionComponent,
)

sealed interface OfflineRegionArtifactValidation {
    data class Valid(val manifest: OfflineRegionPackageManifest) : OfflineRegionArtifactValidation

    data class Invalid(
        val errors: List<OfflineRegionArtifactValidationError>,
    ) : OfflineRegionArtifactValidation
}

/** Compares the three downloaded payloads with the signed release declaration. */
object OfflineRegionArtifactPolicy {
    fun validate(
        manifest: OfflineRegionPackageManifest,
        observations: List<OfflineRegionArtifactObservation>,
    ): OfflineRegionArtifactValidation {
        val errors = mutableListOf<OfflineRegionArtifactValidationError>()
        val duplicateComponents = observations
            .groupingBy(OfflineRegionArtifactObservation::component)
            .eachCount()
            .filterValues { it > 1 }
            .keys
        duplicateComponents.forEach { component ->
            errors += OfflineRegionArtifactValidationError(
                code = OfflineRegionArtifactErrorCode.DUPLICATE_COMPONENT,
                component = component,
            )
        }

        val observationsByComponent = observations.associateBy { it.component }
        OfflineRegionComponent.entries.forEach { component ->
            val observation = observationsByComponent[component]
            if (observation == null) {
                errors += OfflineRegionArtifactValidationError(
                    code = OfflineRegionArtifactErrorCode.MISSING_COMPONENT,
                    component = component,
                )
                return@forEach
            }
            val declared = manifest.artifact(component)
            if (observation.downloadBytes != declared.downloadBytes) {
                errors += OfflineRegionArtifactValidationError(
                    code = OfflineRegionArtifactErrorCode.DOWNLOAD_SIZE_MISMATCH,
                    component = component,
                )
            }
            if (observation.installedBytes != declared.installedBytes) {
                errors += OfflineRegionArtifactValidationError(
                    code = OfflineRegionArtifactErrorCode.INSTALLED_SIZE_MISMATCH,
                    component = component,
                )
            }
            if (!observation.sha256.equals(declared.sha256, ignoreCase = true)) {
                errors += OfflineRegionArtifactValidationError(
                    code = OfflineRegionArtifactErrorCode.CHECKSUM_MISMATCH,
                    component = component,
                )
            }
        }
        observationsByComponent.keys
            .filter { it !in OfflineRegionComponent.entries }
            .forEach { component ->
                errors += OfflineRegionArtifactValidationError(
                    code = OfflineRegionArtifactErrorCode.UNEXPECTED_COMPONENT,
                    component = component,
                )
            }

        return if (errors.isEmpty()) {
            OfflineRegionArtifactValidation.Valid(manifest)
        } else {
            OfflineRegionArtifactValidation.Invalid(errors)
        }
    }

    private fun OfflineRegionPackageManifest.artifact(
        component: OfflineRegionComponent,
    ): ArtifactSizeAndChecksum = when (component) {
        OfflineRegionComponent.ROUTING -> components.routing.toComparableArtifact()
        OfflineRegionComponent.SEARCH -> components.search.toComparableArtifact()
        OfflineRegionComponent.MAP -> components.map.toComparableArtifact()
    }

    private fun OfflineRegionRoutingArtifact.toComparableArtifact() =
        ArtifactSizeAndChecksum(downloadBytes, installedBytes, sha256)

    private fun OfflineRegionSearchArtifact.toComparableArtifact() =
        ArtifactSizeAndChecksum(downloadBytes, installedBytes, sha256)

    private fun OfflineRegionMapArtifact.toComparableArtifact() =
        ArtifactSizeAndChecksum(downloadBytes, installedBytes, sha256)

    private data class ArtifactSizeAndChecksum(
        val downloadBytes: Long,
        val installedBytes: Long,
        val sha256: String,
    )
}

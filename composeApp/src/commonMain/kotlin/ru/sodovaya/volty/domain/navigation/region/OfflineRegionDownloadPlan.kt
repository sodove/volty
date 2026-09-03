package ru.sodovaya.volty.domain.navigation.region

/** The only files a regional release is allowed to publish into app-private storage. */
enum class OfflineRegionArtifactFile(
    val component: OfflineRegionComponent,
    val relativePath: String,
) {
    ROUTING(OfflineRegionComponent.ROUTING, "routing/valhalla-routing.tar.gz"),
    SEARCH(OfflineRegionComponent.SEARCH, "search/places.sqlite.gz"),
    MAP(OfflineRegionComponent.MAP, "map/map.pmtiles"),
}

data class OfflineRegionArtifactDownload(
    val component: OfflineRegionComponent,
    val relativePath: String,
    val url: String,
    val downloadBytes: Long,
    val installedBytes: Long,
    val sha256: String,
)

data class OfflineRegionDownloadPlan(
    val regionId: String,
    val releaseVersion: String,
    val artifacts: List<OfflineRegionArtifactDownload>,
) {
    init {
        require(artifacts.map(OfflineRegionArtifactDownload::component).toSet() == OfflineRegionComponent.entries.toSet()) {
            "a regional release must contain exactly one routing, search, and map artifact"
        }
        require(artifacts.map(OfflineRegionArtifactDownload::relativePath).toSet().size == artifacts.size) {
            "regional artifact paths must be unique"
        }
    }

    val totalDownloadBytes: Long
        get() = artifacts.sumOf(OfflineRegionArtifactDownload::downloadBytes)

    val totalInstalledBytes: Long
        get() = artifacts.sumOf(OfflineRegionArtifactDownload::installedBytes)
}

sealed interface OfflineRegionDownloadPlanResult {
    data class Ready(val plan: OfflineRegionDownloadPlan) : OfflineRegionDownloadPlanResult

    data class Rejected(
        val errors: List<OfflineRegionManifestValidationError>,
    ) : OfflineRegionDownloadPlanResult
}

/** Converts the signed release envelope into a bounded, deterministic storage plan. */
object OfflineRegionDownloadPlanFactory {
    fun create(
        manifest: OfflineRegionPackageManifest,
        currentAppVersionCode: Int,
    ): OfflineRegionDownloadPlanResult {
        val errors = OfflineRegionPackageManifestPolicy.validate(manifest, currentAppVersionCode)
        if (errors.isNotEmpty()) return OfflineRegionDownloadPlanResult.Rejected(errors)

        return OfflineRegionDownloadPlanResult.Ready(
            OfflineRegionDownloadPlan(
                regionId = manifest.regionId,
                releaseVersion = manifest.releaseVersion,
                artifacts = listOf(
                    OfflineRegionArtifactDownload(
                        component = OfflineRegionComponent.ROUTING,
                        relativePath = OfflineRegionArtifactFile.ROUTING.relativePath,
                        url = manifest.components.routing.url,
                        downloadBytes = manifest.components.routing.downloadBytes,
                        installedBytes = manifest.components.routing.installedBytes,
                        sha256 = manifest.components.routing.sha256,
                    ),
                    OfflineRegionArtifactDownload(
                        component = OfflineRegionComponent.SEARCH,
                        relativePath = OfflineRegionArtifactFile.SEARCH.relativePath,
                        url = manifest.components.search.url,
                        downloadBytes = manifest.components.search.downloadBytes,
                        installedBytes = manifest.components.search.installedBytes,
                        sha256 = manifest.components.search.sha256,
                    ),
                    OfflineRegionArtifactDownload(
                        component = OfflineRegionComponent.MAP,
                        relativePath = OfflineRegionArtifactFile.MAP.relativePath,
                        url = manifest.components.map.url,
                        downloadBytes = manifest.components.map.downloadBytes,
                        installedBytes = manifest.components.map.installedBytes,
                        sha256 = manifest.components.map.sha256,
                    ),
                ),
            ),
        )
    }
}

sealed interface OfflineRegionResumeDecision {
    data class Resume(val offsetBytes: Long) : OfflineRegionResumeDecision

    data object Restart : OfflineRegionResumeDecision
}

object OfflineRegionResumePolicy {
    fun decide(partialBytes: Long, expectedBytes: Long): OfflineRegionResumeDecision =
        if (partialBytes in 0 until expectedBytes && expectedBytes > 0L) {
            OfflineRegionResumeDecision.Resume(partialBytes)
        } else {
            OfflineRegionResumeDecision.Restart
        }
}

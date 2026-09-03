package ru.sodovaya.volty.domain.navigation.region

import ru.sodovaya.volty.domain.navigation.GeoCoordinate

/** A catalog entry plus the lifecycle state of its local package. */
data class OfflineRegionPackageSnapshot(
    val manifest: OfflineRegionManifest,
    val status: OfflineRegionPackageStatus,
)

sealed interface OfflineRegionAccessDecision {
    data class UseOffline(val regionId: String) : OfflineRegionAccessDecision

    data class StartDownload(
        val regionId: String,
        val trigger: OfflineRegionDownloadTrigger,
    ) : OfflineRegionAccessDecision

    data class RequestMeteredApproval(
        val regionId: String,
        val trigger: OfflineRegionDownloadTrigger,
    ) : OfflineRegionAccessDecision

    data class WaitForDownload(val regionId: String) : OfflineRegionAccessDecision

    data class UseOnlineFallback(val missingRegionIds: List<String>) : OfflineRegionAccessDecision

    data object UnavailableOffline : OfflineRegionAccessDecision
}

/**
 * Chooses one source for search, route, or map access. A package is usable only after its
 * atomic install reaches READY; an update keeps the previous usable release active.
 */
object OfflineRegionAccessPolicy {
    fun decide(
        points: List<GeoCoordinate>,
        packages: List<OfflineRegionPackageSnapshot>,
        network: OfflineNetworkAvailability,
        trigger: OfflineRegionDownloadTrigger,
        preferences: OfflineDownloadPreferences,
        meteredConfirmed: Boolean = false,
        allowOnlineFallback: Boolean = false,
    ): OfflineRegionAccessDecision {
        require(points.isNotEmpty()) { "at least one point is required" }

        val matching = packages
            .filter { packageSnapshot -> points.all(packageSnapshot.manifest.bounds::contains) }
            .groupBy { it.manifest.regionId }
            .values
            .map { entries -> entries.minBy { it.status.priority() } }
        val usable = matching.firstOrNull { it.status.isUsableOffline() }
        if (usable != null) return OfflineRegionAccessDecision.UseOffline(usable.manifest.regionId)

        val operationInProgress = matching.firstOrNull { it.status.isDownloadInProgress() }
        if (operationInProgress != null) {
            return OfflineRegionAccessDecision.WaitForDownload(operationInProgress.manifest.regionId)
        }

        val candidate = matching.firstOrNull { it.status.canStartDownload() }
        if (candidate != null) {
            return when (
                val downloadDecision = OfflineRegionDownloadPolicy.decide(
                    network = network,
                    trigger = trigger,
                    preferences = preferences,
                    meteredConfirmed = meteredConfirmed,
                )
            ) {
                OfflineDownloadDecision.Allowed -> OfflineRegionAccessDecision.StartDownload(
                    regionId = candidate.manifest.regionId,
                    trigger = trigger,
                )

                OfflineDownloadDecision.RequiresMeteredConfirmation -> if (allowOnlineFallback) {
                    OfflineRegionAccessDecision.UseOnlineFallback(listOf(candidate.manifest.regionId))
                } else {
                    OfflineRegionAccessDecision.RequestMeteredApproval(
                        regionId = candidate.manifest.regionId,
                        trigger = trigger,
                    )
                }

                is OfflineDownloadDecision.Blocked -> OfflineRegionAccessDecision.UnavailableOffline
            }
        }

        return if (network == OfflineNetworkAvailability.OFFLINE) {
            OfflineRegionAccessDecision.UnavailableOffline
        } else {
            OfflineRegionAccessDecision.UseOnlineFallback(
                missingRegionIds = matching.map { it.manifest.regionId },
            )
        }
    }

    private fun OfflineRegionPackageStatus.isUsableOffline(): Boolean = this ==
        OfflineRegionPackageStatus.READY || this == OfflineRegionPackageStatus.UPDATE_AVAILABLE

    private fun OfflineRegionPackageStatus.isDownloadInProgress(): Boolean = this ==
        OfflineRegionPackageStatus.QUEUED || this == OfflineRegionPackageStatus.WAITING_FOR_NETWORK ||
        this == OfflineRegionPackageStatus.DOWNLOADING || this == OfflineRegionPackageStatus.PAUSED ||
        this == OfflineRegionPackageStatus.VERIFYING || this == OfflineRegionPackageStatus.INSTALLING ||
        this == OfflineRegionPackageStatus.DELETING

    private fun OfflineRegionPackageStatus.canStartDownload(): Boolean = this ==
        OfflineRegionPackageStatus.NOT_INSTALLED || this == OfflineRegionPackageStatus.FAILED ||
        this == OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL

    private fun OfflineRegionPackageStatus.priority(): Int = when {
        isUsableOffline() -> 0
        this == OfflineRegionPackageStatus.QUEUED ||
            this == OfflineRegionPackageStatus.WAITING_FOR_NETWORK ||
            this == OfflineRegionPackageStatus.DOWNLOADING ||
            this == OfflineRegionPackageStatus.PAUSED ||
            this == OfflineRegionPackageStatus.VERIFYING ||
            this == OfflineRegionPackageStatus.INSTALLING ||
            this == OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL -> 1

        canStartDownload() -> 2
        else -> 3
    }
}

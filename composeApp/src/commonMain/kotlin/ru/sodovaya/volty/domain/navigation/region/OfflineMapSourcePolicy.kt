package ru.sodovaya.volty.domain.navigation.region

/** The part of the map currently visible to the rider. */
data class OfflineMapViewport(
    val bounds: OfflineRegionBounds,
)

sealed interface OfflineMapSourceDecision {
    data class UseOffline(val regionId: String) : OfflineMapSourceDecision

    /** Keep rendering online tiles while the missing region is fetched in the background. */
    data class UseOnlineAndStartDownload(val regionId: String) : OfflineMapSourceDecision

    /** Keep rendering online tiles while an automatic regional download is in flight. */
    data class UseOnlineAndWait(val regionId: String) : OfflineMapSourceDecision

    /** The map can remain online, but the user must approve a metered download first. */
    data class UseOnlineAndRequestMeteredApproval(val regionId: String) : OfflineMapSourceDecision

    data object UseOnline : OfflineMapSourceDecision

    data object UnavailableOffline : OfflineMapSourceDecision
}

/** Selects a complete installed PMTiles package without making the renderer network-aware. */
object OfflineMapSourcePolicy {
    fun select(
        viewport: OfflineMapViewport,
        packages: List<OfflineRegionPackageSnapshot>,
        network: OfflineNetworkAvailability,
        preferences: OfflineDownloadPreferences = OfflineDownloadPreferences(),
        meteredConfirmed: Boolean = false,
    ): OfflineMapSourceDecision {
        val localRegion = packages
            .asSequence()
            .filter { it.status.isUsableOffline() }
            .filter { packageSnapshot -> viewport.isInside(packageSnapshot.manifest.bounds) }
            .map { it.manifest.regionId }
            .distinct()
            .sorted()
            .firstOrNull()

        if (localRegion != null) return OfflineMapSourceDecision.UseOffline(localRegion)
        val matchingRegion = packages
            .asSequence()
            .filter { packageSnapshot -> viewport.isInside(packageSnapshot.manifest.bounds) }
            .sortedBy { it.manifest.regionId }
            .firstOrNull()

        if (matchingRegion != null) {
            if (matchingRegion.status.isDownloadInProgress()) {
                return if (network == OfflineNetworkAvailability.OFFLINE) {
                    OfflineMapSourceDecision.UnavailableOffline
                } else {
                    OfflineMapSourceDecision.UseOnlineAndWait(matchingRegion.manifest.regionId)
                }
            }
            if (matchingRegion.status.canStartDownload()) {
                return when (
                    OfflineRegionDownloadPolicy.decide(
                        network = network,
                        trigger = OfflineRegionDownloadTrigger.MAP,
                        preferences = preferences,
                        meteredConfirmed = meteredConfirmed,
                    )
                ) {
                    OfflineDownloadDecision.Allowed ->
                        OfflineMapSourceDecision.UseOnlineAndStartDownload(matchingRegion.manifest.regionId)

                    OfflineDownloadDecision.RequiresMeteredConfirmation ->
                        OfflineMapSourceDecision.UseOnlineAndRequestMeteredApproval(matchingRegion.manifest.regionId)

                    is OfflineDownloadDecision.Blocked -> OfflineMapSourceDecision.UnavailableOffline
                }
            }
        }

        return if (network == OfflineNetworkAvailability.OFFLINE) {
            OfflineMapSourceDecision.UnavailableOffline
        } else {
            OfflineMapSourceDecision.UseOnline
        }
    }

    private fun OfflineMapViewport.isInside(region: OfflineRegionBounds): Boolean =
        bounds.south >= region.south &&
            bounds.west >= region.west &&
            bounds.north <= region.north &&
            bounds.east <= region.east

    private fun OfflineRegionPackageStatus.isUsableOffline(): Boolean = this ==
        OfflineRegionPackageStatus.READY || this == OfflineRegionPackageStatus.UPDATE_AVAILABLE

    private fun OfflineRegionPackageStatus.isDownloadInProgress(): Boolean = this ==
        OfflineRegionPackageStatus.QUEUED || this == OfflineRegionPackageStatus.WAITING_FOR_NETWORK ||
        this == OfflineRegionPackageStatus.DOWNLOADING || this == OfflineRegionPackageStatus.VERIFYING ||
        this == OfflineRegionPackageStatus.INSTALLING ||
        this == OfflineRegionPackageStatus.DELETING

    private fun OfflineRegionPackageStatus.canStartDownload(): Boolean = this ==
        OfflineRegionPackageStatus.NOT_INSTALLED || this == OfflineRegionPackageStatus.FAILED ||
        this == OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL
}

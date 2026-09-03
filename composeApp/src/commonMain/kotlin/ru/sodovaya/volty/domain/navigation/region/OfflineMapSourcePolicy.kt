package ru.sodovaya.volty.domain.navigation.region

/** The part of the map currently visible to the rider. */
data class OfflineMapViewport(
    val bounds: OfflineRegionBounds,
)

sealed interface OfflineMapSourceDecision {
    data class UseOffline(val regionId: String) : OfflineMapSourceDecision

    data object UseOnline : OfflineMapSourceDecision

    data object UnavailableOffline : OfflineMapSourceDecision
}

/** Selects a complete installed PMTiles package without making the renderer network-aware. */
object OfflineMapSourcePolicy {
    fun select(
        viewport: OfflineMapViewport,
        packages: List<OfflineRegionPackageSnapshot>,
        network: OfflineNetworkAvailability,
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
}

package ru.sodovaya.volty.domain.navigation.region

import kotlinx.serialization.Serializable
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

/** Geographic extent used to decide whether a regional package can serve a request. */
@Serializable
data class OfflineRegionBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    init {
        require(south in -90.0..90.0) { "south must be a valid latitude" }
        require(north in -90.0..90.0) { "north must be a valid latitude" }
        require(west in -180.0..180.0) { "west must be a valid longitude" }
        require(east in -180.0..180.0) { "east must be a valid longitude" }
        require(south <= north) { "south must not be north of north" }
        require(west <= east) { "west must not be east of east" }
    }

    fun contains(point: GeoCoordinate): Boolean =
        point.latitude in south..north && point.longitude in west..east
}

@Serializable
data class OfflineRegionManifest(
    val regionId: String,
    val displayName: String,
    val bounds: OfflineRegionBounds,
) {
    init {
        require(regionId.isNotBlank()) { "regionId must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}

enum class OfflineRegionPackageStatus {
    NOT_INSTALLED,
    QUEUED,
    WAITING_FOR_NETWORK,
    AWAITING_METERED_APPROVAL,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    INSTALLING,
    READY,
    UPDATE_AVAILABLE,
    FAILED,
    DELETING,
}

sealed interface RegionCoverageResolution {
    data class Covered(val regionIds: List<String>) : RegionCoverageResolution

    data class Missing(val regionIds: List<String>) : RegionCoverageResolution
}

/**
 * Resolves coverage before invoking the local router. A route is covered only when one
 * installed package contains the complete request, so a route cannot silently cross a
 * package boundary with only one side available.
 */
object RegionCoveragePolicy {
    fun resolve(
        points: List<GeoCoordinate>,
        regions: List<OfflineRegionManifest>,
    ): RegionCoverageResolution {
        require(points.isNotEmpty()) { "at least one route point is required" }

        val coveringRegion = regions.firstOrNull { region ->
            points.all(region.bounds::contains)
        }

        return if (coveringRegion != null) {
            RegionCoverageResolution.Covered(listOf(coveringRegion.regionId))
        } else {
            RegionCoverageResolution.Missing(regions.map(OfflineRegionManifest::regionId).distinct())
        }
    }
}

package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineRegionModelsTest {
    @Test
    fun route_is_covered_when_all_points_are_inside_an_installed_region() {
        val region = region()

        assertEquals(
            RegionCoverageResolution.Covered(listOf(region.regionId)),
            RegionCoveragePolicy.resolve(
                points = listOf(
                    GeoCoordinate(56.80, 60.50),
                    GeoCoordinate(56.90, 60.70),
                ),
                regions = listOf(region),
            ),
        )
    }

    @Test
    fun missing_coverage_reports_the_regions_needed_for_each_uncovered_point() {
        val region = region()

        assertEquals(
            RegionCoverageResolution.Missing(listOf(region.regionId)),
            RegionCoveragePolicy.resolve(
                points = listOf(
                    GeoCoordinate(56.80, 60.50),
                    GeoCoordinate(58.00, 60.70),
                ),
                regions = listOf(region),
            ),
        )
    }

    @Test
    fun package_status_exposes_installability_without_ui_specific_states() {
        assertEquals(
            listOf(
                OfflineRegionPackageStatus.NOT_INSTALLED,
                OfflineRegionPackageStatus.QUEUED,
                OfflineRegionPackageStatus.WAITING_FOR_NETWORK,
                OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL,
                OfflineRegionPackageStatus.DOWNLOADING,
                OfflineRegionPackageStatus.PAUSED,
                OfflineRegionPackageStatus.VERIFYING,
                OfflineRegionPackageStatus.INSTALLING,
                OfflineRegionPackageStatus.READY,
                OfflineRegionPackageStatus.UPDATE_AVAILABLE,
                OfflineRegionPackageStatus.FAILED,
                OfflineRegionPackageStatus.DELETING,
            ),
            OfflineRegionPackageStatus.entries,
        )
    }

    private fun region() = OfflineRegionManifest(
        regionId = "ru-sve-yekaterinburg-agglomeration",
        displayName = "Екатеринбург и агломерация",
        bounds = OfflineRegionBounds(
            south = 56.30,
            west = 59.55,
            north = 57.25,
            east = 61.45,
        ),
    )
}

package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineRegionAccessPolicyTest {
    @Test
    fun ready_region_is_used_without_network() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.READY)),
            network = OfflineNetworkAvailability.OFFLINE,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
        )

        assertEquals(
            OfflineRegionAccessDecision.UseOffline(regionId = regionId),
            decision,
        )
    }

    @Test
    fun missing_region_starts_automatically_on_unmetered_network() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.UNMETERED,
            trigger = OfflineRegionDownloadTrigger.SEARCH,
            preferences = OfflineDownloadPreferences(),
        )

        assertEquals(
            OfflineRegionAccessDecision.StartDownload(
                regionId = regionId,
                trigger = OfflineRegionDownloadTrigger.SEARCH,
            ),
            decision,
        )
    }

    @Test
    fun missing_region_asks_before_metered_download_and_can_fallback_online() {
        val prompt = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.METERED,
            trigger = OfflineRegionDownloadTrigger.MAP,
            preferences = OfflineDownloadPreferences(),
        )
        assertEquals(
            OfflineRegionAccessDecision.RequestMeteredApproval(regionId, OfflineRegionDownloadTrigger.MAP),
            prompt,
        )

        val fallback = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.METERED,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
            meteredConfirmed = false,
            allowOnlineFallback = true,
        )
        assertIs<OfflineRegionAccessDecision.UseOnlineFallback>(fallback)
    }

    @Test
    fun already_running_download_is_not_duplicated() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.DOWNLOADING)),
            network = OfflineNetworkAvailability.UNMETERED,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
        )

        assertEquals(
            OfflineRegionAccessDecision.WaitForDownload(regionId),
            decision,
        )
    }

    @Test
    fun usable_release_wins_when_catalog_contains_an_older_entry_first() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(
                packageSnapshot(OfflineRegionPackageStatus.NOT_INSTALLED),
                packageSnapshot(OfflineRegionPackageStatus.READY),
            ),
            network = OfflineNetworkAvailability.OFFLINE,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
        )

        assertEquals(OfflineRegionAccessDecision.UseOffline(regionId), decision)
    }

    @Test
    fun metered_setting_allows_automatic_download_without_prompt() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.METERED,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(skipMeteredConfirmation = true),
        )

        assertEquals(
            OfflineRegionAccessDecision.StartDownload(regionId, OfflineRegionDownloadTrigger.ROUTE),
            decision,
        )
    }

    @Test
    fun explicit_metered_approval_releases_a_waiting_package() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = listOf(packageSnapshot(OfflineRegionPackageStatus.AWAITING_METERED_APPROVAL)),
            network = OfflineNetworkAvailability.METERED,
            trigger = OfflineRegionDownloadTrigger.SETTINGS,
            preferences = OfflineDownloadPreferences(),
            meteredConfirmed = true,
        )

        assertEquals(
            OfflineRegionAccessDecision.StartDownload(regionId, OfflineRegionDownloadTrigger.SETTINGS),
            decision,
        )
    }

    @Test
    fun unknown_catalog_region_is_not_reported_as_offline_coverage() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = routePoints,
            packages = emptyList(),
            network = OfflineNetworkAvailability.OFFLINE,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
        )

        assertEquals(
            OfflineRegionAccessDecision.UnavailableOffline,
            decision,
        )
    }

    @Test
    fun route_spanning_known_regions_reports_each_missing_endpoint_region() {
        val decision = OfflineRegionAccessPolicy.decide(
            points = spanningRoutePoints,
            packages = listOf(
                packageSnapshot(
                    regionId = "ekb",
                    status = OfflineRegionPackageStatus.NOT_INSTALLED,
                    bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
                ),
                packageSnapshot(
                    regionId = "tyumen",
                    status = OfflineRegionPackageStatus.NOT_INSTALLED,
                    bounds = OfflineRegionBounds(57.0, 61.0, 58.0, 62.0),
                ),
            ),
            network = OfflineNetworkAvailability.UNMETERED,
            trigger = OfflineRegionDownloadTrigger.ROUTE,
            preferences = OfflineDownloadPreferences(),
            allowOnlineFallback = true,
        )

        assertEquals(
            OfflineRegionAccessDecision.UseOnlineFallback(listOf("ekb", "tyumen")),
            decision,
        )
    }

    private fun packageSnapshot(
        status: OfflineRegionPackageStatus,
        regionId: String = DEFAULT_REGION_ID,
        bounds: OfflineRegionBounds = OfflineRegionBounds(
            south = 56.0,
            west = 60.0,
            north = 57.0,
            east = 61.0,
        ),
    ) = OfflineRegionPackageSnapshot(
        manifest = OfflineRegionManifest(
            regionId = regionId,
            displayName = "Екатеринбург",
            bounds = bounds,
        ),
        status = status,
    )

    private companion object {
        const val regionId = "ru-sve-yekaterinburg-agglomeration"
        const val DEFAULT_REGION_ID = regionId
        val routePoints = listOf(
            GeoCoordinate(latitude = 56.80, longitude = 60.60),
            GeoCoordinate(latitude = 56.84, longitude = 60.64),
        )
        val spanningRoutePoints = listOf(
            GeoCoordinate(latitude = 56.80, longitude = 60.60),
            GeoCoordinate(latitude = 57.20, longitude = 61.20),
        )
    }
}

package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.GeoCoordinate

class OfflineMapSourcePolicyTest {
    @Test
    fun installed_region_wins_over_online_source() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.READY)),
            network = OfflineNetworkAvailability.UNMETERED,
        )

        assertEquals(OfflineMapSourceDecision.UseOffline("ekb"), decision)
    }

    @Test
    fun update_available_keeps_using_previous_local_map() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.UPDATE_AVAILABLE)),
            network = OfflineNetworkAvailability.OFFLINE,
        )

        assertEquals(OfflineMapSourceDecision.UseOffline("ekb"), decision)
    }

    @Test
    fun incomplete_package_uses_online_when_network_exists() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.DOWNLOADING)),
            network = OfflineNetworkAvailability.METERED,
        )

        assertEquals(OfflineMapSourceDecision.UseOnlineAndWait("ekb"), decision)
    }

    @Test
    fun incomplete_package_is_unavailable_when_network_drops() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.DOWNLOADING)),
            network = OfflineNetworkAvailability.OFFLINE,
        )

        assertEquals(OfflineMapSourceDecision.UnavailableOffline, decision)
    }

    @Test
    fun missing_region_starts_background_download_on_wifi_while_map_stays_online() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.UNMETERED,
        )

        assertEquals(OfflineMapSourceDecision.UseOnlineAndStartDownload("ekb"), decision)
    }

    @Test
    fun missing_region_requests_mobile_confirmation() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.METERED,
        )

        assertEquals(OfflineMapSourceDecision.UseOnlineAndRequestMeteredApproval("ekb"), decision)
    }

    @Test
    fun metered_setting_allows_background_map_download() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = listOf(snapshot(OfflineRegionPackageStatus.NOT_INSTALLED)),
            network = OfflineNetworkAvailability.METERED,
            preferences = OfflineDownloadPreferences(skipMeteredConfirmation = true),
        )

        assertEquals(OfflineMapSourceDecision.UseOnlineAndStartDownload("ekb"), decision)
    }

    @Test
    fun missing_package_is_explicitly_unavailable_offline() {
        val decision = OfflineMapSourcePolicy.select(
            viewport = viewport(),
            packages = emptyList(),
            network = OfflineNetworkAvailability.OFFLINE,
        )

        assertEquals(OfflineMapSourceDecision.UnavailableOffline, decision)
    }

    private fun viewport() = OfflineMapViewport(
        bounds = OfflineRegionBounds(
            south = 56.80,
            west = 60.55,
            north = 56.88,
            east = 60.66,
        ),
    )

    private fun snapshot(status: OfflineRegionPackageStatus) = OfflineRegionPackageSnapshot(
        manifest = OfflineRegionManifest(
            regionId = "ekb",
            displayName = "Екатеринбург",
            bounds = OfflineRegionBounds(56.70, 60.40, 57.00, 60.90),
        ),
        status = status,
    )
}

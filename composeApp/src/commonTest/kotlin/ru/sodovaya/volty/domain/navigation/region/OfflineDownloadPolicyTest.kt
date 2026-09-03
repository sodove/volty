package ru.sodovaya.volty.domain.navigation.region

import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineDownloadPolicyTest {
    @Test
    fun missing_region_can_download_automatically_on_unmetered_network() {
        assertEquals(
            OfflineDownloadDecision.Allowed,
            OfflineRegionDownloadPolicy.decide(
                network = OfflineNetworkAvailability.UNMETERED,
                trigger = OfflineRegionDownloadTrigger.ROUTE,
                preferences = OfflineDownloadPreferences(),
            ),
        )
    }

    @Test
    fun automatic_metered_download_requires_confirmation_by_default() {
        assertEquals(
            OfflineDownloadDecision.RequiresMeteredConfirmation,
            OfflineRegionDownloadPolicy.decide(
                network = OfflineNetworkAvailability.METERED,
                trigger = OfflineRegionDownloadTrigger.SEARCH,
                preferences = OfflineDownloadPreferences(),
            ),
        )
    }

    @Test
    fun settings_checkbox_skips_metered_confirmation() {
        assertEquals(
            OfflineDownloadDecision.Allowed,
            OfflineRegionDownloadPolicy.decide(
                network = OfflineNetworkAvailability.METERED,
                trigger = OfflineRegionDownloadTrigger.MAP,
                preferences = OfflineDownloadPreferences(skipMeteredConfirmation = true),
            ),
        )
    }

    @Test
    fun explicit_metered_confirmation_allows_the_pending_download() {
        assertEquals(
            OfflineDownloadDecision.Allowed,
            OfflineRegionDownloadPolicy.decide(
                network = OfflineNetworkAvailability.METERED,
                trigger = OfflineRegionDownloadTrigger.SETTINGS,
                preferences = OfflineDownloadPreferences(),
                meteredConfirmed = true,
            ),
        )
    }

    @Test
    fun offline_network_never_starts_a_download() {
        assertEquals(
            OfflineDownloadDecision.Blocked(OfflineDownloadBlockReason.NO_NETWORK),
            OfflineRegionDownloadPolicy.decide(
                network = OfflineNetworkAvailability.OFFLINE,
                trigger = OfflineRegionDownloadTrigger.ROUTE,
                preferences = OfflineDownloadPreferences(),
            ),
        )
    }
}

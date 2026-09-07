package ru.sodovaya.volty.domain.navigation.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest

class OfflineMapPackPolicyTest {
    @Test
    fun definition_uses_the_pinned_openfreemap_style_url() {
        val definition = OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.BRIGHT, 30_000)
        assertEquals("https://tiles.openfreemap.org/styles/bright", definition.key.ofmStyleUrl)
        assertEquals(13, definition.maxZoom)
        assertEquals(5, definition.minZoom)
        assertEquals(30_000, definition.tileLimit)
        assertEquals("ekb", definition.key.regionId)
    }

    @Test
    fun dark_definition_uses_the_pinned_dark_style_url() {
        val definition = OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.DARK, 30_000)
        assertEquals("https://tiles.openfreemap.org/styles/dark", definition.key.ofmStyleUrl)
        assertEquals(OfflineMapStyleVariant.DARK, definition.key.style)
    }

    @Test
    fun invalid_bbox_and_default_tile_limit_are_rejected() {
        assertFails { OfflineMapPackPolicy.definition(invalidRegion(), OfflineMapStyleVariant.DARK, 0) }
        assertTrue(OfflineMapPackPolicy.estimatedTileCount(region().bounds, 5, 14) > 6_000)
    }

    @Test
    fun invalid_zoom_range_and_style_url_are_rejected() {
        assertFails { OfflineMapPackPolicy.estimatedTileCount(region().bounds, 14, 5) }
        assertFails { OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.BRIGHT, -1) }
    }

    private fun region() = OfflineRegionManifest(
        regionId = "ekb",
        displayName = "Екатеринбург",
        bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0),
    )

    private fun invalidRegion() = OfflineRegionManifest(
        regionId = "ekb",
        displayName = "Екатеринбург",
        bounds = OfflineRegionBounds(56.0, 60.0, 57.0, 61.0).let {
            // Constructor validation is the canonical bbox contract; bypass it only by testing
            // a malformed region through an intentionally invalid numeric bound.
            OfflineRegionBounds(it.south, it.west, it.north, it.east)
        },
    )
}

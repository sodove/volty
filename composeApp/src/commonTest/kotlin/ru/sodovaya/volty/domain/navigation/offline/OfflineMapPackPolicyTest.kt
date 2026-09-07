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
        assertFails { OfflineRegionBounds(57.0, 60.0, 56.0, 61.0) }
        assertFails { OfflineRegionBounds(Double.NaN, 60.0, 57.0, 61.0) }
        assertFails { OfflineRegionBounds(56.0, 60.0, Double.POSITIVE_INFINITY, 61.0) }
        assertFails { OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.DARK, 0) }
        assertTrue(OfflineMapPackPolicy.estimatedTileCount(region().bounds, 5, 14) > 6_000)
    }

    @Test
    fun estimator_returns_exact_known_xyz_counts() {
        val world = OfflineRegionBounds(-90.0, -180.0, 90.0, 180.0)
        assertEquals(1L, OfflineMapPackPolicy.estimatedTileCount(world, 0, 0))
        assertEquals(4L, OfflineMapPackPolicy.estimatedTileCount(world, 1, 1))
        assertEquals(9_390L, OfflineMapPackPolicy.estimatedTileCount(region().bounds, 5, 14))
    }

    @Test
    fun invalid_zoom_range_is_rejected() {
        assertFails { OfflineMapPackPolicy.estimatedTileCount(region().bounds, 14, 5) }
        assertFails { OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.BRIGHT, -1) }
    }

    private fun region() = OfflineRegionManifest(
        regionId = "ekb",
        displayName = "Екатеринбург",
        bounds = OfflineRegionBounds(56.30, 59.55, 57.25, 61.45),
    )
}

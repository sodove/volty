package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapPackPolicy
import ru.sodovaya.volty.domain.navigation.offline.OfflineMapStyleVariant
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionBounds
import ru.sodovaya.volty.domain.navigation.region.OfflineRegionManifest

class MapRenderSourcePolicyTest {
    @Test
    fun bright_always_uses_the_original_ofm_style_uri() {
        assertEquals(
            "https://tiles.openfreemap.org/styles/bright",
            MapRenderSourcePolicy.styleUrl(darkTheme = false),
        )
        assertEquals(
            "https://tiles.openfreemap.org/styles/bright",
            OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.BRIGHT, 30_000).key.ofmStyleUrl,
        )
    }

    @Test
    fun dark_always_uses_the_original_ofm_style_uri() {
        assertEquals(
            "https://tiles.openfreemap.org/styles/dark",
            MapRenderSourcePolicy.styleUrl(darkTheme = true),
        )
        assertEquals(
            "https://tiles.openfreemap.org/styles/dark",
            OfflineMapPackPolicy.definition(region(), OfflineMapStyleVariant.DARK, 30_000).key.ofmStyleUrl,
        )
    }

    @Test
    fun buildings_are_appended_after_all_existing_ofm_layers_including_roads() {
        val existingLayers = listOf("background", "building", "highway-primary", "bridge-motorway", "place-city")
        val layerIds = existingLayers.toMutableList()
        val issuedLayers = mutableListOf<LegacyBuildingLayerDefinition>()

        LegacyBuildingLayerPolicy.appendIfMissing(layerIds, darkTheme = false) { layer ->
            issuedLayers += layer
            layerIds += layer.id
        }

        assertEquals(existingLayers + "volty-buildings-3d", layerIds)
        assertEquals(
            LegacyBuildingLayerDefinition(
                id = "volty-buildings-3d",
                sourceId = "openmaptiles",
                sourceLayer = "building",
                heightProperty = "render_height",
                baseProperty = "render_min_height",
                color = "#D5DCE0",
                opacity = 0.82f,
                minZoom = 13f,
            ),
            issuedLayers.single(),
        )
    }

    @Test
    fun dark_buildings_keep_the_legacy_color_and_unmodified_ofm_height_attributes() {
        val issuedLayers = mutableListOf<LegacyBuildingLayerDefinition>()

        LegacyBuildingLayerPolicy.appendIfMissing(listOf("highway-primary"), darkTheme = true) {
            issuedLayers += it
        }

        assertEquals(
            LegacyBuildingLayerDefinition(
                id = "volty-buildings-3d",
                sourceId = "openmaptiles",
                sourceLayer = "building",
                heightProperty = "render_height",
                baseProperty = "render_min_height",
                color = "#31424B",
                opacity = 0.82f,
                minZoom = 13f,
            ),
            issuedLayers.single(),
        )
    }

    @Test
    fun configuring_a_retained_style_twice_does_not_append_a_second_building_layer() {
        val layerIds = mutableListOf("highway-primary", "place-city")
        val issuedLayers = mutableListOf<LegacyBuildingLayerDefinition>()

        repeat(2) {
            LegacyBuildingLayerPolicy.appendIfMissing(layerIds, darkTheme = false) { layer ->
                issuedLayers += layer
                layerIds += layer.id
            }
        }

        assertEquals(listOf("highway-primary", "place-city", "volty-buildings-3d"), layerIds)
        assertEquals(1, issuedLayers.size)
    }

    private fun region() = OfflineRegionManifest(
        regionId = "ekb",
        displayName = "Екатеринбург",
        bounds = OfflineRegionBounds(56.30, 59.55, 57.25, 61.45),
    )
}

package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OfflineMapStylePolicyTest {
    @Test
    fun `all map sources keep 3D buildings above roads`() {
        assertEquals(
            OfflineMapStylePolicy.BuildingLayerPolicy(
                layerAboveRoads = true,
                usesDefensiveDataFallbacks = false,
            ),
            OfflineMapStylePolicy.buildingLayerPolicy(usesOfflineSource = false),
        )
        assertEquals(
            OfflineMapStylePolicy.BuildingLayerPolicy(
                layerAboveRoads = true,
                usesDefensiveDataFallbacks = true,
            ),
            OfflineMapStylePolicy.buildingLayerPolicy(usesOfflineSource = true),
        )
    }

    @Test
    fun `OpenFreeMap marks footprints that must stay out of the 3D layer`() {
        assertEquals("hide_3d", OfflineMapStylePolicy.building3dExclusionProperty)
    }

    @Test
    fun `building extrusion has a sane visual ceiling`() {
        assertEquals(60.0, OfflineMapStylePolicy.maxBuildingExtrusionHeightMeters)
    }

    @Test
    fun `local OpenFreeMap snapshot has no remote or sprite dependencies`() {
        val localized = OfflineMapStylePolicy.localizeOpenFreeMapStyle(
            styleJson = """
                {
                  "version": 8,
                  "sources": {
                    "ne2_shaded": {"type": "raster", "tiles": ["https://tiles.openfreemap.org/natural-earth/{z}/{x}/{y}.png"]},
                    "openmaptiles": {"type": "vector", "url": "https://tiles.openfreemap.org/planet"}
                  },
                  "sprite": "https://tiles.openfreemap.org/sprites/ofm",
                  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
                  "layers": [
                    {"id": "background", "type": "background"},
                    {"id": "water", "type": "fill", "source": "openmaptiles", "source-layer": "water"},
                    {"id": "shade", "type": "raster", "source": "ne2_shaded"},
                    {"id": "town", "type": "symbol", "source": "openmaptiles", "layout": {"icon-image": "town", "text-field": ["get", "name"]}},
                    {"id": "wood", "type": "fill", "source": "openmaptiles", "paint": {"fill-pattern": "wood-pattern"}}
                  ]
                }
            """.trimIndent(),
            tileUrl = "http://127.0.0.1:39375/tiles/{z}/{x}/{y}.pbf",
            glyphsUrl = "http://127.0.0.1:39375/glyphs/{fontstack}/{range}.pbf",
        )

        val style = Json.parseToJsonElement(localized).jsonObject
        assertNull(style["sprite"])
        assertEquals(
            "http://127.0.0.1:39375/glyphs/{fontstack}/{range}.pbf",
            style.getValue("glyphs").jsonPrimitive.content,
        )
        val source = style.getValue("sources").jsonObject.getValue("openmaptiles").jsonObject
        assertEquals("vector", source.getValue("type").jsonPrimitive.content)
        assertEquals(
            "http://127.0.0.1:39375/tiles/{z}/{x}/{y}.pbf",
            source.getValue("tiles").jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(5, source.getValue("minzoom").jsonPrimitive.content.toInt())
        assertEquals(14, source.getValue("maxzoom").jsonPrimitive.content.toInt())

        val layers = style.getValue("layers").jsonArray.map { it.jsonObject }
        assertEquals(listOf("background", "water", "town", "wood"), layers.map { it.getValue("id").jsonPrimitive.content })
        layers.forEach { layer ->
            assertFalse(layer.toString().contains("https://"))
            assertFalse(layer["layout"]?.jsonObject?.containsKey("icon-image") == true)
            assertFalse(layer["paint"]?.jsonObject?.containsKey("fill-pattern") == true)
        }
    }
}

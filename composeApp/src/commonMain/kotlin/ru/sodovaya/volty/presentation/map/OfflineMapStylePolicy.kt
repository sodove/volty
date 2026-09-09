package ru.sodovaya.volty.presentation.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shared invariants for the deliberately small offline vector-map style. */
internal object OfflineMapStylePolicy {
    data class BuildingLayerPolicy(
        val layerAboveRoads: Boolean,
        val usesDefensiveDataFallbacks: Boolean,
    )

    /** OpenFreeMap marks broad or uncertain footprints that must remain flat. */
    const val building3dExclusionProperty: String = "hide_3d"

    const val maxBuildingExtrusionHeightMeters: Double = 60.0

    /** Offline archives may omit height data, but never get a different visual layer hierarchy. */
    fun buildingLayerPolicy(usesOfflineSource: Boolean): BuildingLayerPolicy = BuildingLayerPolicy(
        layerAboveRoads = true,
        usesDefensiveDataFallbacks = usesOfflineSource,
    )

    /**
     * Rebinds an upstream OpenFreeMap style snapshot to Volty's loopback PMTiles server.
     *
     * The bundled regional archive deliberately has no raster tiles or sprite atlas.  Keeping
     * those URLs in a style makes MapLibre silently reach the network while offline; removing
     * only the sprite-dependent decoration preserves the upstream map hierarchy and labels.
     */
    fun localizeOpenFreeMapStyle(
        styleJson: String,
        tileUrl: String,
        glyphsUrl: String,
    ): String {
        val style = Json.parseToJsonElement(styleJson).jsonObject
        val localLayers = style.getValue("layers").jsonArray.mapNotNull { element ->
            element.jsonObject
                .takeUnless { it["source"]?.jsonPrimitive?.content == "ne2_shaded" }
                ?.withoutSpriteReferences()
        }
        val localStyle = JsonObject(
            style
                .filterKeys { it !in setOf("sources", "sprite", "glyphs", "layers") }
                .toMutableMap()
                .apply {
                    put(
                        "sources",
                        JsonObject(
                            mapOf(
                                "openmaptiles" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("vector"),
                                        "tiles" to JsonArray(listOf(JsonPrimitive(tileUrl))),
                                        "minzoom" to JsonPrimitive(5),
                                        "maxzoom" to JsonPrimitive(14),
                                    ),
                                ),
                            ),
                        ),
                    )
                    put("glyphs", JsonPrimitive(glyphsUrl))
                    put("layers", JsonArray(localLayers))
                },
        )
        return Json.encodeToString(JsonObject.serializer(), localStyle)
    }

    private fun JsonObject.withoutSpriteReferences(): JsonObject = JsonObject(
        mapValues { (key, value) ->
            if (key in setOf("layout", "paint") && value is JsonObject) {
                JsonObject(value.filterKeys { it !in setOf("icon-image", "fill-pattern") })
            } else {
                value
            }
        },
    )
}

package ru.sodovaya.volty.presentation.map

import com.materialkolor.hct.Hct
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.model.ThemeAccent
import ru.sodovaya.volty.presentation.common.seededTonalColors
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the real bundled OpenFreeMap styles through the themer. Paint-only is the offline-pack
 * contract (sources, tile URLs, sprite, glyphs and layout must stay byte-identical), and the road
 * and building tone relations are the "preserve OpenFreeMap's hierarchy" contract.
 */
class MapThemeBundledStyleTest {
    private fun style(dark: Boolean): String {
        val name = "openfreemap-${if (dark) "dark" else "bright"}.json"
        val file = listOf("src/androidMain/assets/offline-map-styles/$name", "composeApp/src/androidMain/assets/offline-map-styles/$name")
            .map(::File).first { it.exists() }
        return file.readText()
    }

    private fun palettes(dark: Boolean) = ThemeAccent.entries.map { mapThemePalette(seededTonalColors(it.seedArgb), dark) }

    private fun isColorString(text: String) = text.startsWith("#") || text.startsWith("rgb") || text.startsWith("hsl")

    /** Same shape, same numbers, same non-colour strings; only colour strings may differ. */
    private fun sameSkeleton(a: JsonElement, b: JsonElement, where: String) {
        when {
            a is JsonObject && b is JsonObject -> {
                assertEquals(a.keys, b.keys, where)
                a.forEach { (k, v) -> sameSkeleton(v, b.getValue(k), "$where.$k") }
            }
            a is JsonArray && b is JsonArray -> {
                assertEquals(a.size, b.size, where)
                a.indices.forEach { sameSkeleton(a[it], b[it], "$where[$it]") }
            }
            a is JsonPrimitive && a.isString && isColorString(a.content) -> assertTrue(b is JsonPrimitive && b.isString && isColorString(b.content), where)
            else -> assertEquals(a, b, where)
        }
    }

    @Test fun every_accent_themes_paint_only_in_both_styles() {
        for (dark in listOf(false, true)) {
            val original = Json.parseToJsonElement(style(dark)).jsonObject
            for (palette in palettes(dark)) {
                val themed = Json.parseToJsonElement(themeMapStyle(style(dark), palette, dark)).jsonObject
                // Everything that decides which bytes an offline pack needs is identical.
                for (key in original.keys - "layers") assertEquals(original[key], themed[key], "top-level $key")
                val before = original.getValue("layers").jsonArray
                val after = themed.getValue("layers").jsonArray
                assertEquals(before.map { it.jsonObject["id"] }, after.map { it.jsonObject["id"] })
                before.indices.forEach { i ->
                    val b = before[i].jsonObject
                    val a = after[i].jsonObject
                    assertEquals(b.filterKeys { it != "paint" }, a.filterKeys { it != "paint" }, "layer ${b["id"]} non-paint fields")
                    sameSkeleton(b["paint"] ?: JsonObject(emptyMap()), a["paint"] ?: JsonObject(emptyMap()), "paint of ${b["id"]}")
                }
            }
        }
    }

    private fun tones(styleJson: String): Map<String, Double> =
        Json.parseToJsonElement(styleJson).jsonObject.getValue("layers").jsonArray.mapNotNull { element ->
            val layer = element.jsonObject
            val paint = layer["paint"]?.jsonObject ?: return@mapNotNull null
            val value = paint["line-color"] ?: paint["fill-color"] ?: paint["background-color"] ?: return@mapNotNull null
            val leaf = firstLeaf(value) ?: return@mapNotNull null
            layer.getValue("id").jsonPrimitive.content to Hct.fromInt(leaf).tone
        }.toMap()

    private fun firstLeaf(value: JsonElement): Int? = when {
        value is JsonPrimitive && value.isString && value.content.startsWith("rgba(") -> {
            val p = value.content.substringAfter('(').substringBefore(')').split(',').map { it.trim().toDouble().toInt() }
            (0xFF shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2]
        }
        value is JsonArray -> value.firstNotNullOfOrNull { firstLeaf(it) }
        else -> null
    }

    @Test fun dark_theme_keeps_upstreams_road_order_for_every_accent() {
        for (palette in palettes(true)) {
            val t = tones(themeMapStyle(style(true), palette, true))
            // Casings stay lighter than the fill they wrap (reversed by the first revision).
            assertTrue(t.getValue("highway_major_casing") > t.getValue("highway_major_inner"))
            // Minor < major z6-11 fill, as upstream (#181818 < #2a2a2a); path never outshines a road.
            assertTrue(t.getValue("highway_minor") < t.getValue("highway_major_subtle"))
            assertTrue(t.getValue("highway_path") < t.getValue("highway_major_subtle"))
            assertTrue(t.getValue("highway_path") < t.getValue("highway_major_casing"))
            // Footpaths are subordinate to even the minor roads and stay close to upstream's dim L10.
            assertTrue(t.getValue("highway_path") < t.getValue("highway_minor"))
            assertTrue(t.getValue("highway_path") < 14.0)
            // Roads are visibly above the ground, for the night legibility lift.
            assertTrue(t.getValue("highway_minor") - t.getValue("background") > 8.0)
            // Upstream order of ground tones is rebuilt: building lighter than land, outline lighter than building.
            assertTrue(t.getValue("building") > t.getValue("landuse_residential"))
            assertTrue(t.getValue("building") < 12.0)
        }
    }

    @Test fun light_theme_leaves_every_road_exactly_as_openfreemap_draws_it() {
        val original = Json.parseToJsonElement(style(false)).jsonObject.getValue("layers").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
        for (palette in palettes(false)) {
            val themed = Json.parseToJsonElement(themeMapStyle(style(false), palette, false)).jsonObject.getValue("layers").jsonArray.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            for ((id, layer) in original) {
                val isRoad = layer.jsonObject["type"]?.jsonPrimitive?.content == "line" &&
                    (id.startsWith("highway-") || id.startsWith("tunnel-") || id.startsWith("bridge-")) && id != "bridge-path-casing" && !id.contains("railway")
                if (isRoad) assertEquals(layer, themed.getValue(id), id)
            }
            // The orange motorway and the yellow primary stay two different colours.
            val motorway = original.getValue("highway-motorway").jsonObject.getValue("paint").jsonObject["line-color"]
            val primary = original.getValue("highway-primary").jsonObject.getValue("paint").jsonObject["line-color"]
            assertTrue(motorway != primary)
        }
    }

    @Test fun light_buildings_sit_about_three_tone_points_off_the_ground_like_upstream() {
        for (palette in palettes(false)) {
            val t = tones(themeMapStyle(style(false), palette, false))
            val gap = t.getValue("background") - t.getValue("building")
            assertTrue(gap in 1.0..5.0, "background-building gap was $gap") // upstream 3.3; the first revision had 11
        }
    }
}

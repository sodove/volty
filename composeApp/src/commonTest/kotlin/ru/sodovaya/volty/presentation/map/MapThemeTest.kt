package ru.sodovaya.volty.presentation.map

import com.materialkolor.hct.Hct
import kotlinx.serialization.json.*
import kotlin.test.*
import ru.sodovaya.volty.presentation.common.seededTonalColors

class MapThemeTest {
    @Test fun match_predicates_remain_original_feature_values_when_outputs_are_recolored() {
        val expression = Json.parseToJsonElement("""["match",["get","color"],"#FF0000","#FF0000","#00FF00","#00FF00","#FFFFFF"]""").jsonArray
        val themed = themedMapColor(expression, 0xFF704000.toInt()).jsonArray
        assertEquals(expression[1], themed[1])
        assertEquals(JsonPrimitive("#FF0000"), themed[2])
        assertEquals(JsonPrimitive("#00FF00"), themed[4])
        assertNotEquals(expression[3], themed[3])
        assertNotEquals(expression[5], themed[5])
    }
    @Test fun map_has_its_own_near_black_and_near_white_tones() {
        val tones = seededTonalColors(0xFFC45C20.toInt())
        val dark = mapThemePalette(tones, true)
        val light = mapThemePalette(tones, false)
        assertTrue(Hct.fromInt(dark.background).tone < 8.0)
        assertTrue(Hct.fromInt(light.background).tone > 95.0)
        assertTrue(Hct.fromInt(dark.building).tone > Hct.fromInt(dark.land).tone)
        assertTrue(Hct.fromInt(dark.road).tone > Hct.fromInt(dark.building).tone)
        assertTrue(Hct.fromInt(dark.text).tone > 75.0)
        assertTrue(Hct.fromInt(light.text).tone < 30.0)
    }

    @Test fun manual_seed_changes_map_geometry_tint_not_only_route() {
        val orange = mapThemePalette(seededTonalColors(0xFFC45C20.toInt()), true)
        val blue = mapThemePalette(seededTonalColors(0xFF2165C0.toInt()), true)
        assertNotEquals(orange.background, blue.background)
        assertNotEquals(orange.building, blue.building)
        assertNotEquals(orange.accent, blue.accent)
    }

    @Test fun theme_changes_only_paint_colors_preserving_zoom_structure_and_semantics() {
        val original = Json.parseToJsonElement("""{"version":8,"sources":{"x":{"type":"vector","url":"https://example.org/tiles"}},"layers":[
          {"id":"building","type":"fill","source":"x","minzoom":13,"filter":["==","kind","building"],"paint":{"fill-color":["interpolate",["linear"],["zoom"],15.5,"#f2eae2",16,"#dfdbd7"],"fill-opacity":0.6}},
          {"id":"landuse-residential","type":"fill","paint":{"fill-color":["interpolate",["linear"],["zoom"],12,"hsla(30,19%,90%,0.4)",16,"hsla(30,19%,90%,0.2)"]}},
          {"id":"water","type":"fill","paint":{"fill-color":"#AECFE2"}},
          {"id":"volty-history-color-layer","type":"line","paint":{"line-color":"#35E6A2"}}
        ]}""").jsonObject
        val palette = mapThemePalette(seededTonalColors(0xFFC45C20.toInt()), false)
        val themed = Json.parseToJsonElement(themeMapStyle(original.toString(), palette, false)).jsonObject
        assertEquals(original["sources"], themed["sources"])
        val before = original.getValue("layers").jsonArray
        val after = themed.getValue("layers").jsonArray
        assertEquals(before.size, after.size)
        val building = after[0].jsonObject
        assertEquals(before[0].jsonObject.filterKeys { it != "paint" }, building.filterKeys { it != "paint" })
        val colors = building.getValue("paint").jsonObject.getValue("fill-color").jsonArray
        val originalColors = before[0].jsonObject.getValue("paint").jsonObject.getValue("fill-color").jsonArray
        assertEquals(originalColors.take(4), colors.take(4))
        assertEquals(originalColors[5], colors[5])
        assertNotEquals(colors[4], colors[6])
        assertNotEquals(originalColors[4], colors[4])
        assertEquals(JsonPrimitive(0.6), building.getValue("paint").jsonObject["fill-opacity"])
        val residential = after[1].jsonObject.getValue("paint").jsonObject.getValue("fill-color").jsonArray
        assertTrue(residential[4].jsonPrimitive.content.endsWith(",0.4)"))
        assertTrue(residential[6].jsonPrimitive.content.endsWith(",0.2)"))
        assertEquals(before[2], after[2]) // Water retains its geographic meaning.
        assertEquals(before[3], after[3]) // Telemetry color scale is not a theme accent.
    }

    @Test fun dark_roads_and_light_geometry_receive_colors_while_light_roads_stay_upstream() {
        val tones = seededTonalColors(0xFFC45C20.toInt())
        val dark = mapThemePalette(tones, true)
        val light = mapThemePalette(tones, false)
        assertNotNull(mapLayerThemeColors("highway_major_inner", "line", dark, true)["line-color"])
        assertNotNull(mapLayerThemeColors("landuse-residential", "fill", light, false)["fill-color"])
        // Light roads keep OpenFreeMap's own orange/yellow/white: nothing is recoloured.
        for (id in listOf("highway-primary", "highway-motorway", "highway-minor", "highway-path", "highway-motorway-casing", "tunnel-service-track", "bridge-link")) {
            assertTrue(mapLayerThemeColors(id, "line", light, false).isEmpty(), id)
        }
        assertTrue(mapLayerThemeColors("unrelated-upstream-layer", "line", light, false).isEmpty())
    }

    @Test fun road_classifier_tests_service_before_track_and_splits_motorway_major_minor() {
        assertEquals(RoadClass.MINOR, roadClassOf("tunnel-service-track")) // white upstream: not a path
        assertEquals(RoadClass.MINOR, roadClassOf("highway-minor"))
        assertEquals(RoadClass.MINOR, roadClassOf("highway_minor"))
        assertEquals(RoadClass.PATH, roadClassOf("bridge-path"))
        assertEquals(RoadClass.PATH, roadClassOf("highway_path"))
        assertEquals(RoadClass.MOTORWAY, roadClassOf("highway-motorway-link"))
        assertEquals(RoadClass.MOTORWAY, roadClassOf("highway_motorway_inner"))
        assertEquals(RoadClass.MAJOR, roadClassOf("highway-secondary-tertiary"))
        assertEquals(RoadClass.MAJOR, roadClassOf("highway-link"))
        assertEquals(RoadClass.MAJOR, roadClassOf("highway_major_subtle"))
    }

    @Test fun path_and_track_lines_never_take_the_text_colour() {
        val dark = mapThemePalette(seededTonalColors(0xFFC45C20.toInt()), true)
        for (id in listOf("highway_path", "tunnel-service-track", "bridge-path")) {
            val target = mapLayerThemeColors(id, "line", dark, true).getValue("line-color")
            assertNotEquals(dark.textMuted, target.color, id)
            assertNotEquals(dark.text, target.color, id)
        }
    }

    @Test fun background_coloured_knockouts_follow_the_background_in_both_themes() {
        for (dark in listOf(false, true)) {
            val palette = mapThemePalette(seededTonalColors(0xFF2165C0.toInt()), dark)
            for ((id, type) in listOf("road_pier" to "line", "road_area_pier" to "fill", "bridge-path-casing" to "line")) {
                val key = if (type == "line") "line-color" else "fill-color"
                assertEquals(palette.background, mapLayerThemeColors(id, type, palette, dark).getValue(key).color, "$id dark=$dark")
            }
        }
    }

    @Test fun tone_shift_is_leaf_plus_target_minus_reference_so_stop_distances_survive() {
        val expression = Json.parseToJsonElement("""["interpolate",["linear"],["zoom"],5,"#808080",6,"#404040"]""")
        val target = Hct.from(30.0, 3.0, 50.0).toInt()
        val themed = themedMapColor(expression, target, referenceTone = 40.0).jsonArray
        val first = Hct.fromInt(rgbaToArgb(themed[4].jsonPrimitive.content)).tone
        val second = Hct.fromInt(rgbaToArgb(themed[6].jsonPrimitive.content)).tone
        val originalGap = Hct.fromInt(0xFF808080.toInt()).tone - Hct.fromInt(0xFF404040.toInt()).tone
        assertEquals(originalGap, first - second, 1.5)
        // #808080 is tone ~54: it moves by target(50) - reference(40) = +10.
        assertEquals(Hct.fromInt(0xFF808080.toInt()).tone + 10.0, first, 1.5)
    }

    @Test fun a_plain_colour_class_member_keeps_its_distance_from_the_class_reference() {
        val target = Hct.from(30.0, 3.0, 20.0).toInt()
        val base = themedMapColor(JsonPrimitive("#222222"), target, referenceTone = Hct.fromInt(0xFF222222.toInt()).tone)
        val brighter = themedMapColor(JsonPrimitive("#444444"), target, referenceTone = Hct.fromInt(0xFF222222.toInt()).tone)
        assertEquals(20.0, Hct.fromInt(rgbaToArgb(base.jsonPrimitive.content)).tone, 1.5)
        assertTrue(Hct.fromInt(rgbaToArgb(brighter.jsonPrimitive.content)).tone > 25.0) // not collapsed onto 20
    }

    @Test fun without_a_reference_a_plain_colour_lands_on_the_target_tone_as_labels_always_did() {
        val target = Hct.from(30.0, 3.0, 85.0).toInt()
        val themed = themedMapColor(JsonPrimitive("#333333"), target)
        assertEquals(85.0, Hct.fromInt(rgbaToArgb(themed.jsonPrimitive.content)).tone, 1.5)
    }

    @Test fun palette_tones_follow_the_review_for_both_themes() {
        val tones = seededTonalColors(0xFFC45C20.toInt())
        fun tone(c: Int) = Hct.fromInt(c).tone
        val light = mapThemePalette(tones, false)
        assertEquals(96.0, tone(light.background), 1.0)
        assertEquals(93.0, tone(light.building), 1.0)
        assertEquals(88.0, tone(light.buildingOutline), 1.0)
        assertEquals(90.0, tone(light.building3d), 1.0)
        val dark = mapThemePalette(tones, true)
        assertEquals(4.0, tone(dark.background), 1.5)
        assertEquals(2.0, tone(dark.land), 1.5)
        assertEquals(3.0, tone(dark.building), 1.5)
        assertEquals(11.0, tone(dark.buildingOutline), 1.5)
        assertTrue(tone(dark.building) > tone(dark.land)) // building lighter than the ground it stands on
        assertTrue(tone(dark.text) > 75.0) // the intentional dark label lift is kept
    }

    @Test fun hud_cards_keep_their_own_tones_when_the_map_ground_goes_near_black() {
        val dark = mapThemePalette(seededTonalColors(0xFFC45C20.toInt()), true)
        assertTrue(Hct.fromInt(dark.hudSurface).tone > 7.0)
        assertTrue(Hct.fromInt(dark.hudSurfaceStrong).tone > Hct.fromInt(dark.hudSurface).tone)
    }

    private fun rgbaToArgb(text: String): Int {
        val parts = text.substringAfter('(').substringBefore(')').split(',').map { it.trim().toDouble().toInt() }
        return (0xFF shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
    }
}

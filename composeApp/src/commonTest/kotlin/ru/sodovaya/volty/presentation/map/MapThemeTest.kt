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
        assertTrue(Hct.fromInt(light.background).tone > 96.0)
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

    @Test fun dark_and_bright_layer_names_both_receive_geometry_colors() {
        val palette = mapThemePalette(seededTonalColors(0xFFC45C20.toInt()), true)
        assertNotNull(mapLayerThemeColors("highway_major_inner", "line", palette, true)["line-color"])
        assertNotNull(mapLayerThemeColors("highway-primary", "line", palette, false)["line-color"])
        assertTrue(mapLayerThemeColors("unrelated-upstream-layer", "line", palette, false).isEmpty())
    }
}

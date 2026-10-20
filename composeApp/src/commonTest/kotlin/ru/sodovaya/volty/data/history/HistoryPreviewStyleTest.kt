package ru.sodovaya.volty.data.history

import kotlinx.serialization.json.*
import kotlin.test.*

class HistoryPreviewStyleTest {
    private val template="""{"version":8,"sources":{"openmaptiles":{"type":"vector","url":"https://__TILEJSON_DOMAIN__/planet","attribution":"© OpenStreetMap"}},"glyphs":"https://__TILEJSON_DOMAIN__/fonts/{fontstack}/{range}.pbf","sprite":"https://__TILEJSON_DOMAIN__/sprites/ofm","layers":[{"id":"background","type":"background","paint":{"background-color":"#123456"}},{"id":"road","type":"line","source":"openmaptiles","source-layer":"transportation"},{"id":"label","type":"symbol","layout":{"text-font":["Noto Sans Regular"]}}]}"""
    @Test fun labelsAndFontSpriteResourcesAreRemovedButColorsAndAttributionSurvive() {
        val style=Json.parseToJsonElement(assertNotNull(historyPreviewStyle(template))).jsonObject
        assertNull(style["glyphs"]);assertNull(style["sprite"])
        assertEquals(listOf("background","road"),style.getValue("layers").jsonArray.map{it.jsonObject.getValue("id").jsonPrimitive.content})
        assertEquals("#123456",style.getValue("layers").jsonArray[0].jsonObject.getValue("paint").jsonObject.getValue("background-color").jsonPrimitive.content)
        val source=style.getValue("sources").jsonObject.getValue("openmaptiles").jsonObject
        assertEquals("https://tiles.openfreemap.org/planet",source.getValue("url").jsonPrimitive.content)
        assertEquals("© OpenStreetMap",source.getValue("attribution").jsonPrimitive.content)
    }
    @Test fun patternsThatStillNeedSpriteUseFallbackInsteadOfAnIncompleteMap() {
        val withPattern=template.replace("\"background-color\":\"#123456\"","\"background-pattern\":\"dots\"")
        assertNull(historyPreviewStyle(withPattern))
    }
    @Test fun patternWithAnExplicitBaseColorCanUseThatColorWithoutDownloadingSprites() {
        val patterned=template.replace("\"background-color\":\"#123456\"","\"background-color\":\"#123456\",\"background-pattern\":\"dots\"")
        val result=Json.parseToJsonElement(assertNotNull(historyPreviewStyle(patterned))).jsonObject
        assertNull(result["sprite"])
        val paint=result.getValue("layers").jsonArray.first().jsonObject.getValue("paint").jsonObject
        assertNull(paint["background-pattern"]);assertEquals("#123456",paint.getValue("background-color").jsonPrimitive.content)
    }
    @Test fun unsupportedStyleVersionAndMalformedInputAreNotTreatedAsReady() {
        assertNull(historyPreviewStyle(template.replace("\"version\":8","\"version\":9")))
        assertFails { historyPreviewStyle("not-json") }
    }
    @Test fun alternateProviderIsNeverSilentlyIntroducedByAStyleTemplate() {
        assertNull(historyPreviewStyle(template.replace("https://__TILEJSON_DOMAIN__/planet","https://other.invalid/planet")))
    }
    @Test fun rasterSourceTilesKeepTheApprovedHostAndTheirZoomMetadata() {
        val raster="""{"version":8,"sources":{"world":{"type":"raster","maxzoom":6,"tiles":["https://__TILEJSON_DOMAIN__/natural_earth/{z}/{x}/{y}.png"]}},"layers":[{"id":"world","type":"raster","source":"world"}]}"""
        val style=Json.parseToJsonElement(assertNotNull(historyPreviewStyle(raster))).jsonObject
        val source=style.getValue("sources").jsonObject.getValue("world").jsonObject
        assertEquals(6,source.getValue("maxzoom").jsonPrimitive.int)
        assertEquals("https://tiles.openfreemap.org/natural_earth/{z}/{x}/{y}.png",source.getValue("tiles").jsonArray.single().jsonPrimitive.content)
    }
}

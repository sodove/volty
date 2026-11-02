package ru.sodovaya.volty.presentation.map

import android.content.Context
import kotlinx.serialization.json.*
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillExtrusionLayer
import org.maplibre.android.style.layers.PropertyFactory.*

/** Authoring values stay immutable, so recoloring never accumulates tone/rounding drift. */
internal class MapThemeStyleApplier(context: Context) {
    private val templates = listOf(false, true).associateWith { dark ->
        val name = if (dark) "dark" else "bright"
        val json = context.assets.open("offline-map-styles/openfreemap-$name.json").bufferedReader().use { it.readText() }
        Json.parseToJsonElement(json).jsonObject.getValue("layers").jsonArray.associate { element ->
            val layer = element.jsonObject
            layer.getValue("id").jsonPrimitive.content to layer
        }
    }

    fun apply(style: Style, palette: MapThemePalette, dark: Boolean) {
        for ((id, template) in templates.getValue(dark)) {
            val layer = style.getLayer(id) ?: continue
            val paint = template["paint"]?.jsonObject ?: continue
            val targets = mapLayerThemeColors(id, template.getValue("type").jsonPrimitive.content, palette, dark)
            for ((property, target) in targets) {
                val original = paint[property] ?: continue
                val themed = themedMapColor(original, target)
                val expression = (themed as? JsonArray)?.let { Expression.raw(it.toString()) }
                val text = (themed as? JsonPrimitive)?.content.orEmpty()
                // PropertyFactory supplies PaintPropertyValue. A plain PropertyValue would
                // silently route these names to native layout properties instead of paint.
                val value = when (property) {
                    "background-color" -> if (expression != null) backgroundColor(expression) else backgroundColor(text)
                    "fill-color" -> if (expression != null) fillColor(expression) else fillColor(text)
                    "fill-outline-color" -> if (expression != null) fillOutlineColor(expression) else fillOutlineColor(text)
                    "line-color" -> if (expression != null) lineColor(expression) else lineColor(text)
                    "text-color" -> if (expression != null) textColor(expression) else textColor(text)
                    "text-halo-color" -> if (expression != null) textHaloColor(expression) else textHaloColor(text)
                    else -> continue
                }
                layer.setProperties(value)
            }
        }
        style.getLayerAs<FillExtrusionLayer>("volty-buildings-3d")?.setProperties(fillExtrusionColor(palette.building3d))
    }
}

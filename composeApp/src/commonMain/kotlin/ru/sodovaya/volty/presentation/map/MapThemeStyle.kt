package ru.sodovaya.volty.presentation.map

import com.materialkolor.hct.Hct
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** Explicit OFM layer families: never recolor arbitrary layers or data-driven telemetry. */
fun mapLayerThemeColors(id: String, type: String, p: MapThemePalette, dark: Boolean): Map<String, Int> {
    val colors = when {
        id == "background" -> mapOf("background-color" to p.background)
        id == "volty-buildings-3d" -> mapOf("fill-extrusion-color" to p.building3d)
        id == "building" || id == "building-top" -> mapOf("fill-color" to p.building, "fill-outline-color" to p.buildingOutline)
        id in setOf("landuse_residential", "landuse-residential", "landuse-suburb", "landuse-railway", "road_area_pier", "road_pier", "highway-area") ->
            mapOf((if (type == "line") "line-color" else "fill-color") to p.land)
        type == "line" && (id.startsWith("highway_") || id.startsWith("highway-") || id.startsWith("tunnel-") || id.startsWith("bridge-")) && !id.contains("railway") -> {
            val color = when {
                id.contains("casing") -> p.roadOutline
                id.contains("path") || id.contains("track") -> p.textMuted
                id.contains("minor") || id.contains("service") -> p.road
                else -> p.roadMajor
            }
            mapOf("line-color" to color)
        }
        type == "symbol" && (id.startsWith("place_") || id.startsWith("label_") || id.startsWith("highway") || id.startsWith("poi_") || id == "airport") ->
            mapOf("text-color" to (if (id.startsWith("poi_") || id.startsWith("highway")) p.textMuted else p.text), "text-halo-color" to p.halo)
        // Dark OFM uses neutral water/greenery; keep that geography as authored.
        else -> emptyMap()
    }
    return colors
}

/** Replaces only color leaves. Keeps expressions, zoom stops, alpha and every non-color field. */
fun themedMapColor(value: JsonElement, target: Int): JsonElement {
    fun outputIndices(expression: JsonArray): List<Int> = when (expression.firstOrNull()?.jsonPrimitive?.contentOrNull) {
        "interpolate", "interpolate-hcl", "interpolate-lab" -> (4 until expression.size step 2).toList()
        "step" -> (2 until expression.size step 2).toList()
        "match" -> (3 until expression.lastIndex step 2).toList() + expression.lastIndex
        "case" -> (2 until expression.lastIndex step 2).toList() + expression.lastIndex
        "coalesce" -> (1 until expression.size).toList()
        else -> emptyList() // Unsupported expressions are preserved, never guessed at.
    }
    fun firstColor(element: JsonElement): Int? = when (element) {
        is JsonPrimitive -> if (element.isString) parseMapColor(element.content) else null
        is JsonArray -> outputIndices(element).firstNotNullOfOrNull { firstColor(element[it]) }
        else -> null
    }
    val referenceTone = firstColor(value)?.let { Hct.fromInt(it).tone } ?: return value
    val targetHct = Hct.fromInt(target)
    fun replace(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> {
            val color = if (element.isString) parseMapColor(element.content) else null
            if (color == null) element else {
                val tone = (targetHct.tone + Hct.fromInt(color).tone - referenceTone).coerceIn(0.0, 100.0)
                val rgb = Hct.from(targetHct.hue, targetHct.chroma, tone).toInt()
                val alpha = when {
                    element.content.startsWith("rgba(") || element.content.startsWith("hsla(") -> element.content.substringAfterLast(',').removeSuffix(")").trim()
                    else -> "1"
                }
                JsonPrimitive("rgba(${rgb ushr 16 and 255},${rgb ushr 8 and 255},${rgb and 255},$alpha)")
            }
        }
        is JsonArray -> {
            val outputs = outputIndices(element)
            JsonArray(element.mapIndexed { index, child -> if (index in outputs) replace(child) else child })
        }
        else -> element
    }
    return replace(value)
}

fun themeMapStyle(styleJson: String, palette: MapThemePalette, dark: Boolean): String {
    val style = Json.parseToJsonElement(styleJson).jsonObject
    val layers = style.getValue("layers").jsonArray.map { element ->
        val layer = element.jsonObject
        val paint = layer["paint"]?.jsonObject ?: return@map element
        val overrides = mapLayerThemeColors(layer.getValue("id").jsonPrimitive.content, layer.getValue("type").jsonPrimitive.content, palette, dark)
        JsonObject(layer + ("paint" to JsonObject(paint.mapValues { (key, value) ->
            overrides[key]?.let { themedMapColor(value, it) } ?: value
        })))
    }
    return JsonObject(style + ("layers" to JsonArray(layers))).toString()
}

private fun parseMapColor(value: String): Int? {
    if (value.startsWith('#')) {
        val hex = value.drop(1)
        val expanded = if (hex.length == 3) hex.flatMap { listOf(it, it) }.joinToString("") else hex
        return expanded.takeIf { it.length == 6 }?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
    }
    if (!value.startsWith("rgb") && !value.startsWith("hsl")) return null
    val parts = value.substringAfter('(').substringBefore(')').split(',').map { it.trim().removeSuffix("%").toDoubleOrNull() ?: return null }
    if (parts.size !in 3..4) return null
    val rgb = if (value.startsWith("rgb")) parts.take(3) else {
        val h = ((parts[0] % 360.0) + 360.0) % 360.0 / 60.0
        val s = parts[1] / 100.0; val l = parts[2] / 100.0
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val x = c * (1 - kotlin.math.abs(h % 2 - 1)); val m = l - c / 2
        val channels = when (h.toInt()) {
            0 -> listOf(c, x, 0.0); 1 -> listOf(x, c, 0.0); 2 -> listOf(0.0, c, x)
            3 -> listOf(0.0, x, c); 4 -> listOf(x, 0.0, c); else -> listOf(c, 0.0, x)
        }
        channels.map { (it + m) * 255 }
    }
    return (0xFF000000.toInt() or (rgb[0].roundToInt().coerceIn(0, 255) shl 16)
        or (rgb[1].roundToInt().coerceIn(0, 255) shl 8) or rgb[2].roundToInt().coerceIn(0, 255))
}

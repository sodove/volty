package ru.sodovaya.volty.presentation.map

import com.materialkolor.hct.Hct
import kotlinx.serialization.json.*
import kotlin.math.roundToInt

/** What a layer's paint colour becomes: a palette colour and the upstream tone it stands in for. */
data class MapColorTarget(
    val color: Int,
    /**
     * Upstream tone of the class base this colour replaces. Null means "the first colour of the
     * layer's own value", which puts every plain-colour layer exactly on [color]'s tone (labels).
     */
    val referenceTone: Double? = null,
)

/** Road families the themes tell apart. Casings are classified separately, by id. */
internal enum class RoadClass { MOTORWAY, MAJOR, MINOR, PATH }

/**
 * Classifies an OpenFreeMap road *line* layer id (bright `highway-*`/`bridge-*`/`tunnel-*`, dark
 * `highway_*`). Order matters and is pinned by tests: motorway first (so `motorway-link` is a
 * motorway), then path, then minor/service/track, then everything else is a major road.
 *
 * Retraction: the first classifier tested `path || track` before `service`, so the white
 * `tunnel-service-track` road matched "track" and was painted with the text colour. A track here is
 * a service road upstream (#fff), hence minor.
 */
internal fun roadClassOf(id: String): RoadClass = when {
    id.contains("motorway") -> RoadClass.MOTORWAY
    id.contains("path") -> RoadClass.PATH
    id.contains("minor") || id.contains("service") || id.contains("track") -> RoadClass.MINOR
    else -> RoadClass.MAJOR
}

/**
 * Upstream tones (CIE L*, equal to HCT tone) of each class base, measured from the bundled
 * `openfreemap-bright.json` / `openfreemap-dark.json` (identical to the live styles on 2026-11-02).
 * Only the differences between layers of one class survive recolouring, so these are what a
 * palette tone is measured against.
 */
internal class UpstreamTones(
    val background: Double, val land: Double, val buildingFill: Double, val buildingOutline: Double,
    val motorway: Double, val major: Double, val minor: Double, val path: Double, val casing: Double,
)

internal val BrightUpstreamTones = UpstreamTones(
    background = 96.4, land = 91.4, buildingFill = 93.1, buildingOutline = 87.6,
    motorway = 85.1, major = 94.0, minor = 100.0, path = 76.8, casing = 74.9,
)
internal val DarkUpstreamTones = UpstreamTones(
    background = 3.3, land = 3.5, buildingFill = 2.7, buildingOutline = 9.8,
    motorway = 8.2, major = 17.1, minor = 8.2, path = 9.8, casing = 25.3,
)

private val LANDUSE_IDS = setOf("landuse_residential", "landuse-residential", "landuse-suburb", "landuse-railway")

/** Piers and the bridge-path halo are drawn in upstream's background colour: knockouts, not land. */
private val KNOCKOUT_IDS = setOf("road_area_pier", "road_pier", "bridge-path-casing")

/**
 * Explicit OFM layer families: never recolor arbitrary layers or data-driven telemetry.
 *
 * Light theme: upstream roads are left exactly as drawn; only the background, land, buildings,
 * labels and the background-coloured knockouts are retinted. Dark theme: roads are retinted per
 * class (motorway / major / minor / path, casing) by a tone shift that keeps upstream's order.
 * Retraction: the first revision painted every non-minor road one tone in both themes.
 */
fun mapLayerThemeColors(id: String, type: String, p: MapThemePalette, dark: Boolean): Map<String, MapColorTarget> {
    val up = if (dark) DarkUpstreamTones else BrightUpstreamTones
    val isRoadLine = type == "line" &&
        (id.startsWith("highway_") || id.startsWith("highway-") || id.startsWith("tunnel-") || id.startsWith("bridge-")) &&
        !id.contains("railway")
    return when {
        id == "background" -> mapOf("background-color" to MapColorTarget(p.background, up.background))
        id == "volty-buildings-3d" -> mapOf("fill-extrusion-color" to MapColorTarget(p.building3d))
        id == "building" || id == "building-top" -> mapOf(
            "fill-color" to MapColorTarget(p.building, up.buildingFill),
            "fill-outline-color" to MapColorTarget(p.buildingOutline, up.buildingOutline),
        )
        id in LANDUSE_IDS -> mapOf((if (type == "line") "line-color" else "fill-color") to MapColorTarget(p.land, up.land))
        id in KNOCKOUT_IDS -> mapOf((if (type == "line") "line-color" else "fill-color") to MapColorTarget(p.background, up.background))
        isRoadLine && dark -> {
            val target = when {
                id.contains("casing") -> MapColorTarget(p.roadOutline, up.casing)
                else -> when (roadClassOf(id)) {
                    RoadClass.MOTORWAY -> MapColorTarget(p.roadMotorway, up.motorway)
                    RoadClass.MAJOR -> MapColorTarget(p.roadMajor, up.major)
                    RoadClass.MINOR -> MapColorTarget(p.road, up.minor)
                    RoadClass.PATH -> MapColorTarget(p.path, up.path)
                }
            }
            mapOf("line-color" to target)
        }
        // Light roads, casings and `highway-area` keep upstream's colours (see KDoc).
        isRoadLine || id == "highway-area" -> emptyMap()
        type == "symbol" && (id.startsWith("place_") || id.startsWith("label_") || id.startsWith("highway") || id.startsWith("poi_") || id == "airport") ->
            mapOf(
                "text-color" to MapColorTarget(if (id.startsWith("poi_") || id.startsWith("highway")) p.textMuted else p.text),
                "text-halo-color" to MapColorTarget(p.halo),
            )
        // Dark OFM uses neutral water/greenery; keep that geography as authored.
        else -> emptyMap()
    }
}

/**
 * Replaces only color leaves. Keeps expressions, zoom stops, alpha and every non-color field.
 *
 * Every colour leaf lands at `leafTone + (target.tone - referenceTone)`: hue and chroma come from
 * [target], and the tone moves by the distance between the palette and the upstream class
 * reference, so tone *differences* between layers (and between stops of one expression) survive.
 * [referenceTone] null falls back to the tone of the value's own first colour.
 *
 * Retraction: this used `target.tone + leafTone - firstColorTone` with the first colour of *each
 * layer* as reference, so every plain-colour layer landed exactly on the palette tone and the
 * class hierarchy upstream drew was erased.
 */
fun themedMapColor(value: JsonElement, target: Int, referenceTone: Double? = null): JsonElement {
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
    val reference = referenceTone ?: firstColor(value)?.let { Hct.fromInt(it).tone } ?: return value
    val targetHct = Hct.fromInt(target)
    fun replace(element: JsonElement): JsonElement = when (element) {
        is JsonPrimitive -> {
            val color = if (element.isString) parseMapColor(element.content) else null
            if (color == null) element else {
                val tone = (Hct.fromInt(color).tone + (targetHct.tone - reference)).coerceIn(0.0, 100.0)
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
            overrides[key]?.let { themedMapColor(value, it.color, it.referenceTone) } ?: value
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

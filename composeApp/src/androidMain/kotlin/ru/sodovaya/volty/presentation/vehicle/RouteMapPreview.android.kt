package ru.sodovaya.volty.presentation.vehicle

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import ru.sodovaya.volty.data.history.historyPreviewStyle
import ru.sodovaya.volty.presentation.map.LocalVoltyMapPalette
import ru.sodovaya.volty.presentation.map.MapThemePalette
import ru.sodovaya.volty.presentation.map.themeMapStyle

@Composable
internal actual fun RouteMapPreview(points: List<Pair<Double, Double>>, darkTheme: Boolean, modifier: Modifier) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current.density
    val palette = LocalVoltyMapPalette.current
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(Color(palette.background))) {
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        if (width <= 0 || height <= 0) return@BoxWithConstraints

        // A new request must not display a bitmap produced with the previous palette.
        key(context, points, darkTheme, width, height, density, palette) {
            val snapshot by produceState<RoutePreviewSnapshot?>(null) {
                val bitmap = try {
                    withTimeoutOrNull(8_000L) {
                        routeSnapshot(context, points, darkTheme, width / density, height / density, density, palette)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    null
                }
                value = RoutePreviewSnapshot(bitmap)
            }
            snapshot?.let { result ->
                val bitmap = result.bitmap
                if (bitmap == null) {
                    RouteSketch(points, Modifier.fillMaxSize())
                } else {
                    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/** The result stays absent while the themed snapshot is still loading. */
private data class RoutePreviewSnapshot(val bitmap: ImageBitmap?)

/** The track is a layer of the snapshot's own style, so it sits on the streets it was ridden on. Null on any failure. */
private suspend fun routeSnapshot(context: Context, points: List<Pair<Double, Double>>, darkTheme: Boolean,
    widthDp: Float, heightDp: Float, pixelRatio: Float, palette: MapThemePalette): ImageBitmap? {
    val bounds = routePreviewBounds(points) ?: return null
    val theme = if (darkTheme) "dark" else "bright"
    val template = context.assets.open("offline-map-styles/openfreemap-$theme.json").bufferedReader().use { it.readText() }
    // Keep the same snapshot-safe OFM style preparation as history previews. More importantly,
    // encode route sources and layers into the style JSON itself: MapSnapshotter can throw
    // "invalid native peer" from its constructor when Style.Builder carries programmatic sources.
    val baseStyle = historyPreviewStyle(template) ?: return null
    val styleJson = routeOverlayStyle(themeMapStyle(baseStyle, palette, darkTheme), points, palette)
    val style = Style.Builder().fromJson(styleJson)
    val options = MapSnapshotter.Options(widthDp.toInt().coerceAtLeast(1), heightDp.toInt().coerceAtLeast(1))
        .withStyleBuilder(style)
        .withRegion(LatLngBounds.Builder().include(LatLng(bounds.south, bounds.west)).include(LatLng(bounds.north, bounds.east)).build())
        .withPixelRatio(pixelRatio)
        .withLogo(false)
    return awaitSnapshotOrNull<ImageBitmap> { onResult, onFailure ->
        val snapshotter = MapSnapshotter(context, options)
        snapshotter.start({ result -> try { onResult(result.bitmap.asImageBitmap()) } catch (_: Exception) { onFailure() } },
            { _ -> onFailure() })
        snapshotter::cancel
    }
}

private const val SOURCE_ID = "volty-route-preview"
private const val LAYER_ID = "volty-route-preview-line"
private const val START_SOURCE_ID = "volty-route-preview-start"
private const val START_LAYER_ID = "volty-route-preview-start-dot"
private const val END_SOURCE_ID = "volty-route-preview-end"
private const val END_LAYER_ID = "volty-route-preview-end-dot"

private fun routeOverlayStyle(styleJson: String, points: List<Pair<Double, Double>>, palette: MapThemePalette): String {
    val style = Json.parseToJsonElement(styleJson).jsonObject
    val sources = style.getValue("sources").jsonObject + mapOf(
        SOURCE_ID to geoJsonSource(points.map { (lat, lon) -> lon to lat }, "LineString"),
        START_SOURCE_ID to geoJsonSource(listOf(points.first().second to points.first().first), "Point"),
        END_SOURCE_ID to geoJsonSource(listOf(points.last().second to points.last().first), "Point"),
    )
    val layers = style.getValue("layers").jsonArray + listOf(
        buildJsonObject {
            put("id", LAYER_ID); put("type", "line"); put("source", SOURCE_ID)
            put("layout", buildJsonObject { put("line-cap", "round"); put("line-join", "round") })
            put("paint", buildJsonObject { put("line-color", cssColor(palette.accent)); put("line-width", 4) })
        },
        endpointLayer(START_LAYER_ID, START_SOURCE_ID, palette.accentSecondary, palette.halo, 5),
        endpointLayer(END_LAYER_ID, END_SOURCE_ID, palette.accent, palette.halo, 6),
    )
    return JsonObject(style + ("sources" to JsonObject(sources)) + ("layers" to JsonArray(layers))).toString()
}

private fun geoJsonSource(coordinates: List<Pair<Double, Double>>, geometryType: String): JsonObject = buildJsonObject {
    put("type", "geojson")
    put("data", buildJsonObject {
        put("type", "Feature")
        put("properties", JsonObject(emptyMap()))
        put("geometry", buildJsonObject {
            put("type", geometryType)
            if (geometryType == "Point") {
                val (lon, lat) = coordinates.single()
                put("coordinates", buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) })
            } else {
                put("coordinates", buildJsonArray {
                    coordinates.forEach { (lon, lat) -> add(buildJsonArray { add(JsonPrimitive(lon)); add(JsonPrimitive(lat)) }) }
                })
            }
        })
    })
}

private fun endpointLayer(id: String, source: String, fill: Int, stroke: Int, radius: Int) = buildJsonObject {
    put("id", id); put("type", "circle"); put("source", source)
    put("paint", buildJsonObject {
        put("circle-radius", radius)
        put("circle-color", cssColor(fill))
        put("circle-stroke-color", cssColor(stroke))
        put("circle-stroke-width", 2)
    })
}

private fun cssColor(argb: Int) = "#%06X".format(argb and 0x00FFFFFF)

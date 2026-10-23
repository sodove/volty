package ru.sodovaya.volty.presentation.vehicle

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import ru.sodovaya.volty.presentation.map.MapRenderSourcePolicy
import kotlin.coroutines.resume

@Composable
internal actual fun RouteMapPreview(points: List<Pair<Double, Double>>, darkTheme: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp))) {
        val width = constraints.maxWidth; val height = constraints.maxHeight
        val snapshot by produceState<ImageBitmap?>(null, points, darkTheme, width, height) {
            value = if (width <= 0 || height <= 0) null
            else routeSnapshot(context.applicationContext, points, darkTheme, width / density, height / density, density)
        }
        snapshot?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: RouteSketch(points, Modifier.fillMaxSize())
    }
}

/** The track is a layer of the snapshot's own style, so it sits on the streets it was ridden on. Null on any failure. */
private suspend fun routeSnapshot(context: Context, points: List<Pair<Double, Double>>, darkTheme: Boolean,
    widthDp: Float, heightDp: Float, pixelRatio: Float): ImageBitmap? {
    val bounds = routePreviewBounds(points) ?: return null
    val line = LineString.fromLngLats(points.map { Point.fromLngLat(it.second, it.first) })
    val style = Style.Builder().fromUri(MapRenderSourcePolicy.styleUrl(darkTheme))
        .withSource(GeoJsonSource(SOURCE_ID, line))
        .withLayer(LineLayer(LAYER_ID, SOURCE_ID).withProperties(lineColor("#1E9E86"), lineWidth(4f),
            lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        // Where the ride ended: a ride that stood still has no line to see, only this.
        .withSource(GeoJsonSource(END_SOURCE_ID, Point.fromLngLat(points.last().second, points.last().first)))
        .withLayer(CircleLayer(END_LAYER_ID, END_SOURCE_ID).withProperties(circleRadius(6f), circleColor("#1E9E86"),
            circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f)))
    val options = MapSnapshotter.Options(widthDp.toInt().coerceAtLeast(1), heightDp.toInt().coerceAtLeast(1))
        .withStyleBuilder(style)
        .withRegion(LatLngBounds.Builder().include(LatLng(bounds.south, bounds.west)).include(LatLng(bounds.north, bounds.east)).build())
        .withPixelRatio(pixelRatio)
        .withLogo(false)
    return suspendCancellableCoroutine { continuation ->
        val snapshotter = MapSnapshotter(context, options)
        continuation.invokeOnCancellation { snapshotter.cancel() }
        snapshotter.start({ result -> if (continuation.isActive) continuation.resume(result.bitmap.asImageBitmap()) },
            { _ -> if (continuation.isActive) continuation.resume(null) })
    }
}

private const val SOURCE_ID = "volty-route-preview"
private const val LAYER_ID = "volty-route-preview-line"
private const val END_SOURCE_ID = "volty-route-preview-end"
private const val END_LAYER_ID = "volty-route-preview-end-dot"

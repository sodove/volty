package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlin.math.PI
import kotlin.math.cos

/** A ride's track drawn over a still map; the plain [RouteSketch] stands in until (or unless) the map renders. */
@Composable
internal expect fun RouteMapPreview(points: List<Pair<Double, Double>>, darkTheme: Boolean, modifier: Modifier = Modifier)

internal data class RouteBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * The area a route preview shows: the track with a margin, never smaller than [minSpanMeters]
 * on a side. A rider standing still records a few metres of GPS jitter, and fitting the map to
 * that zooms into a single roof.
 */
internal fun routePreviewBounds(points: List<Pair<Double, Double>>, minSpanMeters: Double = 400.0, margin: Double = 0.15): RouteBounds? {
    if (points.isEmpty()) return null
    val south = points.minOf { it.first }; val north = points.maxOf { it.first }
    val west = points.minOf { it.second }; val east = points.maxOf { it.second }
    val midLat = (south + north) / 2
    val minLat = minSpanMeters / METERS_PER_DEGREE
    val minLon = minSpanMeters / (METERS_PER_DEGREE * cos(midLat * PI / 180).coerceAtLeast(0.01))
    val halfLat = maxOf((north - south) * (1 + 2 * margin), minLat) / 2
    val halfLon = maxOf((east - west) * (1 + 2 * margin), minLon) / 2
    val midLon = (west + east) / 2
    return RouteBounds((midLat - halfLat).coerceAtLeast(-85.0), midLon - halfLon, (midLat + halfLat).coerceAtMost(85.0), midLon + halfLon)
}

private const val METERS_PER_DEGREE = 111_320.0

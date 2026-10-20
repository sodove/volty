package ru.sodovaya.volty.presentation.history

import kotlin.math.*

data class HistoryPreviewCamera(val latitude: Double, val longitude: Double, val zoom: Double,
    val north: Double, val east: Double, val south: Double, val west: Double,
    val minTileZoom: Double, val maxTileZoom: Double)

/** Explicit snapshot camera plus resource coverage; unsupported wraps use Canvas. */
fun historyPreviewCamera(key: HistoryPreviewImageKey): HistoryPreviewCamera? {
    val fixes = key.segments.flatMap { it.fixes }
    if (fixes.isEmpty() || key.width <= 32 || key.height <= 32 || fixes.any { abs(it.lat) > 85.05112878 }) return null
    val west = fixes.minOf { it.lon }; val east = fixes.maxOf { it.lon }
    if (east-west > 180) return null // Canvas already handles wrapped routes.
    fun y(lat: Double) = (1.0-ln(tan(PI/4+lat*PI/360))/PI)/2
    fun latitude(y: Double) = atan(sinh(PI*(1-2*y))) * 180/PI
    val minY = fixes.minOf { y(it.lat) }; val maxY = fixes.maxOf { y(it.lat) }
    val centerY = (minY+maxY)/2
    val centerLat = latitude(centerY); val centerLon = (west+east)/2
    val contextSpan = 200.0/(40_075_016.686*cos(centerLat*PI/180))
    val dx = max((east-west)/360,contextSpan); val dy = max(maxY-minY,contextSpan)
    val zoom = log2(min((key.width-32)/(512*dx),(key.height-32)/(512*dy))).coerceIn(0.0,18.0)
    val scale = 512*2.0.pow(zoom)
    val neighbor = 1/2.0.pow(floor(zoom))
    val halfX = key.width/scale/2+neighbor; val halfY = key.height/scale/2+neighbor
    val resourceWest = centerLon-halfX*360; val resourceEast = centerLon+halfX*360
    if (resourceWest < -180 || resourceEast > 180) return null
    return HistoryPreviewCamera(centerLat,centerLon,zoom,latitude((centerY-halfY).coerceIn(0.0,1.0)),resourceEast,
        latitude((centerY+halfY).coerceIn(0.0,1.0)),resourceWest,floor(zoom),ceil(zoom))
}

package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistoryRide
import ru.sodovaya.volty.domain.history.HistoryRideStatus
import ru.sodovaya.volty.domain.history.HistoryRange
import ru.sodovaya.volty.domain.history.historyAxisEpochMillis
import kotlin.math.*

data class HistoryPreviewPoint(val x: Double, val y: Double)

fun historyPreviewPaths(segments: List<HistoryMapSegment>, width: Double, height: Double,
    padding: Double = 8.0): List<List<HistoryPreviewPoint>> {
    require(width.isFinite() && height.isFinite() && padding.isFinite() && width >= 0 && height >= 0 && padding >= 0)
    val availableWidth = width - 2 * padding; val availableHeight = height - 2 * padding
    if (availableWidth <= 0 || availableHeight <= 0) return emptyList()
    val fixes = segments.flatMap { it.fixes }
    if (fixes.isEmpty()) return emptyList()
    fun longitude(lon: Double) = if (lon < 0) lon + 360 else lon
    val longitudes = fixes.map { longitude(it.lon) }.distinct().sorted()
    var largestGap = -1.0; var start = longitudes.first()
    longitudes.forEachIndexed { index, lon ->
        val next = if (index == longitudes.lastIndex) longitudes.first() + 360 else longitudes[index + 1]
        if (next - lon > largestGap) { largestGap = next - lon; start = next % 360 }
    }
    // Standard Web Mercator display limit. Recorded polar coordinates are unchanged.
    val projected = segments.filter { it.fixes.isNotEmpty() }.map { segment -> segment.fixes.map { fix ->
        val x = ((longitude(fix.lon) - start + 360) % 360) * PI / 180
        val lat = fix.lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
        HistoryPreviewPoint(x, -ln(tan(PI / 4 + lat / 2)))
    } }
    val points = projected.flatten()
    val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
    val minY = points.minOf { it.y }; val maxY = points.maxOf { it.y }
    val dx = maxX - minX; val dy = maxY - minY
    if (dx == 0.0 && dy == 0.0) return projected.map { path -> path.map { HistoryPreviewPoint(width / 2, height / 2) } }
    val scale = min(if (dx > 0) availableWidth / dx else Double.POSITIVE_INFINITY,
        if (dy > 0) availableHeight / dy else Double.POSITIVE_INFINITY)
    if (!scale.isFinite()) return emptyList()
    val left = (width - dx * scale) / 2; val top = (height - dy * scale) / 2
    return projected.map { path -> path.map { point -> HistoryPreviewPoint(
        (left + (point.x - minX) * scale).coerceIn(padding, width - padding),
        (top + (point.y - minY) * scale).coerceIn(padding, height - padding)) } }
}

/** No clock read: only a recorded closed interval can earn a duration. */
fun historyRideDurationMillis(ride: HistoryRide): Long? {
    if (ride.status == HistoryRideStatus.RECORDING) return null
    val end = ride.ended ?: return null
    if (ride.status != HistoryRideStatus.LEGACY) {
        if (historyAxisEpochMillis(ride.started, end) == null) return null
        return end.elapsedMillis - ride.started.elapsedMillis
    }
    return (end.epochMillis - ride.started.epochMillis).takeIf { it >= 0 && end.epochMillis >= ride.started.epochMillis }
}

fun historyTimeAtFraction(range: HistoryRange, fraction: Float): Long? {
    if (!fraction.isFinite()) return null
    val duration = (range.endEpochMillis - range.startEpochMillis).takeIf { it >= 0 } ?: return null
    if (fraction <= 0f) return range.startEpochMillis
    if (fraction >= 1f) return range.endEpochMillis
    val offset = (duration.toDouble() * fraction.toDouble()).toLong().coerceIn(0, duration)
    return range.startEpochMillis + offset
}

fun historyTimeFraction(range: HistoryRange, epochMillis: Long): Float? {
    val duration = (range.endEpochMillis - range.startEpochMillis).takeIf { it >= 0 } ?: return null
    if (duration == 0L) return 0f
    val elapsed = epochMillis.coerceIn(range.startEpochMillis, range.endEpochMillis) - range.startEpochMillis
    return (elapsed.toDouble() / duration.toDouble()).toFloat()
}

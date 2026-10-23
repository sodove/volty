package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.map.*

data class HistoryMapIdentity(val rideId: String, val generation: Long) {
    init { require(rideId.isNotBlank() && generation >= 0) }
}
data class HistoryMapSegment(val fixes: List<HistoryGpsFix>)
data class HistoryMapScene(
    val identity: HistoryMapIdentity,
    val segments: List<HistoryMapSegment>,
    val selectedFix: HistoryGpsFix?,
    val gapFixes: List<HistoryGpsFix>,
    val coloring: HistoryMapColoring? = null,
    val mediaFixes: Map<String, HistoryGpsFix> = emptyMap(),
) {
    val startFix: HistoryGpsFix? get() = segments.firstOrNull()?.fixes?.firstOrNull()
    val endFix: HistoryGpsFix? get() = segments.lastOrNull()?.fixes?.lastOrNull()
}

/** Recorded fixes only: no interpolation, live prediction or bridge across lost GPS.
 * The caller supplies the tolerated GPS cadence, independently of numeric sources.
 * Endpoints describe the loaded portion, not a claim that the whole ride was read.
 */
fun historyMapScene(identity: HistoryMapIdentity, origin: HistoryTick?, fixes: List<HistoryGpsFix>,
    markers: List<HistoryMarker>, cursorEpochMillis: Long?, maxFixAgeMillis: Long): HistoryMapScene =
    historyMapTrack(identity, origin, fixes, markers, maxFixAgeMillis).at(cursorEpochMillis)

/**
 * A route's geometry, built once, with the cursor lookup kept apart from it. A scrubbed cursor moves
 * every frame; rebuilding the whole route per frame (and hiding the marker until it finished) is what
 * made the marker blink or vanish under a dragging finger. [at] costs two binary searches.
 */
class HistoryMapTrack internal constructor(
    val scene: HistoryMapScene,
    private val fixes: List<HistoryGpsFix>,
    private val times: LongArray,
    private val boundaries: LongArray,
    private val maxFixAgeMillis: Long,
) {
    fun selectedFix(cursorEpochMillis: Long?): HistoryGpsFix? {
        val cursor = cursorEpochMillis ?: return null
        val candidate = lastAtOrBefore(times, cursor)
        if (candidate < 0) return null
        val time = times[candidate]
        val age = cursor - time
        if (age > maxFixAgeMillis) return null
        // A recorded boundary between the fix and the cursor: the fix no longer describes the cursor.
        if (age > 0) { val b = firstAtOrAfter(boundaries, time); if (b < boundaries.size && boundaries[b] <= cursor) return null }
        return fixes[candidate]
    }

    fun at(cursorEpochMillis: Long?): HistoryMapScene = scene.copy(selectedFix = selectedFix(cursorEpochMillis))

    private fun lastAtOrBefore(values: LongArray, key: Long): Int {
        var low = 0; var high = values.size
        while (low < high) { val mid = (low + high) ushr 1; if (values[mid] <= key) low = mid + 1 else high = mid }
        return low - 1
    }

    private fun firstAtOrAfter(values: LongArray, key: Long): Int {
        var low = 0; var high = values.size
        while (low < high) { val mid = (low + high) ushr 1; if (values[mid] < key) low = mid + 1 else high = mid }
        return low
    }
}

fun historyMapTrack(identity: HistoryMapIdentity, origin: HistoryTick?, fixes: List<HistoryGpsFix>,
    markers: List<HistoryMarker>, maxFixAgeMillis: Long): HistoryMapTrack {
    require(maxFixAgeMillis >= 0)
    val empty = HistoryMapTrack(HistoryMapScene(identity, emptyList(), null, emptyList()), emptyList(), LongArray(0), LongArray(0), maxFixAgeMillis)
    fun axis(tick: HistoryTick): Long? = if (origin == null) tick.epochMillis else historyAxisEpochMillis(origin, tick)
    val times = fixes.map { axis(it.tick) ?: return empty }
    if (times.zipWithNext().any { (a, b) -> b < a }) return empty
    val boundaries = markers.filter { it.kind in gpsBoundaries }.map { axis(it.tick) ?: return empty }.sorted()
    val segments = mutableListOf<HistoryMapSegment>()
    val gaps = mutableListOf<HistoryGpsFix>()
    var segment = mutableListOf<HistoryGpsFix>()
    var boundaryIndex = 0
    fixes.forEachIndexed { index, fix ->
        if (index > 0) {
            val previous = times[index - 1]; val current = times[index]
            while (boundaryIndex < boundaries.size && boundaries[boundaryIndex] < previous) boundaryIndex++
            val delta = current - previous
            val interrupted = delta < 0 || delta > maxFixAgeMillis ||
                (current > previous && boundaryIndex < boundaries.size && boundaries[boundaryIndex] <= current)
            if (interrupted) {
                segments += HistoryMapSegment(segment.toList()); segment = mutableListOf()
                gaps += fix
            }
        }
        segment += fix
    }
    if (segment.isNotEmpty()) segments += HistoryMapSegment(segment.toList())
    return HistoryMapTrack(HistoryMapScene(identity, segments.toList(), null, gaps.toList()), fixes.toList(),
        times.toLongArray(), boundaries.toLongArray(), maxFixAgeMillis)
}

private val gpsBoundaries = setOf(HistoryMarkerKind.GPS_GAP, HistoryMarkerKind.CLOCK_CHANGE,
    HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED, HistoryMarkerKind.MANUAL_STOP,
    HistoryMarkerKind.VEHICLE_SWITCH)

fun historyMapNavigationScene(history: HistoryMapScene, fit: MapCameraRequest.FitHistory?): NavigationMapScene =
    NavigationMapScene(ownFix = null, trail = emptyList(), participantMarkers = emptyList(),
        routes = emptyList(), destination = null, followState = RideMapFollowState(RideMapFollowMode.FREE),
        cameraRequest = fit?.takeIf { it.identity == history.identity && history.segments.isNotEmpty() }, history = history)

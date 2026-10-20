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
) {
    val startFix: HistoryGpsFix? get() = segments.firstOrNull()?.fixes?.firstOrNull()
    val endFix: HistoryGpsFix? get() = segments.lastOrNull()?.fixes?.lastOrNull()
}

/** Recorded fixes only: no interpolation, live prediction or bridge across lost GPS.
 * The caller supplies the tolerated GPS cadence, independently of numeric sources.
 * Endpoints describe the loaded portion, not a claim that the whole ride was read.
 */
fun historyMapScene(identity: HistoryMapIdentity, origin: HistoryTick?, fixes: List<HistoryGpsFix>,
    markers: List<HistoryMarker>, cursorEpochMillis: Long?, maxFixAgeMillis: Long): HistoryMapScene {
    require(maxFixAgeMillis >= 0)
    val empty = HistoryMapScene(identity, emptyList(), null, emptyList())
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
    val candidate = cursorEpochMillis?.let { cursor -> times.indexOfLast { it <= cursor } } ?: -1
    val selected = if (candidate >= 0) {
        val time = times[candidate]
        val age = checkNotNull(cursorEpochMillis) - time
        if (age < 0 || age > maxFixAgeMillis ||
            (age > 0 && boundaries.any { it in time..cursorEpochMillis })) null else fixes[candidate]
    } else null
    return HistoryMapScene(identity, segments.toList(), selected, gaps.toList())
}

private val gpsBoundaries = setOf(HistoryMarkerKind.GPS_GAP, HistoryMarkerKind.CLOCK_CHANGE,
    HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED, HistoryMarkerKind.MANUAL_STOP,
    HistoryMarkerKind.VEHICLE_SWITCH)

fun historyMapNavigationScene(history: HistoryMapScene, fit: MapCameraRequest.FitHistory?): NavigationMapScene =
    NavigationMapScene(ownFix = null, trail = emptyList(), participantMarkers = emptyList(),
        routes = emptyList(), destination = null, followState = RideMapFollowState(RideMapFollowMode.FREE),
        cameraRequest = fit?.takeIf { it.identity == history.identity && history.segments.isNotEmpty() }, history = history)

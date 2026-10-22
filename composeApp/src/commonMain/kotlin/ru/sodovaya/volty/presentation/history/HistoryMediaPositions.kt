package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

/** Recorded coordinates only; a photo timestamp does not earn an interpolated location. */
fun historyMediaPositions(favorite: HistoryFavorite?, ride: HistoryRide?, route: HistoryRouteState,
    media: HistoryMediaState, gpsMaxAgeMillis: Long): Map<String, HistoryGpsFix> {
    require(gpsMaxAgeMillis >= 0)
    if (favorite == null || ride == null || ride.favoriteSource() != favorite.source ||
        media.favoriteId != favorite.id || route.rideId != ride.id || !route.loaded || route.error != null) return emptyMap()
    val range = route.range ?: return emptyMap()
    if (route.markers.any { it.kind == HistoryMarkerKind.CLOCK_CHANGE }) return emptyMap()
    // EXIF uses wall time. A monotonic plotting axis is not evidence that the
    // camera's wall clock agrees after a clock change. Fail closed on mismatch.
    if (route.fixes.any { historyAxisEpochMillis(ride.started, it.tick) != it.tick.epochMillis }) return emptyMap()
    if (route.fixes.zipWithNext().any { (a, b) -> a.tick.epochMillis == b.tick.epochMillis && (a.lat != b.lat || a.lon != b.lon) }) return emptyMap()
    val segments = historyMapScene(HistoryMapIdentity(ride.id, 0), ride.started, route.fixes,
        route.markers, null, gpsMaxAgeMillis).segments
    return media.items.take(64).mapNotNull { item ->
        val captured = item.capturedEpochMillis ?: return@mapNotNull null
        if (item.favoriteId != favorite.id || item.status != HistoryMediaStatus.READY ||
            captured !in favorite.range.startEpochMillis..favorite.range.endEpochMillis ||
            captured !in range.startEpochMillis..range.endEpochMillis) return@mapNotNull null
        val segment = segments.singleOrNull { it.fixes.isNotEmpty() &&
            captured in it.fixes.first().tick.epochMillis..it.fixes.last().tick.epochMillis } ?: return@mapNotNull null
        val fixes = segment.fixes
        val search = fixes.binarySearchBy(captured) { it.tick.epochMillis }
        val nearest = if (search >= 0) fixes[search] else {
            val insertion = -search - 1
            listOfNotNull(fixes.getOrNull(insertion - 1), fixes.getOrNull(insertion))
                .minByOrNull { kotlin.math.abs(it.tick.epochMillis.toDouble() - captured.toDouble()) }
        } ?: return@mapNotNull null
        if (kotlin.math.abs(nearest.tick.epochMillis.toDouble() - captured.toDouble()) > 30_000.0) return@mapNotNull null
        item.id to nearest
    }.toMap()
}

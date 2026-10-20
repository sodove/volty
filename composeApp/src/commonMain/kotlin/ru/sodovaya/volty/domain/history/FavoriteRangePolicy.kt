package ru.sodovaya.volty.domain.history

fun clampFavoriteRange(selected: HistoryRange, ride: HistoryRange): HistoryRange {
    val start = maxOf(selected.startEpochMillis, ride.startEpochMillis)
    val end = minOf(selected.endEpochMillis, ride.endEpochMillis)
    require(end > start) { "A favorite must contain a non-empty recorded interval" }
    return HistoryRange(start, end)
}

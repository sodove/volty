package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

/** Most recent earned value at/before the stable chart axis, never interpolation.
 * The caller supplies source cadence; overview sparsity cannot invent evidence.
 * An exact stored sample is usable, but holding it cannot cross acquisition gaps.
 */
fun historyCursorSample(series: HistorySeries, epochMillis: Long, maxAgeMillis: Long): HistorySample? {
    require(maxAgeMillis >= 0)
    val origin = series.origin
    fun axis(tick: HistoryTick): Long? = if (origin == null) tick.epochMillis else historyAxisEpochMillis(origin, tick)
    var previousAxis: Long? = null
    var candidate: HistorySample? = null
    var candidateAxis: Long? = null
    var ambiguousUnknown = false
    for (sample in series.samples) {
        if (sample.key != series.key) return null
        val time = axis(sample.tick) ?: return null
        if (previousAxis != null && time < previousAxis) return null
        previousAxis = time
        if (time <= epochMillis) {
            ambiguousUnknown = if (candidateAxis == time) ambiguousUnknown || sample.value == null else sample.value == null
            candidate = sample; candidateAxis = time
        }
    }
    val earned = candidate ?: return null
    val time = candidateAxis ?: return null
    if (earned.value == null || ambiguousUnknown) return null
    val age = epochMillis - time
    if (age < 0 || age > maxAgeMillis) return null // Negative also rejects overflow.
    if (series.truncated && epochMillis > checkNotNull(previousAxis)) return null
    if (age == 0L) return earned
    for (marker in series.markers) {
        if (marker.kind == HistoryMarkerKind.GPS_GAP || (marker.sourceId != null && marker.sourceId != series.key.sourceId)) continue
        val boundary = axis(marker.tick) ?: return null
        if (boundary in time..epochMillis && (boundary > time || marker.kind != HistoryMarkerKind.CONNECTED)) return null
    }
    return earned
}

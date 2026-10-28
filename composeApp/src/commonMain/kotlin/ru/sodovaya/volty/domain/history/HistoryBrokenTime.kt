package ru.sodovaya.volty.domain.history

/**
 * How much of one series' span is NOT evidence: the time between two consecutive samples of the series when
 * either is unknown, the clock jumped, the interval exceeds [maxIntervalMillis], or a gap marker falls strictly
 * between them. This is the measure behind "Partial data" on a chart summary and the energy tile.
 *
 * Why time and not "any unknown anywhere" (retraction): an earlier revision marked a series partial because ONE sample
 * was unknown or because a marker existed inside the range. The rider's 14-minute ride had 5 unknown controller-power
 * samples in its first 1.6 s and a clean 14 minutes after, and every tile read "approximate". That was wrong because
 * the mark carried no size: 1.6 s of 861 s and a lost 5 minutes looked identical.
 *
 * A marker counts only when a sample of the series follows it (it must lie strictly between two samples). The normal
 * ride end is the wheel switched off: the link-loss gap marker is written at the last tick, after the last sample,
 * and says nothing about data that was recorded. Earlier revisions let that trailing marker degrade every series.
 *
 * Unknown samples at the very end, with no later sample, have no interval to measure and add nothing.
 */
internal fun historyBrokenMillis(samples: List<HistorySample>, markers: List<HistoryMarker>, maxIntervalMillis: Long = 2000): Long {
    val gapAxis = markers.filter { it.kind in HISTORY_GAP_MARKERS }.map { it.tick.elapsedMillis }.sorted()
    var broken = 0L
    for (i in 1 until samples.size) {
        val a = samples[i - 1]; val b = samples[i]
        val delta = b.tick.elapsedMillis - a.tick.elapsedMillis
        if (delta <= 0) continue
        val marker = run {
            var low = 0; var high = gapAxis.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (gapAxis[mid] <= a.tick.elapsedMillis) low = mid + 1 else high = mid
            }
            gapAxis.getOrNull(low)?.let { it < b.tick.elapsedMillis } == true
        }
        if (a.unknown != null || b.unknown != null || clockBoundary(a.tick, b.tick) || delta > maxIntervalMillis || marker) {
            broken += delta
        }
    }
    return broken
}

/** Markers that interrupt a series (same set the writer's barrier and the SQL evidence-gap query use). */
internal val HISTORY_GAP_MARKERS = setOf(HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CLOCK_CHANGE,
    HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED, HistoryMarkerKind.CONNECTED)

/**
 * Unknown-or-gap time above which a series is "partial": max([HISTORY_PARTIAL_FRACTION] of the span,
 * [HISTORY_PARTIAL_FLOOR_MILLIS]).
 *
 * False-negative cost, stated: a hole up to the threshold is NOT flagged, so a minimum, maximum or mean can miss a
 * value inside it. At 2 % that is ~17 s of a 14-minute ride, ~3.6 min of a 3-hour one; on a short ride the 10 s floor
 * applies. A genuine gap still renders as a break on the chart, and energy loses exactly the integrated time of the
 * hole (at most the threshold fraction of the energy), so the figure is a floor, not a fabrication.
 */
fun historyPartialThresholdMillis(spanMillis: Long): Long =
    maxOf((spanMillis * HISTORY_PARTIAL_FRACTION).toLong(), HISTORY_PARTIAL_FLOOR_MILLIS)

/**
 * True when [brokenMillis] of a [spanMillis] series is too much to call it exact: above [historyPartialThresholdMillis],
 * or half the span or more. The second clause keeps the 10 s floor from excusing a SHORT series that is mostly or
 * entirely unknown (a 4 s fixture of alternating unknowns is not "exact" because 4 s < 10 s).
 */
fun historyBrokenIsPartial(brokenMillis: Long, spanMillis: Long): Boolean =
    brokenMillis > historyPartialThresholdMillis(spanMillis) || brokenMillis * 2 >= spanMillis && brokenMillis > 0

const val HISTORY_PARTIAL_FRACTION = 0.02
const val HISTORY_PARTIAL_FLOOR_MILLIS = 10_000L

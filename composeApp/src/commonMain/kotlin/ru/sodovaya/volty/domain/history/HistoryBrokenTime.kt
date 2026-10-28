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
internal class HistoryBrokenAccumulator(markers: List<HistoryMarker>, private val maxIntervalMillis: Long = HISTORY_MAX_INTERVAL_MILLIS) {
    private val gapAxis = markers.filter { it.kind in HISTORY_GAP_MARKERS }.map { it.tick.elapsedMillis }.sorted()
    private val trailingAxis = markers.filter { it.kind in HISTORY_TRAILING_MARKERS }.map { it.tick.elapsedMillis }
    private var first: HistorySample? = null
    private var last: HistorySample? = null

    /** Unknown-or-gap time so far. */
    var brokenMillis = 0L; private set

    /** The part of it that is a real gap (interval, marker, clock jump), not an unknown run. */
    var gapMillis = 0L; private set

    fun offer(b: HistorySample) {
        val a = last
        last = b
        if (first == null) first = b
        if (a == null) return
        val delta = b.tick.elapsedMillis - a.tick.elapsedMillis
        if (delta <= 0) return
        var low = 0; var high = gapAxis.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (gapAxis[mid] <= a.tick.elapsedMillis) low = mid + 1 else high = mid
        }
        val marker = gapAxis.getOrNull(low)?.let { it < b.tick.elapsedMillis } == true
        val genuine = marker || clockBoundary(a.tick, b.tick) || delta > maxIntervalMillis
        if (genuine) { brokenMillis += delta; gapMillis += delta }
        else if (a.unknown != null || b.unknown != null) brokenMillis += delta
    }

    /** True once the broken time is beyond the threshold of the longest span the range could hold: the verdict is final. */
    fun surelyPartial(spanBoundMillis: Long) = brokenMillis > historyPartialThresholdMillis(spanBoundMillis)

    /**
     * Final verdict. [rideEndElapsed] is the end of the RIDE (or of the range, if earlier), not of this series. When a
     * gap marker sits at or after this series' last sample and the ride ran on for more than the interval limit, the
     * series stopped being recorded while the ride continued: that tail is broken time (review C1, a BMS link that died
     * at minute 20 of 60). A ride that ends where its series end (the wheel switched off, tail within the limit) has
     * no such tail. The caller passes the EFFECTIVE end: the ride's end only when the ride demonstrably went on without this
 * series (another source sampled later, or GPS shows movement), else the series' own last tick. Returns null when there were no samples.
     */
    /** Elapsed tick of the last sample offered, or null. */
    val lastElapsed: Long? get() = last?.tick?.elapsedMillis

    fun partial(rideEndElapsed: Long): Boolean? {
        val f = first ?: return null
        val l = checkNotNull(last)
        var broken = brokenMillis
        var spanEnd = l.tick.elapsedMillis
        val tail = rideEndElapsed - l.tick.elapsedMillis
        if (tail > maxIntervalMillis && trailingAxis.any { it >= l.tick.elapsedMillis }) {
            broken += tail; gapMillis += tail; spanEnd += tail
        }
        return historyBrokenIsPartial(broken, spanEnd - f.tick.elapsedMillis)
    }
}

/** The recorder's interval limit: samples further apart than this are a gap. */
const val HISTORY_MAX_INTERVAL_MILLIS = 2000L

/** Markers that can start a tail: the series was cut while the ride may have gone on. */
internal val HISTORY_TRAILING_MARKERS = setOf(HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CLOCK_CHANGE,
    HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED)

/** Markers that interrupt a series (same set the writer's barrier and the SQL evidence-gap query use). */
internal val HISTORY_GAP_MARKERS = setOf(HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CLOCK_CHANGE,
    HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED, HistoryMarkerKind.CONNECTED)

/**
 * Unknown-or-gap time above which a series is "partial": max([HISTORY_PARTIAL_FRACTION] of the span,
 * [HISTORY_PARTIAL_FLOOR_MILLIS]).
 *
 * False-negative cost, stated: a hole up to the threshold is NOT flagged, so a minimum, maximum or mean can miss a
 * value inside it. At 2 % that is ~17 s of a 14-minute ride, ~3.6 min of a 3-hour one; on a short ride the 10 s floor
 * applies. A genuine gap still renders as a break on the chart.
 *
 * Retraction: an earlier revision said energy loses "at most the threshold fraction of the energy". That was wrong: the
 * tolerance is a fraction of TIME, and a hole can hold far more of the Wh than that (a climb). The energy figure is a
 * floor either way; a genuine gap now makes the Energy tile approximate regardless of this tolerance
 * ([HistoryStats.hasGap]) and only short unknown runs are tolerated there.
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

/**
 * The epoch time up to which GPS shows the rider still MOVING after [afterEpochMillis], or null. Used to decide whether
 * the ride demonstrably went on after a series stopped (review re-check: a wheel switched off and a ride ended minutes
 * later by Stop must not count that standing time as a gap). A moving window is two fixes 10 to 30 s apart,
 * both at most 50 m accurate, whose displacement exceeds the sum of their accuracies at an implied speed of at least
 * 3 km/h; at least [HISTORY_GPS_MOVING_MIN_SECONDS] of it must accumulate.
 * Standing jitter stays inside the accuracy and reads as not moving.
 */
internal fun historyGpsMovingUntil(fixes: List<HistoryGpsFix>, afterEpochMillis: Long): Long? {
    val usable = fixes.filter { it.tick.epochMillis > afterEpochMillis && it.accuracyMeters <= 50.0 }
    var seconds = 0.0
    var until: Long? = null
    var i = 0
    while (i < usable.size) {
        // Non-overlapping windows of at least 10 s: at 1 Hz one step is smaller than the accuracy even at riding speed.
        val a = usable[i]
        var j = i + 1
        while (j < usable.size && usable[j].tick.epochMillis - a.tick.epochMillis < 10_000) j++
        if (j >= usable.size) break
        val b = usable[j]
        val dt = (b.tick.epochMillis - a.tick.epochMillis) / 1000.0
        if (dt <= 30.0) {
            val metres = metresBetween(a.lat, a.lon, b.lat, b.lon)
            if (metres > a.accuracyMeters + b.accuracyMeters && metres / dt * 3.6 >= 3.0) { seconds += dt; until = b.tick.epochMillis }
        }
        i = j
    }
    return until.takeIf { seconds >= HISTORY_GPS_MOVING_MIN_SECONDS }
}

internal const val HISTORY_GPS_MOVING_MIN_SECONDS = 10.0

private fun metresBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * kotlin.math.PI / 180; val dLon = (lon2 - lon1) * kotlin.math.PI / 180
    val h = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(lat1 * kotlin.math.PI / 180) * kotlin.math.cos(lat2 * kotlin.math.PI / 180) * kotlin.math.sin(dLon / 2).let { it * it }
    return 2 * 6_371_000.0 * kotlin.math.asin(kotlin.math.sqrt(h))
}

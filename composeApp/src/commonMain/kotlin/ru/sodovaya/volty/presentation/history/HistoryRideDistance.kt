package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.plausibility.CounterDecision
import ru.sodovaya.volty.domain.plausibility.CounterStepGate

/** A ride's length and whether it is a measurement ([approximate] = false) or a reconstruction. */
data class HistoryRideDistance(val km: Double, val approximate: Boolean)

/**
 * How far a ride went, from the stored 500 ms detail samples, GPS first and the wheel's own counter filling what GPS
 * did not cover. Read time, so it repairs every recorded ride, the rider's included, and needs no migration.
 *
 * Why this exists: a Begode ET Max once read one odometer value 65.5 km too high for a single sample (0x2AFFF0 -> 0x2BFFF0, bit
 * 16 set), and `max - min` of the stored odometer showed 67.2 km over a 950 s ride, an average of 254 km/h. A counter
 * is a sequence of increments, not a pair of extremes.
 *
 *  - [counter]: the motion tier's `trip_distance_km`, else `odometer_km`, oldest first (kilometres). Each run is walked
 *    through [CounterStepGate] with the monotone predicate, so a needle adds nothing and a persistent reset is
 *    re-baselined after three agreeing samples. An unknown sample, a sample on another clock generation and any
 *    non-`GPS_GAP` entry of [markers] between two samples end a run: the gate is `finish()`ed and a new one starts,
 *    adding 0 across the break. (A `GPS_GAP` says nothing about the wheel's counter.)
 *  - GPS-first: the intervals between consecutive fixes at most 30 s apart with accuracy at most 50 m (the rules of
 *    [historyGpsDistanceKm]), on the ride axis, are *covered*. An accepted wheel increment `[t_prev, t]` is added as fill
 *    only when its midpoint lies outside every covered interval. Distance = GPS length + fill.
 *  - Cross-check (kind-1 witness: the ride's own speed series): a distance above `peakKmh x duration x 1.05` is
 *    impossible. The counter is then not used: the GPS length alone when there is any GPS, otherwise null (no distance
 *    tile, therefore no average and no Wh/km). Never a clamped number.
 *  - [HistoryRideDistance.approximate]: any fill used, any step rejected or re-baselined, or the cross-check fired.
 *
 * Known limit, inherited from the gate: two consecutive identical torn reads (a needle repeated once) agree with each
 * other, so the gate accepts them and the step adds its ~65 km. Only the cross-check above catches that; when GPS is
 * partial it then shows the GPS length alone and discards the good fill. Three repeats re-baseline instead.
 *
 * False-positive risk: a ride with GPS everywhere shows the GPS length, which reads slightly long at walking pace.
 * The cross-check needs a genuine peak: a speed needle in [peakKmh] only loosens it (Task 10 removes those). A
 * persistent reset costs one increment (the jump itself), so a ride ends up at most a step short. A false positive of
 * the gate would show as a counter that stops growing on a healthy wheel and the missing metres would be filled by GPS.
 */
fun historyRideDistance(
    counter: List<HistorySample>,
    gps: List<HistoryGpsFix>,
    origin: HistoryTick,
    peakKmh: Double?,
    durationMillis: Long,
    markers: List<HistoryMarker> = emptyList(),
): HistoryRideDistance? = historyRideWalk(counter, gps, origin, peakKmh, durationMillis, markers).distance

/**
 * What one counter series gave: the [distance] (null: nothing earned), the gate's [acceptedM] sum over all runs (how
 * much real progress the series carries, for choosing between trip and odometer) and whether the [crossChecked]
 * witness fired (the counter was judged unearned: GPS alone or nothing, and no other source may overrule that).
 */
internal class HistoryRideWalk(val distance: HistoryRideDistance?, val acceptedM: Double, val crossChecked: Boolean)

internal fun historyRideWalk(
    counter: List<HistorySample>,
    gps: List<HistoryGpsFix>,
    origin: HistoryTick,
    peakKmh: Double?,
    durationMillis: Long,
    markers: List<HistoryMarker> = emptyList(),
): HistoryRideWalk {
    var acceptedMetres = 0.0
    val covered = coveredIntervals(gps, origin)
    val gpsKm = historyGpsDistanceKm(gps)
    // Final review M5: only this counter's own markers end a run (a BMS link gap or a media marker of another source says
    // nothing about it); a marker with no source is global and still counts. An earlier revision took every source's.
    val counterSource = counter.firstOrNull()?.key?.sourceId
    val breaks = markers.filter { it.kind != HistoryMarkerKind.GPS_GAP && (it.sourceId == null || it.sourceId == counterSource) }
        .mapNotNull { historyAxisEpochMillis(origin, it.tick) }.sorted()

    var fillMetres = 0.0
    var approximate = false
    fun fill(from: Long, to: Long, metres: Double) {
        if (metres <= 0.0) return
        if (!covered.contains((from + to) / 2)) { fillMetres += metres; approximate = true }
    }

    var run = CounterRun()
    fun settle(decisions: List<CounterDecision<CounterPoint>>) {
        for (decision in decisions) when (decision) {
            is CounterDecision.Accepted -> {
                run.previous?.let { fill(it.axis, decision.point.axis, decision.incrementM) }
                run.previous = decision.point
            }
            is CounterDecision.Rejected -> approximate = true
            is CounterDecision.Rebased -> {
                approximate = true
                // The chain is the last `confirmations` offers, contiguous by construction of the gate.
                val first = run.points.getOrNull(decision.point.index - (RUN_CONFIRMATIONS - 1)) ?: decision.point
                fill(first.axis, decision.point.axis, decision.creditedM)
                run.previous = decision.point
            }
        }
    }
    fun endRun() { settle(run.gate.finish()); acceptedMetres += run.gate.acceptedSumM; run = CounterRun() }

    var lastTick: HistoryTick? = null
    var lastAxis: Long? = null
    for (sample in counter) {
        val value = sample.value
        val axis = if (value == null) null else historyAxisEpochMillis(origin, sample.tick)
        if (value == null || axis == null) { endRun(); lastTick = null; lastAxis = null; continue }
        val before = lastTick
        val beforeAxis = lastAxis
        if (before != null && beforeAxis != null &&
            (clockBoundary(before, sample.tick) || breaks.any { it > beforeAxis && it <= axis })) endRun()
        val point = CounterPoint(run.points.size, axis, value * 1000.0)
        run.points += point
        settle(run.gate.offer(point))
        lastTick = sample.tick; lastAxis = axis
    }
    endRun()

    val total = gpsKm + fillMetres / 1000.0
    val limit = peakKmh?.let { it * (durationMillis / 3_600_000.0) * CROSS_CHECK_TOLERANCE }
    if (limit != null && durationMillis > 0 && total > limit) {
        return HistoryRideWalk(if (gpsKm > 0.0) HistoryRideDistance(gpsKm, approximate = true) else null, acceptedMetres, true)
    }
    return HistoryRideWalk(total.takeIf { it > 0.0 }?.let { HistoryRideDistance(it, approximate) }, acceptedMetres, false)
}

/**
 * The same cross-check for the aggregate path: the distance a trip counter's (else odometer's) `max - min` stats give,
 * used when the detail cannot be paged (a legacy ride, a fake, a page budget that ran out). A counter span above the
 * ride's own peak speed x duration x 1.05 is discarded; GPS alone is shown then, else nothing.
 * Without a counter span the GPS length is the distance. A stat that is not [HistoryStats.complete] is approximate.
 */
fun historyStatsDistance(
    sources: List<HistorySource>,
    stats: Map<HistorySeriesKey, HistoryStats>,
    durationMillis: Long?,
    gpsKm: Double?,
    robustPeaks: HistoryRobustPeaks = HistoryRobustPeaks.NONE,
): HistoryRideDistance? {
    val motion = tier(sources, motionTiers)
    fun span(metric: String) = motion.mapNotNull { source ->
        val s = stats[HistorySeriesKey(source.id, metric)] ?: return@mapNotNull null
        val low = s.minimum; val high = s.maximum
        if (low == null || high == null) null else (high - low) to !s.complete
    }.maxByOrNull { it.first }?.takeIf { it.first > 0 }
    val counted = span("trip_distance_km") ?: span("odometer_km")
    val gps = gpsKm?.takeIf { it > 0 }
    if (counted == null) return gps?.let { HistoryRideDistance(it, false) }
    val peak = historyPeakSpeedKmh(sources, stats, robustPeaks)
    if (peak != null && durationMillis != null && durationMillis > 0 &&
        counted.first > peak * (durationMillis / 3_600_000.0) * CROSS_CHECK_TOLERANCE) {
        return gps?.let { HistoryRideDistance(it, true) }
    }
    return HistoryRideDistance(counted.first, counted.second)
}

/**
 * The ride's top speed (the witness for the distance cross-check), or null: per motion source the needle-free detail peak
 * when [robustPeaks] has one, else the stats maximum. A single corrupt speed sample must not raise the ceiling the counter
 * is judged against. An earlier revision read the raw stats maximum only; that was wrong because one 1180 km/h needle
 * made any counter jump look affordable.
 */
internal fun historyPeakSpeedKmh(sources: List<HistorySource>, stats: Map<HistorySeriesKey, HistoryStats>,
    robustPeaks: HistoryRobustPeaks = HistoryRobustPeaks.NONE): Double? =
    tier(sources, motionTiers).mapNotNull {
        val key = HistorySeriesKey(it.id, "speed_kmh")
        robustPeaks.peaks[key]?.value ?: stats[key]?.maximum
    }.maxOrNull()

/** Slack over the top speed: the stored peak is a 500 ms window maximum and the clock of a window is coarse. */
private const val CROSS_CHECK_TOLERANCE = 1.05
private const val RUN_CONFIRMATIONS = 3
internal const val GPS_COVER_GAP_MILLIS = 30_000L
internal const val GPS_COVER_MAX_ACCURACY_M = 50.0

private data class CounterPoint(val index: Int, val axis: Long, val metres: Double)

private class CounterRun {
    val points = ArrayList<CounterPoint>()
    var previous: CounterPoint? = null
    val gate = CounterStepGate<CounterPoint>(value = { it.metres }, agrees = { a, b -> b.metres >= a.metres },
        confirmations = RUN_CONFIRMATIONS)
}

/** Sorted, disjoint-by-construction spans of the ride axis a GPS fix pair vouches for. */
private class Covered(private val starts: LongArray, private val ends: LongArray) {
    fun contains(axis: Long): Boolean {
        var low = 0; var high = starts.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            when {
                axis < starts[mid] -> high = mid - 1
                axis > ends[mid] -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}

private fun coveredIntervals(gps: List<HistoryGpsFix>, origin: HistoryTick): Covered {
    val starts = ArrayList<Long>(); val ends = ArrayList<Long>()
    var last: Pair<Long, HistoryGpsFix>? = null
    for (fix in gps) {
        val axis = historyAxisEpochMillis(origin, fix.tick) ?: continue
        val prior = last
        if (prior != null) {
            val dt = axis - prior.first
            if (dt > 0 && dt <= GPS_COVER_GAP_MILLIS && fix.accuracyMeters <= GPS_COVER_MAX_ACCURACY_M &&
                prior.second.accuracyMeters <= GPS_COVER_MAX_ACCURACY_M) {
                // Fixes are time-ordered, so spans only touch at their ends.
                starts += prior.first; ends += axis
            }
        }
        last = axis to fix
    }
    return Covered(starts.toLongArray(), ends.toLongArray())
}

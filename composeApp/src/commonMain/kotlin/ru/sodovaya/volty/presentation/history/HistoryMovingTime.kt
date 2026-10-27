package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.*

/** Time spent moving; [approximate] when some of the ride could not be judged (unknown speed, a gap, a clock break). */
data class HistoryMovingTime(val millis: Long, val approximate: Boolean)

/**
 * The rider's definition of moving: a speed above this. This is a definition, not a plausibility bound: it decides what
 * "average speed" means (the rider's ruling, 2026-10-27) and says nothing about which speeds are believable.
 */
internal const val MOVING_SPEED_KMH = 3.0

/**
 * The longest gap between two speed samples that still counts as continuous data: five times the slowest 1 Hz source.
 * This is a data-continuity rule, not a physical one: across a longer silence we do not know what the wheel did.
 */
internal const val MOVING_MAX_GAP_MILLIS = 5_000L

/**
 * Moving time of one speed series, oldest first. The interval between two consecutive samples counts as moving when the
 * earlier one is above [MOVING_SPEED_KMH] and both are known and at most [MOVING_MAX_GAP_MILLIS] apart on one clock
 * generation. Anything else (an unknown end, a longer gap, a clock break) credits nothing and marks the result
 * [HistoryMovingTime.approximate]: unknown time is never counted as moving, and never silently as standing still.
 *
 * GPS over a wheel gap (final review I2): [historyRideDistance] is GPS first, so it counts riding the wheel link did not
 * see (the BLE link dropped for ten minutes while the phone kept fixing). Dividing that distance by wheel-only moving
 * time gave 45 km/h on a 30 km/h ride. An earlier revision credited nothing across a gap over [MOVING_MAX_GAP_MILLIS];
 * that was wrong because the numerator and the denominator then described different parts of the ride. Now, when [gps]
 * and the ride [origin] are given, the part of such a gap that lies inside a *covered* GPS interval (the same rule as
 * the distance's fill: consecutive fixes at most 30 s apart, accuracy at most 50 m) counts as moving when that
 * segment's own speed is above [MOVING_SPEED_KMH]. The result is marked approximate: it is reconstructed.
 *
 * False-positive risk: GPS standstill jitter above 3 km/h over one fix interval (a poor fix at a light) credits standing
 * time as moving; the average then reads a little low, never above the top speed (the final guard in
 * [historyRideTotals] handles the reverse). A ride recorded at a cadence slower than 5 s credits no moving time at all
 * from the wheel (and says so as approximate); a stored speed that is not zero at standstill (sensor offset above
 * 3 km/h) would count parked time. Neither has been seen. Either would show as a "В движении" figure close to the whole
 * duration.
 */
fun historyMovingMillis(speed: List<HistorySample>, gps: List<HistoryGpsFix> = emptyList(), origin: HistoryTick? = null): HistoryMovingTime {
    var moving = 0L
    var approximate = false
    val segments = if (origin == null) emptyList() else gpsMovingSegments(gps, origin)
    var first = 0
    speed.zipWithNext { a, b ->
        val from = a.value
        val to = b.value
        if (a.tick.clockGeneration == b.tick.clockGeneration) {
            val gap = b.tick.elapsedMillis - a.tick.elapsedMillis
            if (gap > MOVING_MAX_GAP_MILLIS) {
                approximate = true
                val start = if (origin == null) null else historyAxisEpochMillis(origin, a.tick)
                val end = if (origin == null) null else historyAxisEpochMillis(origin, b.tick)
                if (start != null && end != null) {
                    while (first < segments.size && segments[first].end <= start) first++
                    var i = first
                    while (i < segments.size && segments[i].start < end) {
                        val s = segments[i++]
                        if (s.kmh > MOVING_SPEED_KMH) moving += minOf(end, s.end) - maxOf(start, s.start)
                    }
                }
                return@zipWithNext
            }
        }
        if (from == null || to == null || a.tick.clockGeneration != b.tick.clockGeneration) { approximate = true; return@zipWithNext }
        val dt = b.tick.elapsedMillis - a.tick.elapsedMillis
        if (dt < 0) { approximate = true; return@zipWithNext }
        if (from > MOVING_SPEED_KMH) moving += dt
    }
    return HistoryMovingTime(moving, approximate)
}

/** One covered GPS interval on the ride axis and the speed the two fixes imply. */
internal class GpsMovingSegment(val start: Long, val end: Long, val kmh: Double)

/** The covered intervals of [gps] (the rule of the distance fill) with their segment speed, oldest first. */
internal fun gpsMovingSegments(gps: List<HistoryGpsFix>, origin: HistoryTick): List<GpsMovingSegment> {
    val result = ArrayList<GpsMovingSegment>()
    var last: Pair<Long, HistoryGpsFix>? = null
    for (fix in gps) {
        val axis = historyAxisEpochMillis(origin, fix.tick) ?: continue
        val prior = last
        if (prior != null) {
            val dt = axis - prior.first
            if (dt > 0 && dt <= GPS_COVER_GAP_MILLIS && fix.accuracyMeters <= GPS_COVER_MAX_ACCURACY_M &&
                prior.second.accuracyMeters <= GPS_COVER_MAX_ACCURACY_M) {
                val metres = haversineMeters(prior.second.lat, prior.second.lon, fix.lat, fix.lon)
                result += GpsMovingSegment(prior.first, axis, metres / (dt / 1000.0) * 3.6)
            }
        }
        last = axis to fix
    }
    return result
}

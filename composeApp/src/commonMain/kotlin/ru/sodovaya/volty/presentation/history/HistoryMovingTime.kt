package ru.sodovaya.volty.presentation.history

import ru.sodovaya.volty.domain.history.HistorySample

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
 * False-positive risk: a ride recorded at a cadence slower than 5 s credits no moving time at all (and says so as
 * approximate); a stored speed that is not zero at standstill (sensor offset above 3 km/h) would count parked time.
 * Neither has been seen. Either would show as a "В движении" figure close to the whole duration.
 */
fun historyMovingMillis(speed: List<HistorySample>): HistoryMovingTime {
    var moving = 0L
    var approximate = false
    speed.zipWithNext { a, b ->
        val from = a.value
        val to = b.value
        if (from == null || to == null || a.tick.clockGeneration != b.tick.clockGeneration) { approximate = true; return@zipWithNext }
        val dt = b.tick.elapsedMillis - a.tick.elapsedMillis
        if (dt < 0 || dt > MOVING_MAX_GAP_MILLIS) { approximate = true; return@zipWithNext }
        if (from > MOVING_SPEED_KMH) moving += dt
    }
    return HistoryMovingTime(moving, approximate)
}

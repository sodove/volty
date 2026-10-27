package ru.sodovaya.volty.presentation.history

import kotlin.math.abs
import ru.sodovaya.volty.domain.history.HistorySample

/** Charge used over a ride, signed: positive = discharged, negative = charged. [approximate] says why not to trust the digit. */
data class HistoryBatteryUsed(val percent: Double, val approximate: Boolean)

/** The window at each end of the ride that the start and end charge are read over. */
internal const val BATTERY_WINDOW_MILLIS = 60_000L

/** A current sample this close (either side) to a charge sample is what that charge is judged against. */
internal const val BATTERY_CURRENT_PAIR_MILLIS = 1_000L

/**
 * |I| below this is "not under load" for SELECTING which samples of a window to read; it is not a plausibility bound
 * and nothing is rejected for exceeding it (the rider's ruling: no fixed physical thresholds). The dumps put a standing
 * pack at -0.2...-0.9 A and a riding one at -4...-21 A, so 3 A separates them with margin both ways; a pack that idles
 * above 3 A (an accessory, a heater) simply never has a calm sample, which falls back to the whole window.
 */
internal const val BATTERY_CALM_AMPS = 3.0

/**
 * Charge used = robust start minus robust end of one battery's recorded charge, signed.
 *
 * The tile was `max - min` of the recorded charge with a hard-coded minus: «-39 %» on a pack that ended at 79 %, because
 * the minimum was a load-sag dip of the voltage-estimated charge, and a charging session read as a discharge. A retracted
 * earlier revision; that was wrong because a ride's extremes are what a sag and a spike are made of, not what the ride
 * did to the pack.
 *
 * **robustStart** is the median of the known charge samples in the [BATTERY_WINDOW_MILLIS] after the first known sample;
 * **robustEnd** the median over the last [BATTERY_WINDOW_MILLIS]. In each window, calm samples (the nearest [current]
 * sample within [BATTERY_CURRENT_PAIR_MILLIS] has |I| < [BATTERY_CALM_AMPS]) are preferred, because a loaded pack's
 * terminal voltage, hence any voltage-derived charge, is depressed. A window with no calm sample falls back to all its
 * samples. Samples of another clock generation than the anchor are not mixed in.
 *
 * **[approximate]** unless both windows used calm samples of the battery's OWN current ([ownCurrent]). A pack without its
 * own current is voltage-estimated or tracker-recorded: every Begode, every `!reportsStateOfCharge` BMS, and a pack whose
 * only current is the controller's. The source model carries no "estimated" flag, so this is the only honest proxy and it
 * is conservative: a BMS reporting its own charge but no current is marked approximate too. Also approximate when the two
 * windows share samples (a ride under two windows' length) or when no current series exists.
 *
 * Null when no charge sample is known. False-positive risk: a pack charging during the first minute then riding reads its
 * start from the charging level; it would show as a small "+" tile. Not seen.
 */
fun historyBatteryUsed(soc: List<HistorySample>, current: List<HistorySample>?, ownCurrent: Boolean): HistoryBatteryUsed? {
    val known = soc.filter { it.value != null }
    val first = known.firstOrNull() ?: return null
    val last = known.last()
    val currents = current.orEmpty().filter { it.value != null }.groupBy { it.tick.clockGeneration }
        .mapValues { (_, list) -> list.sortedBy { it.tick.elapsedMillis } }
    val anyCurrent = currents.isNotEmpty()
    val calm: (HistorySample) -> Boolean = { s ->
        val list = currents[s.tick.clockGeneration].orEmpty()
        // The nearest current sample by binary search: the window reads a few hundred charge samples out of a long ride.
        var lo = 0; var hi = list.size
        while (lo < hi) { val m = (lo + hi) / 2; if (list[m].tick.elapsedMillis < s.tick.elapsedMillis) lo = m + 1 else hi = m }
        listOfNotNull(list.getOrNull(lo - 1), list.getOrNull(lo))
            .filter { abs(it.tick.elapsedMillis - s.tick.elapsedMillis) <= BATTERY_CURRENT_PAIR_MILLIS }
            .minByOrNull { abs(it.tick.elapsedMillis - s.tick.elapsedMillis) }
            ?.let { abs(it.value!!) < BATTERY_CALM_AMPS } ?: false
    }
    fun window(anchor: HistorySample, from: Long, to: Long): Pair<Double, Boolean> {
        val inside = known.filter { it.tick.clockGeneration == anchor.tick.clockGeneration && it.tick.elapsedMillis in from..to }
        val calmOnes = if (!anyCurrent) emptyList() else inside.filter(calm)
        val used = calmOnes.ifEmpty { inside }
        return median(used.map { it.value!! }) to calmOnes.isNotEmpty()
    }
    val (start, startCalm) = window(first, first.tick.elapsedMillis, first.tick.elapsedMillis + BATTERY_WINDOW_MILLIS)
    val (end, endCalm) = window(last, last.tick.elapsedMillis - BATTERY_WINDOW_MILLIS, last.tick.elapsedMillis)
    val overlap = first.tick.clockGeneration != last.tick.clockGeneration ||
        last.tick.elapsedMillis - first.tick.elapsedMillis < 2 * BATTERY_WINDOW_MILLIS
    return HistoryBatteryUsed(start - end, !(ownCurrent && startCalm && endCalm) || overlap)
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Several batteries' usage averaged, as the tile always did; approximate if any is. */
internal fun historyBatteryUsedTotal(parts: List<HistoryBatteryUsed>): HistoryBatteryUsed? =
    if (parts.isEmpty()) null else HistoryBatteryUsed(parts.map { it.percent }.average(), parts.any { it.approximate })

/** "-" for a discharge, "+" for a charge. The sign of the ROUNDED digit decides: 0 prints no sign (the tile is hidden below 0.5 anyway). */
internal fun historyBatteryUsedSign(value: Double): String = when {
    value >= 0.5 -> "−"
    value <= -0.5 -> "+"
    else -> ""
}

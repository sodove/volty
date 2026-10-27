package ru.sodovaya.volty.domain.plausibility

import kotlin.math.abs
import ru.sodovaya.volty.domain.history.HistorySample
import ru.sodovaya.volty.domain.history.clockBoundary

/**
 * Kind-2 plausibility rule (temporal confirmation, relative to the signal's own neighbours): a peak tile must not be a
 * single-sample needle. The rider's ruling (2026-10-27) forbids absolute magnitudes (a scooter draws 600 A, a pack may be
 * another chemistry, an SV wheel shows 105 % PWM), so nothing here knows what a "reasonable" value is.
 *
 * An interior KNOWN sample `x_k` whose two neighbours `a = x_{k-1}`, `b = x_{k+1}` are known and each within
 * [NEEDLE_NEIGHBOUR_MILLIS] on the same clock is a **needle** iff all hold:
 *  1. `x_k` is above both or below both neighbours;
 *  2. `excursion = min(|x_k - a|, |x_k - b|)` and `|b - a| <= 0.25 * excursion` (the series returns to its prior level);
 *  3. `excursion > 6 * median|delta|` over the neighbouring steps (up to 7 on each side, the two steps touching `x_k`
 *     excluded; steps across an unknown or a gap do not count). With no step to measure by the median is 0.
 *
 * Measured (scratch script `plaus2\needles.py`, three real BLE dumps, 3 098 samples across speed, phase current, board
 * temperature, battery current and voltage): 11 samples flagged, every one a standstill quantisation blip (at most
 * 1.4 km/h, 0.1 degC or 0.67 A), and **no series' maximum or minimum changed**.
 *
 * False-positive risk: a genuine spike shorter than one sample interval (a bump's current spike, a 5 kW pull lasting
 * one frame) is excluded from the TILE. How it shows: the tile reads a little below the chart's tallest point and carries
 * the approximate mark. The chart and the live display stay raw. A two-sample plateau (`45, 130, 130, 45`) is
 * corroborated by its neighbour and is never a needle. A needle beside a gap, an unknown or a clock break is not
 * excluded, because the missing neighbour cannot vouch for the level it should return to.
 */
const val NEEDLE_NEIGHBOUR_MILLIS = 2000L
private const val NEEDLE_RETURN_RATIO = 0.25
private const val NEEDLE_NOISE_MULTIPLE = 6.0
private const val NEEDLE_NOISE_STEPS = 8

/** [value] is the largest non-needle value; [needlesExcluded] is how many samples were left out to get it. */
class RobustPeak(val value: Double, val needlesExcluded: Int) {
    val approximate: Boolean get() = needlesExcluded > 0
}

/** Indices (into [samples], in order) of the needles. Samples must be one series, ordered by time. */
fun historyNeedleIndices(samples: List<HistorySample>): Set<Int> {
    val n = samples.size
    if (n < 3) return emptySet()
    fun linked(i: Int): Boolean {
        val a = samples[i]; val b = samples[i + 1]
        return a.value != null && b.value != null && !clockBoundary(a.tick, b.tick) &&
            b.tick.elapsedMillis - a.tick.elapsedMillis <= NEEDLE_NEIGHBOUR_MILLIS
    }
    fun step(i: Int) = abs(samples[i + 1].value!! - samples[i].value!!)
    val found = LinkedHashSet<Int>()
    for (k in 1 until n - 1) {
        val x = samples[k].value ?: continue
        if (!linked(k - 1) || !linked(k)) continue
        val a = samples[k - 1].value!!; val b = samples[k + 1].value!!
        if (!((x > a && x > b) || (x < a && x < b))) continue
        val excursion = minOf(abs(x - a), abs(x - b))
        if (excursion <= 0.0 || abs(b - a) > NEEDLE_RETURN_RATIO * excursion) continue
        val steps = ArrayList<Double>()
        for (i in maxOf(0, k - NEEDLE_NOISE_STEPS) until minOf(n - 1, k + NEEDLE_NOISE_STEPS)) {
            if (i == k - 1 || i == k || !linked(i)) continue
            steps += step(i)
        }
        steps.sort()
        val median = when {
            steps.isEmpty() -> 0.0
            steps.size % 2 == 1 -> steps[steps.size / 2]
            else -> (steps[steps.size / 2 - 1] + steps[steps.size / 2]) / 2
        }
        if (excursion > NEEDLE_NOISE_MULTIPLE * median) found += k
    }
    return found
}

/** The largest known value that is not a needle, or null when no sample is known. Reusable for any metric. */
fun historyRobustPeak(samples: List<HistorySample>): RobustPeak? {
    val needles = historyNeedleIndices(samples)
    var best: Double? = null
    samples.forEachIndexed { i, s ->
        val v = s.value ?: return@forEachIndexed
        if (i !in needles && (best == null || v > best!!)) best = v
    }
    return best?.let { RobustPeak(it, needles.size) }
}

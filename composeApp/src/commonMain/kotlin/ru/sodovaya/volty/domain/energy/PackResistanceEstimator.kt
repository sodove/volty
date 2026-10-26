package ru.sodovaya.volty.domain.energy

/**
 * Online estimate of a pack's internal resistance from the voltage drop across fast current steps.
 *
 * Each sample is compared with a REFERENCE: the newest earlier sample that is at least
 * [MIN_PAIR_MS] and at most [MAX_STEP_WINDOW_MS] old. When the discharge current moved by at least
 * [MIN_STEP_A] since then, the candidate is `R = -dV / dI` (discharge-positive, so a voltage drop on
 * rising current gives a positive R). Candidates outside [[MIN_OHM], [MAX_OHM]] are rejected. [ohm]
 * is the median of the last [WINDOW] accepted candidates, so a single wild step cannot move it.
 * Null until something is accepted (or seeded).
 *
 * Retraction: an earlier revision compared each sample with the one immediately before it. On a
 * live vehicle voltage (pack samples) and battery current (motion samples) arrive as SEPARATE
 * publishes, so neighbouring samples never carry both halves of a step - one moves the current with
 * the old voltage (R near 0, rejected), the next moves the voltage with the old current (dI = 0,
 * ignored) - and no candidate was ever accepted (field report 2026-10-26: no resistance after 6 km).
 * Reaching back [MIN_PAIR_MS] lets both streams refresh before a pair is judged.
 */
class PackResistanceEstimator(initialOhm: Float? = null) {

    private class Sample(val at: Long, val v: Float, val a: Float)

    private val candidates = ArrayDeque<Float>()
    private val history = ArrayDeque<Sample>()

    init {
        if (initialOhm != null) candidates.addLast(initialOhm)
    }

    val ohm: Float?
        get() {
            if (candidates.isEmpty()) return null
            val sorted = candidates.sorted()
            val n = sorted.size
            return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
        }

    fun feed(packV: Float, dischargeA: Float, atMillis: Long) {
        if (history.isNotEmpty() && atMillis <= history.last().at) return // time stood still or went back
        while (history.isNotEmpty() && atMillis - history.first().at > MAX_STEP_WINDOW_MS) history.removeFirst()
        val ref = history.lastOrNull { atMillis - it.at >= MIN_PAIR_MS }
        if (ref != null) {
            val dI = dischargeA - ref.a
            if (kotlin.math.abs(dI) >= MIN_STEP_A) {
                val r = -(packV - ref.v) / dI
                if (r in MIN_OHM..MAX_OHM) {
                    candidates.addLast(r)
                    while (candidates.size > WINDOW) candidates.removeFirst()
                }
            }
        }
        history.addLast(Sample(atMillis, packV, dischargeA))
    }

    companion object {
        const val MIN_STEP_A = 10f
        const val MAX_STEP_WINDOW_MS = 1_000L

        /** Long enough for both the voltage and the current stream to have refreshed since the reference. */
        const val MIN_PAIR_MS = 300L
        const val MIN_OHM = 0.005f
        const val MAX_OHM = 2.0f
        const val WINDOW = 31
    }
}

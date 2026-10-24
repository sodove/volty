package ru.sodovaya.volty.domain.energy

/**
 * Online estimate of a pack's internal resistance from the voltage drop across fast current steps.
 *
 * Each sample is compared with the previous one. When the discharge current moved by at least
 * [MIN_STEP_A] within [MAX_STEP_WINDOW_MS], the candidate is `R = -dV / dI` (discharge-positive,
 * so a voltage drop on rising current gives a positive R). Candidates outside
 * [[MIN_OHM], [MAX_OHM]] are rejected. [ohm] is the median of the last [WINDOW] accepted
 * candidates, so a single wild step cannot move it. Null until something is accepted (or seeded).
 */
class PackResistanceEstimator(initialOhm: Float? = null) {

    private val candidates = ArrayDeque<Float>()
    private var lastV = 0f
    private var lastA = 0f
    private var lastAt = 0L
    private var hasLast = false

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
        if (hasLast) {
            val dI = dischargeA - lastA
            val dt = atMillis - lastAt
            if (kotlin.math.abs(dI) >= MIN_STEP_A && dt > 0 && dt <= MAX_STEP_WINDOW_MS) {
                val r = -(packV - lastV) / dI
                if (r in MIN_OHM..MAX_OHM) {
                    candidates.addLast(r)
                    while (candidates.size > WINDOW) candidates.removeFirst()
                }
            }
        }
        lastV = packV
        lastA = dischargeA
        lastAt = atMillis
        hasLast = true
    }

    companion object {
        const val MIN_STEP_A = 10f
        const val MAX_STEP_WINDOW_MS = 1_000L
        const val MIN_OHM = 0.005f
        const val MAX_OHM = 2.0f
        const val WINDOW = 31
    }
}

package ru.sodovaya.volty.domain.pwm

import kotlin.math.sqrt

/**
 * The speed, pack voltage and phase current at the last sample BEFORE the wheel's own high-power alarm bit was first
 * seen (see [OnsetLookback]). Retraction: this used to be "the instant the alarm switched on", captured at the sample
 * where the bit was first seen. That sample is one frame late (the rider's T4 dump: bit seen at 70.8 km/h, fired at
 * 65.1), so every `k` fitted from it was too small and the estimate read LOW, the dangerous direction.
 * [atMillis] is when the bit was seen.
 */
data class AlarmOnset(val speedKmh: Float, val packV: Float, val phaseA: Float?, val atMillis: Long)

/**
 * Learns [PwmCalibration] from the wheel's own alarm onsets (each satisfies
 * `threshold/100 * V = k * speed + R * I`, with speed, V and I of the sample BEFORE the bit was seen, not of the
 * sample that first showed it) or from a free-spin plateau. Pure; no I/O.
 */
object PwmCalibrationLearner {
    const val MIN_SPEED_KMH = 10f
    const val MAX_ONSETS = 20
    private const val MIN_ONSETS_FOR_R = 3
    private const val MIN_CURRENT_SPREAD_A = 10f
    private const val MAX_R = 0.5

    private fun usable(o: AlarmOnset) =
        o.speedKmh.isFinite() && o.speedKmh >= MIN_SPEED_KMH && o.packV.isFinite() && o.packV > 0f &&
            (o.phaseA == null || o.phaseA.isFinite())

    fun fromOnsets(onsets: List<AlarmOnset>, thresholdPercent: Float): PwmCalibration? {
        if (!thresholdPercent.isFinite() || thresholdPercent <= 0f) return null
        val good = onsets.filter(::usable).takeLast(MAX_ONSETS)
        if (good.isEmpty()) return null
        val target = { o: AlarmOnset -> thresholdPercent / 100.0 * o.packV }

        val withI = good.filter { it.phaseA != null }
        val currentSpread = if (withI.size < MIN_ONSETS_FOR_R) 0.0
        else (withI.maxOf { it.phaseA!! } - withI.minOf { it.phaseA!! }).toDouble()

        if (withI.size >= MIN_ONSETS_FOR_R && currentSpread >= MIN_CURRENT_SPREAD_A) {
            leastSquares(withI, target)?.let { (k, r) ->
                val ks = withI.map { (target(it) - r * it.phaseA!!) / it.speedKmh }
                return PwmCalibration(k.toFloat(), r.toFloat(), withI.size, PwmCalibrationSource.RIDING, relStdDev(ks), PwmBasis.ONSET, good)
            }
        }
        val ks = good.map { target(it) / it.speedKmh }
        return PwmCalibration(median(ks).toFloat(), 0f, good.size, PwmCalibrationSource.RIDING, relStdDev(ks), PwmBasis.ONSET, good)
    }

    /**
     * Least squares for (k, R) over the onsets that carry a phase current (onsets without one are not
     * used in this mode, so `points` counts only those). R is constrained to [0, 0.5]: outside it is
     * clamped and k refitted. Returns null when the result is unusable (k non-finite or <= 0, e.g. large
     * currents against low speeds), so the caller falls back to median k with R = 0.
     */
    private fun leastSquares(withI: List<AlarmOnset>, target: (AlarmOnset) -> Double): Pair<Double, Double>? {
        var ss = 0.0; var si = 0.0; var ii = 0.0; var sy = 0.0; var iy = 0.0
        for (o in withI) {
            val s = o.speedKmh.toDouble(); val i = o.phaseA!!.toDouble(); val y = target(o)
            ss += s * s; si += s * i; ii += i * i; sy += s * y; iy += i * y
        }
        var r = 0.0
        var k = 0.0
        var solved = false
        val det = ss * ii - si * si
        if (det > 1e-9 * ss * ii) {
            r = (ss * iy - si * sy) / det
            k = (sy - r * si) / ss
            solved = true
        }
        if (!solved || r < 0.0 || r > MAX_R) {
            r = if (!solved || r < 0.0) 0.0 else MAX_R
            k = (sy - r * si) / ss
        }
        return if (k.isFinite() && r.isFinite() && k > 0.0) k to r else null
    }

    /** Free-spin plateau: the wheel is at its limit, so 100 % there. r = 0. Null for a non-finite or non-positive plateau or voltage. */
    fun fromFreeSpin(plateauKmh: Float, packV: Float): PwmCalibration? {
        if (!plateauKmh.isFinite() || plateauKmh <= 0f || !packV.isFinite() || packV <= 0f) return null
        return PwmCalibration(packV / plateauKmh, 0f, 1, PwmCalibrationSource.WIZARD, null, PwmBasis.FREE_SPIN, emptyList())
    }

    /**
     * Folds one new onset in. Always records it in the bounded history; then:
     * - ONSET basis: refit from the whole history with [fromOnsets] (k only below 3 onsets or < 10 A of
     *   current spread, else k and R), keeping the calibration's source. Not a running mean: that is what makes R learnable.
     * - FREE_SPIN basis (manual or plateau-only wizard): never blended with onsets; kept, with onsets
     *   accumulating, until 3 usable onsets exist, then replaced by the onset fit (RIDING, ONSET).
     * An unusable onset returns [current].
     */
    fun merge(current: PwmCalibration?, onset: AlarmOnset, thresholdPercent: Float): PwmCalibration? {
        if (current == null) return fromOnsets(listOf(onset), thresholdPercent)
        if (!usable(onset)) return current
        val history = (current.onsets + onset).takeLast(MAX_ONSETS)
        return when (current.basis) {
            PwmBasis.ONSET ->
                fromOnsets(history, thresholdPercent)?.copy(source = current.source) ?: current.copy(onsets = history)
            PwmBasis.FREE_SPIN ->
                if (history.size >= MIN_ONSETS_FOR_R) fromOnsets(history, thresholdPercent) ?: current.copy(onsets = history)
                else current.copy(onsets = history)
        }
    }

    /**
     * Ruling R5: the vehicle's alarm threshold models the wheel's FIXED alarm, so when it changes every stored onset
     * is re-read against the new value (an onset is "the alarm fired here", and what that means in duty depends on
     * the threshold). An ONSET-basis calibration is refit from its whole history keeping its source; a FREE_SPIN one
     * (plateau / manual) does not depend on the threshold and is returned as it is, as is one whose refit is unusable.
     */
    fun refit(current: PwmCalibration, thresholdPercent: Float): PwmCalibration {
        if (current.basis != PwmBasis.ONSET || current.onsets.isEmpty()) return current
        return fromOnsets(current.onsets, thresholdPercent)?.copy(source = current.source) ?: current
    }

    private fun median(xs: List<Double>): Double {
        val s = xs.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }

    private fun relStdDev(xs: List<Double>): Float? {
        if (xs.isEmpty()) return null
        val mean = xs.average()
        if (mean <= 0.0) return null
        val v = xs.sumOf { (it - mean) * (it - mean) } / xs.size
        return (sqrt(v) / mean).toFloat()
    }
}

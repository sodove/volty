package ru.sodovaya.volty.domain.pwm

import kotlin.math.sqrt

/** The instant the wheel's own high-power alarm switched on. */
data class AlarmOnset(val speedKmh: Float, val packV: Float, val phaseA: Float?, val atMillis: Long)

/**
 * Learns [PwmCalibration] from the wheel's own alarm onsets (each satisfies
 * `threshold/100 * V = k * speed + R * I`) or from a free-spin plateau. Pure; no I/O.
 */
object PwmCalibrationLearner {
    const val MIN_SPEED_KMH = 10f
    private const val MIN_ONSETS_FOR_R = 3
    private const val MIN_CURRENT_SPREAD_A = 10f
    private const val MAX_R = 0.5

    private fun usable(o: AlarmOnset) =
        o.speedKmh.isFinite() && o.speedKmh >= MIN_SPEED_KMH && o.packV.isFinite() && o.packV > 0f &&
            (o.phaseA == null || o.phaseA.isFinite())

    fun fromOnsets(onsets: List<AlarmOnset>, thresholdPercent: Float): PwmCalibration? {
        if (!thresholdPercent.isFinite() || thresholdPercent <= 0f) return null
        val good = onsets.filter(::usable)
        if (good.isEmpty()) return null
        val target = { o: AlarmOnset -> thresholdPercent / 100.0 * o.packV }

        val withI = good.filter { it.phaseA != null }
        val currentSpread = if (withI.size < MIN_ONSETS_FOR_R) 0.0
        else (withI.maxOf { it.phaseA!! } - withI.minOf { it.phaseA!! }).toDouble()

        var k: Double
        var r = 0.0
        if (withI.size >= MIN_ONSETS_FOR_R && currentSpread >= MIN_CURRENT_SPREAD_A) {
            var ss = 0.0; var si = 0.0; var ii = 0.0; var sy = 0.0; var iy = 0.0
            for (o in withI) {
                val s = o.speedKmh.toDouble(); val i = o.phaseA!!.toDouble(); val y = target(o)
                ss += s * s; si += s * i; ii += i * i; sy += s * y; iy += i * y
            }
            val det = ss * ii - si * si
            var solved = false
            k = 0.0
            if (det > 1e-9 * ss * ii) {
                val rr = (ss * iy - si * sy) / det
                k = (sy - rr * si) / ss
                r = rr
                solved = true
            }
            if (!solved || r < 0.0 || r > MAX_R) {
                r = if (!solved || r < 0.0) 0.0 else MAX_R
                k = (sy - r * si) / ss
            }
            val ks = withI.map { (target(it) - r * it.phaseA!!) / it.speedKmh }
            return PwmCalibration(k.toFloat(), r.toFloat(), withI.size, PwmCalibrationSource.RIDING, relStdDev(ks))
        }
        val ks = good.map { target(it) / it.speedKmh }
        k = median(ks)
        return PwmCalibration(k.toFloat(), 0f, good.size, PwmCalibrationSource.RIDING, relStdDev(ks))
    }

    /** Free-spin plateau: the wheel is at its limit, so 100 % there. r = 0. */
    fun fromFreeSpin(plateauKmh: Float, packV: Float): PwmCalibration =
        PwmCalibration(packV / plateauKmh, 0f, 1, PwmCalibrationSource.WIZARD, null)

    /**
     * Folds one new onset into a riding calibration as a running mean of k at the current R.
     * A WIZARD or MANUAL calibration is returned unchanged (measured/typed values are authoritative;
     * an alarm onset sits near 83 % of the plateau, so blending them would contradict both).
     * An unusable onset returns [current].
     */
    fun merge(current: PwmCalibration?, onset: AlarmOnset, thresholdPercent: Float): PwmCalibration? {
        if (current == null) return fromOnsets(listOf(onset), thresholdPercent)
        if (current.source != PwmCalibrationSource.RIDING) return current
        if (!usable(onset) || !thresholdPercent.isFinite() || thresholdPercent <= 0f) return current
        val ki = (thresholdPercent / 100.0 * onset.packV - current.r * (onset.phaseA ?: 0f)) / onset.speedKmh
        val n = current.points
        val mean = current.k.toDouble()
        val newMean = (mean * n + ki) / (n + 1)
        val sd = (current.spread ?: 0f) * mean
        val meanSq = (sd * sd + mean * mean) * n
        val newVar = ((meanSq + ki * ki) / (n + 1) - newMean * newMean).coerceAtLeast(0.0)
        val spread = if (newMean > 0.0) (sqrt(newVar) / newMean).toFloat() else null
        return current.copy(k = newMean.toFloat(), points = n + 1, spread = spread)
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

package ru.sodovaya.volty.domain.energy

import ru.sodovaya.volty.domain.model.Chemistry

/**
 * Open-circuit-voltage curves: cell resting voltage against state of charge, per chemistry.
 *
 * A cell's voltage is not linear in its charge. A Li-ion cell drops fast over the top and
 * bottom tenth and sits on a long shallow plateau between; LiFePO4 is nearly flat from 10 % to
 * 90 %. The earlier linear voltage-to-percent map was right at its two endpoints only and wrong
 * in between (T4 field report 2026-10-24: a Begode's readout collapsed 60 -> 15 % under load).
 *
 * Tables are resting voltage at 0, 10, ..., 100 % of NAMEPLATE capacity, piecewise-linear
 * between points. They were sanity-checked against the commonly published NMC 18650/21700
 * discharge tables (e.g. Battery University "BU-501"/"BU-409" charge/discharge curves, typical
 * 3.0 V cut-off to 4.2 V full cell datasheets) and against LiFePO4 cell datasheets (3.65 V full,
 * ~3.20-3.35 V plateau, 2.50 V cut-off). They are approximations, not measurements of the
 * rider's packs. Lead-acid has no table: it is linear between [Chemistry.emptyCellV] and
 * [Chemistry.defaultHighV].
 *
 * Below the first point the fraction is 0, above the last it is 1.
 */
object OcvCurve {

    private val LI_ION_NMC = floatArrayOf(3.00f, 3.45f, 3.55f, 3.62f, 3.68f, 3.74f, 3.82f, 3.90f, 3.98f, 4.08f, 4.20f)
    private val LIFEPO4 = floatArrayOf(2.50f, 3.00f, 3.20f, 3.25f, 3.27f, 3.28f, 3.29f, 3.30f, 3.32f, 3.35f, 3.65f)

    private fun table(chemistry: Chemistry): FloatArray? = when (chemistry) {
        Chemistry.LI_ION_NMC -> LI_ION_NMC
        Chemistry.LIFEPO4 -> LIFEPO4
        Chemistry.LEAD_ACID -> null
    }

    /** Fraction 0..1 of nameplate capacity at resting cell voltage [cellV]. */
    fun fraction(chemistry: Chemistry, cellV: Float): Float {
        if (cellV.isNaN()) return 0f
        val t = table(chemistry)
        if (t == null) {
            val span = chemistry.defaultHighV - chemistry.emptyCellV
            if (span <= 0f) return 0f
            return ((cellV - chemistry.emptyCellV) / span).coerceIn(0f, 1f)
        }
        if (cellV <= t.first()) return 0f
        if (cellV >= t.last()) return 1f
        for (i in 1 until t.size) {
            if (cellV <= t[i]) {
                val seg = t[i] - t[i - 1]
                return ((i - 1) + (cellV - t[i - 1]) / seg) / (t.size - 1)
            }
        }
        return 1f
    }

    /** Inverse of [fraction] for a fraction in 0..1. */
    private fun voltageForFraction(chemistry: Chemistry, fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val t = table(chemistry)
            ?: return chemistry.emptyCellV + f * (chemistry.defaultHighV - chemistry.emptyCellV)
        val pos = f * (t.size - 1)
        val i = pos.toInt().coerceAtMost(t.size - 2)
        return t[i] + (pos - i) * (t[i + 1] - t[i])
    }

    /**
     * SoC 0..100 with [lowV] mapped to exactly 0 and [highV] to exactly 100, shaped by the
     * chemistry's curve between them. Degenerate bounds (or a curve with no rise between them)
     * give 0, never NaN.
     */
    fun stretchedSocPercent(chemistry: Chemistry, cellV: Float, lowV: Float, highV: Float): Float {
        if (!(highV > lowV)) return 0f
        val fLow = fraction(chemistry, lowV)
        val fHigh = fraction(chemistry, highV)
        val span = fHigh - fLow
        if (!(span > 0f)) return 0f
        return ((fraction(chemistry, cellV) - fLow) / span * 100f).coerceIn(0f, 100f)
    }

    /** Inverse of [stretchedSocPercent]: the resting cell voltage at [socPercent]. */
    fun cellVoltageAt(chemistry: Chemistry, socPercent: Float, lowV: Float, highV: Float): Float {
        if (!(highV > lowV)) return lowV
        val fLow = fraction(chemistry, lowV)
        val fHigh = fraction(chemistry, highV)
        if (!(fHigh > fLow)) return lowV
        val f = fLow + socPercent.coerceIn(0f, 100f) / 100f * (fHigh - fLow)
        return voltageForFraction(chemistry, f)
    }

    /** Share of nameplate capacity between [lowV] and [highV]; 0 for degenerate bounds. */
    fun usableFraction(chemistry: Chemistry, lowV: Float, highV: Float): Float {
        if (!(highV > lowV)) return 0f
        return (fraction(chemistry, highV) - fraction(chemistry, lowV)).coerceAtLeast(0f)
    }
}

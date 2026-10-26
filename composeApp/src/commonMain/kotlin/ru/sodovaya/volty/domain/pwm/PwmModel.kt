package ru.sodovaya.volty.domain.pwm

/**
 * Where a [PwmCalibration] came from.
 *
 * Retraction: an earlier revision said WIZARD and MANUAL are never overwritten by riding. Wrong by
 * ruling R1: a FREE_SPIN-basis calibration (WIZARD plateau-only or MANUAL) is kept only until 3 usable
 * riding onsets exist, then replaced by the onset fit (source RIDING). That replacement is intended.
 */
enum class PwmCalibrationSource { WIZARD, RIDING, MANUAL }

/** How a calibration was obtained: from alarm onsets (refit on every new onset) or a free-spin plateau / manual entry. */
enum class PwmBasis { ONSET, FREE_SPIN }

/**
 * Per-wheel constants of `pwm = (k * speedKmh + R * phaseCurrentA) / packV`.
 *
 * [k] is volts per km/h, [r] ohms. There is deliberately no default or per-model value anywhere in
 * the app: motors differ between revisions of one model, so every value was measured on this wheel
 * (free-spin wizard, its own alarm while riding) or typed by the rider. [spread] is the relative
 * standard deviation of the per-onset k, null when it was not measured.
 */
data class PwmCalibration(
    val k: Float,
    val r: Float,
    val points: Int,
    val source: PwmCalibrationSource,
    val spread: Float?,
    val basis: PwmBasis,
    /** The last [PwmCalibrationLearner.MAX_ONSETS] onsets seen; the history an ONSET fit is refit from. */
    val onsets: List<AlarmOnset>,
)

/** An estimated duty. [atCeiling] means the model reached 100 % (field weakening beyond); [percent] never exceeds 100. */
data class PwmEstimate(val percent: Float, val atCeiling: Boolean)

object PwmModel {
    /** Tolerance in percentage points so a free-spin plateau (exactly 100 by construction) is not "above". */
    private const val CEILING_EPSILON = 0.01

    /**
     * Estimated duty from the calibration, or null when an input is non-finite or [packV] <= 0.
     * A null [phaseA] counts as zero current only when [PwmCalibration.r] is 0; with r > 0 it returns null. Never extrapolates past 100: that is [PwmEstimate.atCeiling].
     * Not multiplied by WheelLog's 0.9 — the calibration already measured the wheel's own scale.
     */
    fun estimatePercent(cal: PwmCalibration, speedKmh: Float, packV: Float, phaseA: Float?): PwmEstimate? {
        if (!speedKmh.isFinite() || !packV.isFinite() || packV <= 0f) return null
        if (phaseA != null && !phaseA.isFinite()) return null
        // R2: with a learned R the current matters; a missing reading would under-read, so show nothing.
        if (phaseA == null && cal.r > 0f) return null
        if (!cal.k.isFinite() || !cal.r.isFinite()) return null
        val raw = (cal.k.toDouble() * speedKmh + cal.r.toDouble() * (phaseA ?: 0f)) / packV * 100.0
        val atCeiling = raw > 100.0 + CEILING_EPSILON
        return PwmEstimate(raw.coerceIn(0.0, 100.0).toFloat(), atCeiling)
    }
}

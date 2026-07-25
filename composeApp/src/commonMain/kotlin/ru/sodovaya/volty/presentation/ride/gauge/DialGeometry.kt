package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One dial's value range and tick density.
 *
 * [majorTicks] is the number of INTERVALS, so a scale with `majorTicks = 5`
 * carries six labelled values (both ends inclusive).
 */
data class DialScale(
    val min: Float,
    val max: Float,
    val majorTicks: Int,
    val minorPerMajor: Int = 4
)

/** One tick's angular position, and whether it is a labelled major. */
data class TickAngle(val degrees: Float, val isMajor: Boolean)

/**
 * Pure geometry for the Classic dial. Everything the Canvas needs to draw a
 * dial is computed here so it can be tested without a UI harness — the
 * hand-authored mockup's numbers collided with its centre readout precisely
 * because that arithmetic lived inline in the drawing code.
 *
 * The dial opens at [START_ANGLE] and sweeps [SWEEP] degrees clockwise, the
 * same 270° opening the Clean renderer's RadialGauge uses, so the two styles
 * read as the same instrument.
 */
object DialGeometry {

    const val START_ANGLE: Float = 135f
    const val SWEEP: Float = 270f

    // Font sizes are fractions of the dial's own radius — the same pattern as every other
    // geometric constant DialGauge computes (tickOuterR, majorTickLen, arcR, hubR). Committed
    // HERE rather than as private constants inside DialGauge.kt so DialGeometryTest can derive
    // real dial geometry (font sizes, measured-label estimates) from the EXACT numbers the
    // renderer draws with instead of a hand-copied magic number — see
    // `centre_scale_on_the_real_localized_small_corner_dial_...` below, which replaced a stale
    // pre-localization pin with a derivation from these.
    const val NumberFontFraction = 0.12f
    const val LabelFontFraction = 0.07f
    const val ValueFontFraction = 0.15f
    const val UnitFontFraction = 0.06f
    const val LabelLetterSpacingFraction = LabelFontFraction * 0.16f

    /** Position of [value] on the dial as 0..1, clamped. Degenerate scales yield 0. */
    fun fraction(value: Float, scale: DialScale): Float {
        val span = scale.max - scale.min
        if (span <= 0f) return 0f
        return ((value - scale.min) / span).coerceIn(0f, 1f)
    }

    fun angleFor(value: Float, scale: DialScale): Float =
        START_ANGLE + SWEEP * fraction(value, scale)

    /** The labelled values, both ends inclusive. */
    fun majorValues(scale: DialScale): List<Float> {
        val steps = scale.majorTicks.coerceAtLeast(1)
        val span = scale.max - scale.min
        return (0..steps).map { i -> scale.min + span * i / steps }
    }

    /** Every tick around the dial, majors flagged. */
    fun tickAngles(scale: DialScale): List<TickAngle> {
        val majors = scale.majorTicks.coerceAtLeast(1)
        val minors = scale.minorPerMajor.coerceAtLeast(1)
        val total = majors * minors
        return (0..total).map { i ->
            TickAngle(
                degrees = START_ANGLE + SWEEP * i / total,
                isMajor = i % minors == 0
            )
        }
    }

    /**
     * Start angle and sweep of the red band running from [from] to the top of
     * the scale, or null when the threshold sits outside the dial (a gauge
     * whose scale never reaches its danger level shows no band rather than a
     * misleading zero-width one).
     *
     * Boundaries: `from >= scale.max` yields null (a band starting at the very
     * end of the scale would have zero width, so it is treated as no band at
     * all rather than an invisible one). `from == scale.min` is NOT rejected —
     * it yields a band spanning the entire dial (start = [START_ANGLE], sweep
     * = [SWEEP]), i.e. the whole gauge reads as danger. That is intentional
     * for a scale that is danger from its very minimum, but callers wiring up
     * real thresholds should not pass `from == scale.min` by accident.
     */
    fun dangerSweep(from: Float, scale: DialScale): Pair<Float, Float>? {
        if (from >= scale.max || from < scale.min) return null
        val start = angleFor(from, scale)
        val end = angleFor(scale.max, scale)
        return start to (end - start)
    }

    /** Tick counts preferred for a runtime-sized scale, tried in order — 7 first since that is
     * every other Classic dial's fixed tick density, so a scale that happens to divide evenly by
     * 7 reads identically to the rest of the cluster. */
    private val PREFERRED_MAJOR_TICK_COUNTS = listOf(7, 5, 4)

    /**
     * Picks a [DialScale.majorTicks] for a `min..max` span whose max is a RUNTIME value (unlike
     * every other Classic dial, whose scale is a fixed design constant), so its major tick VALUES
     * land on whole numbers instead of drifting: `majorValues` divides the span into equal
     * fractions, and [DialGauge] renders those with 0 decimals
     * once the max reaches double digits — an uneven division then rounds each tick
     * independently, so consecutive labels stop being evenly spaced (e.g. 80 over 7 ticks reads
     * 0, 11, 23, 34, 46, 57, 69, 80 instead of steady multiples of ~11.4).
     *
     * Tries each of [candidates] in order and returns the first that divides `max - min` into a
     * whole number (within float rounding error); falls back to the first candidate — a slightly
     * uneven ring beats an exception — if none do.
     */
    fun pickMajorTicks(max: Float, min: Float = 0f, candidates: List<Int> = PREFERRED_MAJOR_TICK_COUNTS): Int {
        val span = max - min
        if (span <= 0f || candidates.isEmpty()) return candidates.firstOrNull() ?: 1
        return candidates.firstOrNull { ticks -> dividesEvenly(span, ticks) } ?: candidates.first()
    }

    private fun dividesEvenly(span: Float, ticks: Int, epsilon: Float = 0.01f): Boolean {
        val interval = span / ticks
        return abs(interval - interval.roundToInt()) < epsilon
    }

    /**
     * Rounds [max] UP to the nearest multiple of [step] — never down, so the scale never shrinks
     * below the real value it has to show. A scale max that arrives in an arbitrary DISPLAY unit
     * (e.g. a km/h-native range converted to mph for an imperial rider) needs its OWN round
     * number before [pickMajorTicks] can find a clean divisor: the km/h range is already snapped
     * to a round number upstream (`RideDashboardScreen`'s
     * `max(70f, ceil(sessionMax / 10f) * 10f)`), but that snap doesn't survive a /1.609344
     * conversion — 70 km/h becomes 43.5 mph, which has no clean small-integer divisor at all, no
     * matter which tick count [pickMajorTicks] tries.
     *
     * A value that is already a multiple of [step] snaps to itself (a no-op) — this is why
     * calling this unconditionally on an already-round metric max is safe: it never changes it,
     * so there is no need to branch on which unit system is active.
     */
    fun snapScaleMaxUp(max: Float, step: Float = 5f): Float {
        if (step <= 0f || max <= 0f) return max
        return ceil(max / step) * step
    }

    /**
     * Radius at which a tick's label sits: just inside the tick marks' inner end, offset further
     * inward by [gap] and by the widest label's own measured half-diagonal ([numberHalfDiagonal],
     * not half-height — see [DialGauge]'s draw code for why). Pulled
     * out of the Canvas draw lambda so this arithmetic — wrong by six points on its first pass,
     * per the task report — is pinned by a test instead of re-verified by hand.
     */
    fun numberRadius(tickOuterR: Float, majorTickLen: Float, gap: Float, numberHalfDiagonal: Float): Float =
        (tickOuterR - majorTickLen - gap - numberHalfDiagonal).coerceAtLeast(0f)

    /**
     * How much to shrink the centre readout block (label/value/unit stack) so its own measured
     * footprint never reaches the tick numbers. 1f means no shrink at all; the value is floored
     * at 0.5f and never scaled up. This is NOT always 1f in practice: even the smallest real
     * dial in this cluster (a corner slot, its label localized to Russian) engages this mildly
     * (~0.9, comfortably above the 0.5 floor) rather than sitting at a flat 1.0 — see
     * `DialGeometryTest`'s `centre_scale_on_the_real_localized_small_corner_dial_...` test, which
     * derives that number from the committed font fractions above instead of hand-picking one.
     * This still shrinks further (down toward the 0.5 floor) for a genuinely pathological centre
     * block, e.g. a caller-supplied value string far longer than the design was sized for — that
     * remains the safety-net end of this function's range, just not the only case it handles.
     */
    fun centreScale(numberR: Float, numberHalfDiagonal: Float, margin: Float, centreHalfDiagonal: Float): Float {
        if (centreHalfDiagonal <= 0f) return 1f
        val safeRadius = (numberR - numberHalfDiagonal - margin).coerceAtLeast(0f)
        return (safeRadius / centreHalfDiagonal).coerceIn(0.5f, 1f)
    }

    /** 0 decimals once a scale's own maximum reaches double digits (e.g. speed's "47"), 1 decimal
     * below that (e.g. power's "4.1" on an 8-max kW dial) — the same rule [DialGauge] applied
     * inline, now a pure, tested decision instead of hand-verified arithmetic in the draw lambda. */
    fun decimalsFor(scaleMax: Float): Int = if (abs(scaleMax) < 10f) 1 else 0
}

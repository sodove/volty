package ru.sodovaya.volty.presentation.ride.gauge

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
}

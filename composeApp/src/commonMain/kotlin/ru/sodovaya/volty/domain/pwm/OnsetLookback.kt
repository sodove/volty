package ru.sodovaya.volty.domain.pwm

/** One sample as the onset rule sees it. [packV] / [phaseA] are null when that sample had no reading. */
data class OnsetPoint(val atMillis: Long, val speedKmh: Float, val packV: Float?, val phaseA: Float?)

/** What [OnsetLookback.onsetFor] decided about a rising edge of the alarm bit. */
sealed interface OnsetVerdict {
    /** Take the onset from [point]: the last sample before the edge. */
    data class Take(val point: OnsetPoint) : OnsetVerdict

    /** There is no earlier sample to take it from (the bit was already set, or the link just started): no onset. */
    data object NoPrevious : OnsetVerdict

    /** The speed jumped, or the link stalled, between the previous sample and the edge: where the alarm fired is unknown. */
    data object TooFast : OnsetVerdict
}

/**
 * Which sample an alarm onset is taken from: the last one BEFORE the rising edge of the alarm bit, never the sample
 * where the bit is first seen.
 *
 * Retraction: every earlier revision (the wizard's `thresholdSample`, the rider's PwmEstimator.learn, and the
 * learner / model docs that described them) took the speed of the edge sample itself. Measured wrong. After a
 * firmware update the rider's T4 reports its TRUE PWM in frame 0x07 beside the alarm bit (frame 0x04), and the dump
 * (.superpowers/sdd/pwm-onset/t4-real-pwm-dump.txt) shows the alarm bit first seen at 70.8 km/h / 96.4 V where the
 * previous cycle was 65.1 km/h / 96.5 V: the wheel's alarm fired while accelerating, so the bit is seen one frame
 * AFTER the speed it fired at. Fitting `k` to 70.8 gave 1.089 and an estimate of 91 % on the 100 % plateau (LOW, the
 * dangerous direction); fitting to 65.1 gives 1.186 and 99 % there (a hair high, the safe direction).
 *
 * "Before" means the last sample at a DIFFERENT speed, not merely the previous sample. A Begode cycle is four frames
 * (0x00 live, 0x01, 0x04 alarm, 0x07 duty) and the decoder publishes after each, so the live frame of the edge cycle
 * already carries the NEW speed beside the OLD alarm bit; the sample just before the edge then repeats the edge
 * sample's speed and would reintroduce the very error. So [push] keeps the last sample of the previous speed. A
 * protocol that reports a new speed in every sample degenerates to the plain previous sample.
 *
 * Honesty about the margin: on the rider's dump the estimate was never below the true duty at the edge, but the
 * single-onset fit varies about +-3 % across the points of that same dump (k from true duty: 1.171 at 65.1 km/h,
 * 1.144 at 70.8, 1.193 on the plateau). "Not low" is a tendency of this rule, not a guarantee.
 *
 * Immutable: the wizard state is a data class, the estimator holds a `var`.
 */
data class OnsetLookback(val last: OnsetPoint? = null, val before: OnsetPoint? = null) {

    /** Adds [p], the newest sample (every sample, edge or not, once it has been judged or before it is). */
    fun push(p: OnsetPoint): OnsetLookback =
        if (last != null && last.speedKmh != p.speedKmh) OnsetLookback(last = p, before = last) else copy(last = p)

    /**
     * What to do with a rising edge seen on [edge] (already [push]ed). [OnsetVerdict.NoPrevious] with no earlier
     * speed; [OnsetVerdict.TooFast] when the speed moved more than [MAX_ONSET_JUMP_KMH] or the samples are more than
     * [MAX_ONSET_GAP_MS] apart; otherwise [OnsetVerdict.Take] the previous point.
     */
    fun onsetFor(edge: OnsetPoint): OnsetVerdict {
        val prev = before ?: return OnsetVerdict.NoPrevious
        if (kotlin.math.abs(edge.speedKmh - prev.speedKmh) > MAX_ONSET_JUMP_KMH) return OnsetVerdict.TooFast
        if (edge.atMillis - prev.atMillis > MAX_ONSET_GAP_MS) return OnsetVerdict.TooFast
        // Decelerating into the alarm (72.0 then the bit at 68.4) the previous sample is FASTER than where the bit
        // rose; taking it would fit k too small and read LOW. The slower of the two always errs high.
        return OnsetVerdict.Take(if (prev.speedKmh > edge.speedKmh) edge else prev)
    }

    companion object {
        /** Past this much speed between the previous sample and the edge the fire point is not pinned down (rider-approved). */
        const val MAX_ONSET_JUMP_KMH = 6f

        /** The measured cycle is ~310 ms; over a second apart means the link stalled and the "previous" sample is stale. */
        const val MAX_ONSET_GAP_MS = 1_000L
    }
}

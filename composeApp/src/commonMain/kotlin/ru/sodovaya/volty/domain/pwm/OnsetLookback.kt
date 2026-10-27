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
data class OnsetLookback(
    val last: OnsetPoint? = null,
    val before: OnsetPoint? = null,
    /** The newest [HISTORY] samples, oldest first: what a debounced edge is re-judged from (see [onsetFor]). */
    val recent: List<OnsetPoint> = emptyList(),
) {

    /** Adds [p], the newest sample (every sample, edge or not, once it has been judged or before it is). */
    fun push(p: OnsetPoint): OnsetLookback {
        val kept = (recent + p).takeLast(HISTORY)
        return if (last != null && last.speedKmh != p.speedKmh) OnsetLookback(last = p, before = last, recent = kept)
        else copy(last = p, recent = kept)
    }

    /**
     * What to do with a rising edge seen on [edge] (already [push]ed). [OnsetVerdict.NoPrevious] with no earlier
     * speed; [OnsetVerdict.TooFast] when the speed moved more than [MAX_ONSET_JUMP_KMH] or the samples are more than
     * [MAX_ONSET_GAP_MS] apart; otherwise [OnsetVerdict.Take] the previous point.
     */
    fun onsetFor(edge: OnsetPoint, firstSetAtMillis: Long? = null): OnsetVerdict {
        // A debounced producer (Begode R12) publishes the edge one 0x04 after the bit was first set. The onset must
        // not move later with it: a later, faster onset fits `k` too small and the estimate reads LOW, the dangerous
        // direction. So the edge is re-judged AS IF it were the newest sample strictly BEFORE the first raw set frame
        // (the sample whose speed that frame arrived beside: a held 0x04 publishes no sample of its own, so "the
        // first sample after it" would already be a cycle faster), and the onset comes from before that one.
        // Strictly before: a tie in milliseconds takes the earlier sample, which errs high; the jump check then runs one
        // cycle early (a jump into the first-set cycle can go unchecked). Known and accepted: wall-clock ms ties are
        // rare, and the onset stays on the safe (slower) side.
        if (firstSetAtMillis != null && firstSetAtMillis < edge.atMillis) {
            val i = recent.indexOfLast { it.atMillis < firstSetAtMillis }
            // No kept sample older than the first set frame (the lookback was reset between the two frames, or more
            // than [HISTORY] samples landed in the window): where the alarm fired is unknown. NEVER fall back to the
            // confirmed edge, that is the one-0x04-late, LOW onset this exists to prevent.
            if (i < 0) return OnsetVerdict.NoPrevious
            // The same 1 s stall guard as below, between the sample the first set frame arrived beside and that frame.
            if (firstSetAtMillis - recent[i].atMillis > MAX_ONSET_GAP_MS) return OnsetVerdict.TooFast
            val earlier = recent.subList(0, i + 1).fold(OnsetLookback()) { l, p -> l.push(p) }
            return earlier.onsetFor(recent[i])
        }
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

        /** Samples kept for the debounced-edge re-judgement; a Begode cycle is ~4 samples, the debounce shifts by one cycle. */
        const val HISTORY = 12
    }
}

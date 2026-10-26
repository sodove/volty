package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.SpeedSource
import kotlin.time.ExperimentalTime

/**
 * Whether a wheel is PROVEN not to report PWM. Persisted per vehicle.
 *
 * Rider rule (2026-10-27): calibration and the estimate exist ONLY for [NEVER_REPORTED] wheels.
 * [REPORTS] is a one-way latch; the `hasDuty` flag alone is never evidence (the T4 publishes
 * `hasDuty = true` with a constant 0).
 */
enum class PwmEvidence {
    UNKNOWN, NEVER_REPORTED, REPORTS;

    companion object {
        /**
         * The persisted evidence is monotone: REPORTS > NEVER_REPORTED > UNKNOWN, so a save (or a memory
         * installed late) can only move it up, never down. A save with UNKNOWN over a stored REPORTS would
         * otherwise erase the one-way latch. (Ruling R3.)
         */
        fun monotone(a: PwmEvidence, b: PwmEvidence): PwmEvidence = if (a.ordinal >= b.ordinal) a else b
    }
}

/**
 * The evidence plus the transient bookkeeping needed to advance it. Only [evidence] is persisted
 * (via [PwmMemory]); the rest is per-session and starts empty on every connect, which is safe: a loaded
 * [PwmEvidence.NEVER_REPORTED] needs no more proof, and a loaded UNKNOWN simply starts counting again.
 *
 * Pure: time comes from the samples' own timestamps, never from a clock.
 */
@OptIn(ExperimentalTime::class)
data class PwmEvidenceProgress(
    val evidence: PwmEvidence = PwmEvidence.UNKNOWN,
    /** Cumulative milliseconds seen moving (>= [MIN_MOVING_KMH]) with a coherent duty sample, every one of them 0. */
    val movingMillis: Long = 0L,
    /** Timestamp of the previous counted moving sample, or null when the last coherent sample was not one. */
    val lastMovingAtMillis: Long? = null,
) {
    /**
     * Folds one controller sample in.
     *
     * - A sample with `hasDuty = false` is **ignored entirely**, whatever duty number rides beside the flag
     *   (an incoherent `hasDuty = false, dutyPercent = 37f` is no evidence either way and counts no moving time).
     * - A non-finite duty is ignored the same way.
     * - Any measured duty that is non-zero **latches** [PwmEvidence.REPORTS] for good. (The rule says "non-zero or
     *   differs from its first value"; a duty that differs from a first value of 0 is non-zero, and a constant
     *   non-zero duty is a duty too, so non-zero alone is the whole test.)
     * - Otherwise (duty exactly 0), time spent moving at >= [MIN_MOVING_KMH] accumulates between consecutive
     *   moving samples no further apart than [MAX_GAP_MILLIS] (a long hole is no evidence of anything); at
     *   >= [PROOF_MILLIS] cumulative, with every duty sample 0, the evidence becomes [PwmEvidence.NEVER_REPORTED].
     */
    fun next(sample: ControllerData): PwmEvidenceProgress {
        if (evidence == PwmEvidence.REPORTS) return this
        if (!sample.hasDuty || !sample.dutyPercent.isFinite()) return this
        if (sample.dutyPercent != 0f) return copy(evidence = PwmEvidence.REPORTS, lastMovingAtMillis = null)

        val at = sample.timestamp.toEpochMilliseconds()
        val moving = sample.speedSource != SpeedSource.NONE &&
            sample.speedKmh.isFinite() && sample.speedKmh >= MIN_MOVING_KMH
        if (!moving) return copy(lastMovingAtMillis = null)

        val last = lastMovingAtMillis
        val dt = if (last != null) (at - last).takeIf { it in 0..MAX_GAP_MILLIS } ?: 0L else 0L
        val total = movingMillis + dt
        val proven = evidence == PwmEvidence.NEVER_REPORTED || total >= PROOF_MILLIS
        return copy(
            evidence = if (proven) PwmEvidence.NEVER_REPORTED else evidence,
            movingMillis = total,
            lastMovingAtMillis = at
        )
    }

    companion object {
        const val MIN_MOVING_KMH = 10f
        const val PROOF_MILLIS = 5_000L

        /** Two moving samples further apart than this contribute no time (a dropout is not riding). */
        const val MAX_GAP_MILLIS = 3_000L
    }
}

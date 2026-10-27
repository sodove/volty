package ru.sodovaya.volty.presentation.vehicle.pwm

import ru.sodovaya.volty.domain.pwm.AlarmOnset
import ru.sodovaya.volty.domain.pwm.OnsetLookback
import ru.sodovaya.volty.domain.pwm.OnsetPoint
import ru.sodovaya.volty.domain.pwm.OnsetVerdict
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmCalibrationLearner
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource

/** Where the wizard is. THRESHOLD is skipped for a wheel that reports no alarm bit. */
enum class PwmWizardStep {
    INTRO, THRESHOLD, PLATEAU, RESULT,

    /** Terminal: the wizard may not run for this wheel (see [PwmWizardBlock]). Nothing is measured, nothing can be saved. */
    BLOCKED
}

/**
 * Why the wizard is closed. REPORTS: the wheel reports its own PWM (rider rule: no calibration for it, ever).
 * NOT_PROVEN: the wheel is not yet proven NOT to report it. LOAD_FAILED: the vehicle (and so its alarm threshold) could
 * not be read, and a calibration fitted against a guessed threshold would be wrong.
 */
enum class PwmWizardBlock { REPORTS, NOT_PROVEN, LOAD_FAILED }

/**
 * Why the wizard is not where the rider expects. Shown as a line of text, never an error dialog. SPIN_SLOWER: the
 * alarm came on after a speed jump of more than [OnsetLookback.MAX_ONSET_JUMP_KMH] (or a stall), so the run was not
 * counted and the wizard keeps waiting in the same run.
 */
enum class PwmWizardNotice { DISCONNECTED, NOT_CONNECTED, NO_DATA, NOTHING_CAPTURED, LOADING, SPIN_SLOWER }

/**
 * One live reading as the wizard sees it. [packV] is null when the link has no voltage yet, [alarm] is null for a
 * wheel that reports no alarm bit at all (the T4's firmware may; a wheel that does not must not be asked for it).
 */
data class WizardSample(val atMillis: Long, val speedKmh: Float, val packV: Float?, val alarm: Boolean?)

/** The highest speed held for [PwmWizardState.PLATEAU_MILLIS] within one km/h, with the voltage at that moment. */
data class WizardPlateau(val kmh: Float, val packV: Float)

/** A candidate plateau still being measured: the samples so far all lie within [minKmh]..[maxKmh] (< 1 km/h apart). */
data class WizardStretch(val startMs: Long, val minKmh: Float, val maxKmh: Float, val packVAtMax: Float)

/**
 * What the rider is shown at the end. [ratio] is the median onset speed over the plateau speed (the rider's two
 * dumps: 68.4 / 81.7 = 0.837), present only when BOTH measurements exist; [ratioWarning] when it falls outside
 * [PwmWizardState.RATIO_LOW]..[PwmWizardState.RATIO_HIGH] — the two measurements then disagree about the wheel and
 * one of them was probably taken wrongly.
 */
data class PwmWizardResult(
    val calibration: PwmCalibration,
    val onsetKmh: Float?,
    val plateauKmh: Float?,
    val ratio: Float?,
    val ratioWarning: Boolean,
)

/**
 * The calibration wizard as a pure, immutable state machine: no clock, no I/O, no Compose. The component feeds it
 * live samples ([onSample]) and the rider's taps ([start], [runAgain], [toPlateau], [finish], [restart]) and
 * renders what it holds. Every decision about a calibration lives here so it can be tested without a device.
 *
 * INTRO -> THRESHOLD (first rising edge of the wheel's alarm, whose onset is the last sample BEFORE the edge at
 * >= 10 km/h, see [OnsetLookback]; up to 3 runs) -> PLATEAU (optional;
 * the highest speed held >= 2 s within 1 km/h) -> RESULT. Phase current is never recorded: the wheel is lifted and
 * unloaded, so the fit is `k` only (R stays 0).
 */
data class PwmWizardState(
    /** The vehicle's own alarm threshold: the onsets are fitted against it, not against a constant. */
    val thresholdPercent: Int,
    val step: PwmWizardStep = PwmWizardStep.INTRO,
    val notice: PwmWizardNotice? = null,
    val lastSample: WizardSample? = null,
    val onsets: List<AlarmOnset> = emptyList(),
    /** This run already has its onset; the next edge is ignored until [runAgain]. */
    val runCaptured: Boolean = false,
    val plateau: WizardPlateau? = null,
    val result: PwmWizardResult? = null,
    /**
     * False until the owner knows the vehicle's own threshold AND that the wheel is proven not to report PWM:
     * [start] refuses (notice LOADING) until then, so a run can never be fitted against a default or a guess.
     * Defaults to true for the pure tests; the component starts it false.
     */
    val ready: Boolean = true,
    val blockReason: PwmWizardBlock? = null,
    private val prevAlarm: Boolean? = null,
    private val lookback: OnsetLookback = OnsetLookback(),
    private val stretch: WizardStretch? = null,
) {
    val canRunAgain: Boolean
        get() = step == PwmWizardStep.THRESHOLD && runCaptured && onsets.size < MAX_RUNS

    /** Whether the wheel reports an alarm bit, as of the latest sample. */
    val hasAlarmSignal: Boolean get() = lastSample?.alarm != null

    fun onSample(s: WizardSample): PwmWizardState {
        val seen = copy(lastSample = s)
        return when (step) {
            PwmWizardStep.THRESHOLD -> seen.thresholdSample(s)
            PwmWizardStep.PLATEAU -> seen.plateauSample(s)
            else -> seen
        }
    }

    /**
     * The onset is the last sample BEFORE the alarm's rising edge, not the edge sample ([OnsetLookback]).
     * Retraction: an earlier revision took the edge sample's speed and voltage; the rider's T4 dump (true PWM beside
     * the alarm bit) proved it one frame late, so the fitted `k` came out too small and the estimate read LOW.
     * A jump of more than [OnsetLookback.MAX_ONSET_JUMP_KMH] into the edge (or a stall) is not counted: notice
     * SPIN_SLOWER, the same run keeps waiting. A bit already set on the first sample has no edge and no previous sample.
     */
    private fun thresholdSample(s: WizardSample): PwmWizardState {
        val rising = s.alarm == true && prevAlarm == false
        val v = s.packV
        val valid = v != null && v.isFinite() && v > 0f && s.speedKmh.isFinite()
        val point = if (valid) OnsetPoint(s.atMillis, s.speedKmh, v, null) else null
        val look = if (point != null) lookback.push(point) else OnsetLookback()
        val next = copy(prevAlarm = s.alarm, lookback = look)
        if (!rising || runCaptured || point == null) return next
        val before = when (val verdict = look.onsetFor(point)) {
            is OnsetVerdict.Take -> verdict.point
            OnsetVerdict.NoPrevious -> return next
            OnsetVerdict.TooFast -> return next.copy(notice = PwmWizardNotice.SPIN_SLOWER)
        }
        val beforeV = before.packV
        if (beforeV == null || before.speedKmh < PwmCalibrationLearner.MIN_SPEED_KMH) return next
        return next.copy(onsets = onsets + AlarmOnset(before.speedKmh, beforeV, null, s.atMillis), runCaptured = true, notice = null)
    }

    private fun plateauSample(s: WizardSample): PwmWizardState {
        val v = s.packV
        if (!s.speedKmh.isFinite() || s.speedKmh < PwmCalibrationLearner.MIN_SPEED_KMH || v == null || !v.isFinite() || v <= 0f) {
            return copy(stretch = null)
        }
        val cur = stretch
        // The whole stretch must stay within 1 km/h (not merely each step): a slow ramp is not a plateau.
        val joins = cur != null && maxOf(cur.maxKmh, s.speedKmh) - minOf(cur.minKmh, s.speedKmh) < PLATEAU_BAND_KMH
        val grown = if (cur != null && joins) {
            val higher = s.speedKmh >= cur.maxKmh
            cur.copy(
                minKmh = minOf(cur.minKmh, s.speedKmh),
                maxKmh = maxOf(cur.maxKmh, s.speedKmh),
                packVAtMax = if (higher) v else cur.packVAtMax,
            )
        } else WizardStretch(s.atMillis, s.speedKmh, s.speedKmh, v)
        val held = s.atMillis - grown.startMs >= PLATEAU_MILLIS
        val best = plateau
        val plateau = if (held && (best == null || grown.maxKmh > best.kmh)) WizardPlateau(grown.maxKmh, grown.packVAtMax) else best
        return copy(stretch = grown, plateau = plateau, notice = null)
    }

    /** INTRO -> THRESHOLD (or PLATEAU for a wheel with no alarm bit). Refused, with a notice, without a link or data. */
    fun start(connected: Boolean): PwmWizardState {
        if (step != PwmWizardStep.INTRO) return this
        if (!ready) return copy(notice = PwmWizardNotice.LOADING)
        if (!connected) return copy(notice = PwmWizardNotice.NOT_CONNECTED)
        val sample = lastSample ?: return copy(notice = PwmWizardNotice.NO_DATA)
        return copy(
            step = if (sample.alarm != null) PwmWizardStep.THRESHOLD else PwmWizardStep.PLATEAU,
            notice = null, onsets = emptyList(), runCaptured = false, plateau = null, result = null,
            prevAlarm = null, lookback = OnsetLookback(), stretch = null,
        )
    }

    /**
     * Another run, up to [MAX_RUNS]. The alarm must be seen OFF again first (prevAlarm reset), so a bit still on from
     * the last run is not mistaken for a new edge.
     */
    fun runAgain(): PwmWizardState =
        if (canRunAgain) copy(runCaptured = false, prevAlarm = null, notice = null) else this

    /** THRESHOLD -> PLATEAU: the optional second measurement. */
    fun toPlateau(): PwmWizardState =
        if (step == PwmWizardStep.THRESHOLD) copy(step = PwmWizardStep.PLATEAU, notice = null, stretch = null) else this

    /**
     * -> RESULT. The calibration is the median-k fit of the onsets when there are any (against the vehicle's
     * threshold), else the free-spin plateau fit; with neither, stays where it is and says so.
     */
    fun finish(): PwmWizardState {
        if (step != PwmWizardStep.THRESHOLD && step != PwmWizardStep.PLATEAU) return this
        val fromOnsets = if (onsets.isEmpty()) null
        else PwmCalibrationLearner.fromOnsets(onsets, thresholdPercent.toFloat())?.copy(source = PwmCalibrationSource.WIZARD)
        val fromPlateau = plateau?.let { PwmCalibrationLearner.fromFreeSpin(it.kmh, it.packV) }
        val calibration = fromOnsets ?: fromPlateau ?: return copy(notice = PwmWizardNotice.NOTHING_CAPTURED)
        val onsetKmh = fromOnsets?.let { medianOf(onsets.map { o -> o.speedKmh }) }
        val plateauKmh = plateau?.kmh
        val ratio = if (onsetKmh != null && plateauKmh != null && plateauKmh > 0f) onsetKmh / plateauKmh else null
        return copy(
            step = PwmWizardStep.RESULT, notice = null,
            result = PwmWizardResult(
                calibration = calibration,
                onsetKmh = onsetKmh,
                plateauKmh = plateauKmh,
                ratio = ratio,
                ratioWarning = ratio != null && (ratio < RATIO_LOW || ratio > RATIO_HIGH),
            ),
        )
    }

    /** RESULT -> a clean INTRO. A blocked wizard stays blocked. */
    fun restart(): PwmWizardState =
        if (step == PwmWizardStep.BLOCKED) this else PwmWizardState(thresholdPercent, lastSample = lastSample, ready = ready)

    /**
     * The wizard may not run (rider rule, or the vehicle could not be read): from ANY step, including a result still
     * on screen, to the terminal BLOCKED step, discarding every measurement. Idempotent; the first reason is kept.
     */
    fun block(reason: PwmWizardBlock): PwmWizardState =
        if (step == PwmWizardStep.BLOCKED) this
        else PwmWizardState(thresholdPercent, step = PwmWizardStep.BLOCKED, ready = false, blockReason = reason)

    /**
     * The link dropped. Mid-run (THRESHOLD or PLATEAU) everything captured is forgotten — a half-measured run
     * across a reconnect is not a measurement — and the wizard returns to INTRO saying why. INTRO and RESULT are
     * untouched: the result is already measured and saving it needs no link.
     */
    fun onDisconnected(): PwmWizardState =
        if (step == PwmWizardStep.THRESHOLD || step == PwmWizardStep.PLATEAU) {
            PwmWizardState(thresholdPercent, notice = PwmWizardNotice.DISCONNECTED, ready = ready)
        } else this

    companion object {
        const val MAX_RUNS = 3
        const val PLATEAU_MILLIS = 2_000L
        const val PLATEAU_BAND_KMH = 1f
        const val RATIO_LOW = 0.75f
        const val RATIO_HIGH = 0.92f

        private fun medianOf(xs: List<Float>): Float {
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
        }
    }
}

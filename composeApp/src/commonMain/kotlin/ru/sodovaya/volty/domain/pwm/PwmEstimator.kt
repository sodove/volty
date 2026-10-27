package ru.sodovaya.volty.domain.pwm

import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.DutySource
import ru.sodovaya.volty.domain.model.Vehicle
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Runs the per-wheel PWM model over each published [VehicleData] of one connection: advances the persisted
 * [PwmEvidence], learns calibration onsets from the wheel's own alarm, and publishes the estimate.
 * Applied EXACTLY ONCE per snapshot in `VehicleConnection.publish`, after the energy tracker.
 *
 * Rider rule: everything here exists only for a wheel PROVEN not to report PWM ([PwmEvidence.NEVER_REPORTED]).
 * While the evidence is UNKNOWN the snapshot passes through untouched; once it is REPORTS the estimate and
 * the learning stop at once (the calibration is kept, inert).
 *
 * **What feeds the evidence (a choice, documented):** the vehicle-level fold `snapshot.motion`, once per new
 * sample (deduped by its timestamp, so a battery-triggered republish of the same motion counts nothing). The
 * fold's duty is `maxOf` over the controllers that measure one and its `hasDuty` is `any`, so a measured
 * non-zero duty from ANY controller latches REPORTS, and a constant-0 controller beside a reporting one
 * cannot prove anything. Folding each controller state separately would interleave several controllers'
 * timestamps into one reducer and count the same wall time twice.
 *
 * **Inputs of the estimate:** speed = the fold's known speed; voltage = the pack aggregate's voltage when
 * connected and > 0, else the fold's measured input voltage; phase current = the fold's `motorCurrentA` when
 * finite, **as a magnitude** (`abs`) in both the estimate and the onsets the learner stores.
 * Retraction: the first revision fed the signed current. The T4 reports a NEGATIVE decoded phase current while
 * driving forward (BegodeProtocol publishes `-phaseCurrentA`, but `abs(speed)`), so a signed term would have
 * made a least-squares R come out negative (clamped to 0, i.e. never learnable) and any positive R would
 * have been SUBTRACTED under drive load, under-reading exactly when a cutout is nearest. A magnitude
 * over-reads only during regen, which is the safe direction. There is no has-flag for phase current (no producer can tell an unreported one from zero; Begode's is
 * the live-frame value, published as 0 until its first live frame), so it matters only once a calibration has
 * learned R > 0, which needs >= 3 onsets with >= 10 A of spread.
 *
 * **When no estimate can be made** for a NEVER_REPORTED wheel the published `hasDuty` is false (the wheel's
 * constant 0 is a placeholder, not a reading), so dials show a dash and duty alarms report unavailable.
 *
 * **Learning:** each false -> true edge of the wheel's alarm bit teaches one onset
 * ([PwmCalibrationLearner.merge]), taken from the last sample BEFORE the edge at >= 10 km/h ([OnsetLookback]), not
 * from the sample where the bit is first seen. A first sample that is already true teaches nothing (the edge was not
 * seen); a held bit does not re-teach; a speed jump of more than [OnsetLookback.MAX_ONSET_JUMP_KMH] into the edge,
 * or a gap of more than a second, teaches nothing (the fire point is unknown).
 * Retraction: an earlier revision took the speed, voltage and phase current of the edge sample itself. The rider's
 * T4, after a firmware update that reports its true PWM, showed that sample is one frame late (70.8 km/h where the
 * alarm fired at 65.1), which made the fitted `k` too small and the estimate read LOW, the dangerous direction.
 *
 * **Manual entry is never promoted into the store as a model.** [Vehicle.pwmManual] converts live with
 * [PwmCalibrationLearner.fromFreeSpin] (source MANUAL, FREE_SPIN basis). Onsets learned on top of it are kept
 * (the stored calibration then carries source MANUAL + the onset history), but its k is IGNORED on read: the
 * live manual k is used, so editing the manual value takes effect at once, and clearing it removes the
 * estimate. After 3 usable onsets the learner replaces it with an ONSET fit (RIDING), which is a learned
 * result and does persist. A stored WIZARD / RIDING calibration wins over manual.
 *
 * **Memory:** like the energy tracker, the owner loads it off the funnel and calls [installMemory]. Until then
 * ([memoryReady] false) the snapshot passes through and nothing is saved. Not thread-safe: the sample funnel.
 */
@OptIn(ExperimentalTime::class)
class PwmEstimator(
    vehicle: Vehicle,
    memory: PwmMemory = PwmMemory.EMPTY,
    awaitMemory: Boolean = false,
    /** Called once, on the first [apply]: the owner starts its (suspend) load there, on the active pipeline. */
    private val onFirstApply: () -> Unit = {},
) {
    private var vehicle: Vehicle = vehicle
    private var progress = PwmEvidenceProgress(memory.evidence)
    private var calibration: PwmCalibration? = memory.calibration

    var memoryReady: Boolean = !awaitMemory
        private set

    /** Something persisted changed (evidence moved, a calibration was learned) and has not been saved yet. */
    @Volatile
    var dirty: Boolean = false
        private set

    private var started = false
    private var lastFedAtMillis: Long? = null
    private var prevAlarm: Boolean? = null
    private var lookback = OnsetLookback()
    private var lastLearnStamp: Instant? = null

    val vehicleId: String get() = vehicle.id
    val evidence: PwmEvidence get() = progress.evidence

    /** What to persist now. */
    fun memory(): PwmMemory = PwmMemory(calibration, progress.evidence)

    /** Marks the state as unsaved again (a failed write is retried at the next opportunity). */
    fun markDirty() { dirty = true }
    fun markSaved() { dirty = false }

    /**
     * Adopts the edited vehicle. A changed alarm threshold re-reads ALL stored onsets against the new value at once
     * (ruling R5, [PwmCalibrationLearner.refit]) and marks the state unsaved, so the estimate and the row follow.
     */
    fun updateVehicle(updated: Vehicle) {
        val thresholdChanged = updated.pwmAlarmThresholdPercent != vehicle.pwmAlarmThresholdPercent
        vehicle = updated
        if (!thresholdChanged) return
        val current = calibration ?: return
        val refit = PwmCalibrationLearner.refit(current, updated.pwmAlarmThresholdPercent.toFloat())
        if (refit != current) { calibration = refit; dirty = true }
    }

    /** Adopts the loaded memory. Evidence only moves up ([PwmEvidence.monotone]); a calibration already held is kept. */
    fun installMemory(loaded: PwmMemory) {
        if (memoryReady) return
        progress = progress.copy(evidence = PwmEvidence.monotone(progress.evidence, loaded.evidence))
        calibration = calibration ?: loaded.calibration?.let(::refitOnLoad)
        memoryReady = true
    }

    /**
     * A loaded ONSET fit may have lost legacy (edge-sample) onsets in the store ([ru.sodovaya.volty.data.db.decodeOnsets]),
     * so its k, fitted from all of them, is re-read from the onsets that are left, against this vehicle's threshold.
     */
    private fun refitOnLoad(c: PwmCalibration): PwmCalibration {
        val refit = PwmCalibrationLearner.refit(c, vehicle.pwmAlarmThresholdPercent.toFloat())
        if (kotlin.math.abs(refit.k - c.k) > 1e-4f || kotlin.math.abs(refit.r - c.r) > 1e-4f) dirty = true
        return refit
    }

    /** The wizard / editor wiped the calibration: forget it here too, so no stale model keeps estimating. */
    fun resetCalibration() {
        calibration = null
    }

    /** The wizard's result, installed live. Persisting it is the repository's job (one ordered path). */
    fun installCalibration(c: PwmCalibration) {
        calibration = c
    }

    fun apply(snapshot: VehicleData, now: Instant): VehicleData {
        if (!started) { started = true; onFirstApply() }
        val motion = snapshot.motion
        if (!motion.isConnected) { prevAlarm = null; lookback = OnsetLookback(); lastLearnStamp = null; return snapshot }
        if (!memoryReady) return snapshot

        feedEvidence(motion)
        if (progress.evidence != PwmEvidence.NEVER_REPORTED) { prevAlarm = motion.wheelPwmAlarm; lookback = OnsetLookback(); return snapshot }

        val packV = packVoltage(snapshot)
        val phaseA = motion.motorCurrentA.takeIf { it.isFinite() }?.let { kotlin.math.abs(it) }
        learn(motion, packV, phaseA)

        val est = effectiveCalibration()?.let { cal ->
            if (motion.speedKnown && motion.speedKmh.isFinite() && packV != null) {
                PwmModel.estimatePercent(cal, motion.speedKmh, packV, phaseA)
            } else null
        }
        val out = if (est != null) {
            motion.copy(dutyPercent = est.percent, hasDuty = true, dutySource = DutySource.ESTIMATED, dutyAtCeiling = est.atCeiling)
        } else {
            // Proven non-reporting and nothing to estimate from: the wheel's constant is not a reading.
            motion.copy(dutyPercent = 0f, hasDuty = false, dutySource = DutySource.MEASURED, dutyAtCeiling = false)
        }
        return snapshot.copy(motion = out)
    }

    private fun feedEvidence(motion: ControllerData) {
        val at = motion.timestamp.toEpochMilliseconds()
        if (lastFedAtMillis == at) return
        lastFedAtMillis = at
        val before = progress.evidence
        progress = progress.next(motion)
        if (progress.evidence != before) dirty = true
    }

    private fun packVoltage(snapshot: VehicleData): Float? {
        val agg = snapshot.aggregate
        if (agg.isConnected && agg.voltage.isFinite() && agg.voltage > 0f) return agg.voltage
        val m = snapshot.motion
        return m.inputVoltageV.takeIf { m.hasInputVoltage && it.isFinite() && it > 0f }
    }

    private fun effectiveCalibration(): PwmCalibration? {
        val stored = calibration
        if (stored == null) return manualCalibration()
        // A MANUAL-sourced stored calibration is only the onset history behind the rider's typed value.
        if (stored.source == PwmCalibrationSource.MANUAL) return manualCalibration()?.copy(onsets = stored.onsets)
        return stored
    }

    private fun manualCalibration(): PwmCalibration? {
        val m = vehicle.pwmManual ?: return null
        return PwmCalibrationLearner.fromFreeSpin(m.freeSpinKmh, m.atVoltage)?.copy(source = PwmCalibrationSource.MANUAL)
    }

    private fun learn(motion: ControllerData, packV: Float?, phaseA: Float?) {
        // One look per NEW motion sample (compared as the full Instant, so two distinct samples in the same
        // millisecond are both seen): a battery-triggered republish re-applies the same motion and must not count.
        if (motion.timestamp == lastLearnStamp) return
        lastLearnStamp = motion.timestamp
        val alarm = motion.wheelPwmAlarm
        val rising = alarm == true && prevAlarm == false
        prevAlarm = alarm
        if (packV == null || !motion.speedKnown || !motion.speedKmh.isFinite()) { lookback = OnsetLookback(); return }
        val edge = OnsetPoint(motion.timestamp.toEpochMilliseconds(), motion.speedKmh, packV, phaseA)
        lookback = lookback.push(edge)
        if (!rising) return
        // The onset is the last sample BEFORE the edge: the bit is first seen one frame after it fired.
        val before = (lookback.onsetFor(edge) as? OnsetVerdict.Take)?.point ?: return
        val beforeV = before.packV ?: return
        if (before.speedKmh < PwmCalibrationLearner.MIN_SPEED_KMH) return
        val onset = AlarmOnset(before.speedKmh, beforeV, before.phaseA, edge.atMillis)
        val merged = PwmCalibrationLearner.merge(effectiveCalibration(), onset, vehicle.pwmAlarmThresholdPercent.toFloat())
        if (merged != null && merged != calibration) { calibration = merged; dirty = true }
    }
}

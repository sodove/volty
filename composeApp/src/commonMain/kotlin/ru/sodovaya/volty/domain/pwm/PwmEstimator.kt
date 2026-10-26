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
 * finite. There is no has-flag for phase current (no producer can tell an unreported one from zero; Begode's is
 * the live-frame value, published as 0 until its first live frame), so it matters only once a calibration has
 * learned R > 0, which needs >= 3 onsets with >= 10 A of spread.
 *
 * **When no estimate can be made** for a NEVER_REPORTED wheel the published `hasDuty` is false (the wheel's
 * constant 0 is a placeholder, not a reading), so dials show a dash and duty alarms report unavailable.
 *
 * **Learning:** each false -> true edge of the wheel's alarm bit at >= 10 km/h teaches one onset
 * ([PwmCalibrationLearner.merge]). A first sample that is already true teaches nothing (the edge was not
 * seen); a held bit does not re-teach. A stored calibration wins over [Vehicle.pwmManual]; a manual
 * entry converts with [PwmCalibrationLearner.fromFreeSpin].
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

    val vehicleId: String get() = vehicle.id
    val evidence: PwmEvidence get() = progress.evidence

    /** What to persist now. */
    fun memory(): PwmMemory = PwmMemory(calibration, progress.evidence)

    /** Marks the state as unsaved again (a failed write is retried at the next opportunity). */
    fun markDirty() { dirty = true }
    fun markSaved() { dirty = false }

    fun updateVehicle(updated: Vehicle) { vehicle = updated }

    /** Adopts the loaded memory. Evidence only moves up ([PwmEvidence.monotone]); a calibration already held is kept. */
    fun installMemory(loaded: PwmMemory) {
        if (memoryReady) return
        progress = progress.copy(evidence = PwmEvidence.monotone(progress.evidence, loaded.evidence))
        calibration = calibration ?: loaded.calibration
        memoryReady = true
    }

    /** The wizard / editor wiped the calibration: forget it here too, so no stale model keeps estimating. */
    fun resetCalibration() {
        calibration = null
    }

    fun apply(snapshot: VehicleData, now: Instant): VehicleData {
        if (!started) { started = true; onFirstApply() }
        val motion = snapshot.motion
        if (!motion.isConnected) { prevAlarm = null; return snapshot }
        if (!memoryReady) return snapshot

        feedEvidence(motion)
        if (progress.evidence != PwmEvidence.NEVER_REPORTED) { prevAlarm = motion.wheelPwmAlarm; return snapshot }

        val packV = packVoltage(snapshot)
        val phaseA = motion.motorCurrentA.takeIf { it.isFinite() }
        learn(motion, packV, phaseA, now)

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

    private fun effectiveCalibration(): PwmCalibration? = calibration ?: manualCalibration()

    private fun manualCalibration(): PwmCalibration? {
        val m = vehicle.pwmManual ?: return null
        return PwmCalibrationLearner.fromFreeSpin(m.freeSpinKmh, m.atVoltage)?.copy(source = PwmCalibrationSource.MANUAL)
    }

    private fun learn(motion: ControllerData, packV: Float?, phaseA: Float?, now: Instant) {
        val alarm = motion.wheelPwmAlarm
        val rising = alarm == true && prevAlarm == false
        prevAlarm = alarm
        if (!rising || packV == null || !motion.speedKnown) return
        if (!motion.speedKmh.isFinite() || motion.speedKmh < PwmCalibrationLearner.MIN_SPEED_KMH) return
        val onset = AlarmOnset(motion.speedKmh, packV, phaseA, now.toEpochMilliseconds())
        val merged = PwmCalibrationLearner.merge(effectiveCalibration(), onset, vehicle.pwmAlarmThresholdPercent.toFloat())
        if (merged != null && merged != calibration) { calibration = merged; dirty = true }
    }
}

package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.domain.model.*
import ru.sodovaya.volty.domain.pwm.PwmEvidence

/**
 * What the live PWM estimator knows about one vehicle, as history needs it: the proven [evidence] and the latest
 * estimate (null when the estimator has none). Read non-blockingly at observation time.
 */
internal class HistoryPwmView(val vehicleId: String, val evidence: PwmEvidence, val estimatePercent: Float?)

/**
 * The view the repository publishes after each snapshot of one estimator. Pure so its gates are testable: no view
 * until the estimator's memory is loaded (its evidence is not yet trustworthy), and an estimate only from a
 * connected, duty-bearing snapshot whose duty IS an estimate ([DutySource.ESTIMATED]) - a measured duty is never
 * relabelled as one.
 */
internal fun historyPwmView(vehicleId: String, evidence: PwmEvidence, memoryReady: Boolean, motion: ControllerData): HistoryPwmView? =
    if (!memoryReady) null else HistoryPwmView(vehicleId, evidence,
        motion.dutyPercent.takeIf { motion.isConnected && motion.hasDuty && motion.dutySource == DutySource.ESTIMATED })

/**
 * Records the estimated PWM of a wheel PROVEN not to report one, in place of the decoder's placeholder.
 *
 * A Begode T4 sends a constant 0 in its duty word; the per-link observation recorded that 0 as a measurement
 * while the dashboard showed an estimate, so a ride's PWM chart was a flat line at zero. When the vehicle's
 * evidence is [PwmEvidence.NEVER_REPORTED] the DUTY reading is replaced by a DUTY_ESTIMATED one carrying the estimate, or an honest unknown when the
 * estimator has none - never the constant. Any other evidence (REPORTS, UNKNOWN) or no view at all leaves the
 * decoder's reading untouched, and a view of another vehicle is ignored.
 *
 * **Vehicle-level.** Evidence and estimate describe the vehicle's folded motion, so this rewrites the DUTY of any
 * controller observation under NEVER_REPORTED. A multi-controller vehicle where one controller reports real duty
 * is out of scope (the single-wheel Begode is the only NEVER_REPORTED shape today). While the evidence is still
 * UNKNOWN (the first seconds of a connection) the decoder's raw constant is recorded as-is.
 *
 * **Marked as an estimate.** The reading is REPLACED by a [TelemetryMetric.DUTY_ESTIMATED] reading
 * ("duty_estimated_percent"), so the raw constant is never recorded under "duty_percent" on such a vehicle and a ride's
 * totals can tell the two apart. An earlier revision recorded the estimate unmarked under "duty_percent", claiming that
 * "a separate metric id needs a schema migration"; that was wrong because a metric id is a TEXT value and the registry
 * is code (the estimated charge from [withHistoryEstimatedSoc] is still recorded unmarked, a separate question). Rides
 * recorded before this change cannot be told apart and read as measured: we do not know, and unmarked is the honest
 * answer. No upper bound is applied to the estimate (field-weakening wheels reach 105 %).
 */
internal fun withHistoryEstimatedDuty(observation: TelemetryObservation, vehicleId: String,
    view: () -> HistoryPwmView?): TelemetryObservation {
    if (observation.origin != TelemetryOrigin.CONTROLLER) return observation
    val duty = observation.readings.firstOrNull { it.metric == TelemetryMetric.DUTY } ?: return observation
    val pwm = view()?.takeIf { it.vehicleId == vehicleId && it.evidence == PwmEvidence.NEVER_REPORTED } ?: return observation
    val estimate = pwm.estimatePercent?.takeIf { it.isFinite() }
    val replacement = if (estimate != null) TelemetryReading.measured(TelemetryMetric.DUTY_ESTIMATED, estimate.toDouble())
        else TelemetryReading(TelemetryMetric.DUTY_ESTIMATED, null, TelemetryUnknown.UNAVAILABLE)
    return TelemetryObservation(observation.origin, observation.localIndex,
        observation.readings.map { if (it === duty) replacement else it }, observation.role, observation.assemblyIndex)
}

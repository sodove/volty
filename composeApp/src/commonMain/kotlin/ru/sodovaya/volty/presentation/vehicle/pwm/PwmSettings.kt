package ru.sodovaya.volty.presentation.vehicle.pwm

import ru.sodovaya.volty.domain.model.PwmManual
import ru.sodovaya.volty.domain.pwm.PwmCalibration
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.domain.pwm.PwmEvidence

/** The wheel's alarm threshold as typed: a whole percent, 1..100; anything else is invalid (null). */
fun parsePwmThreshold(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..100 }

/** What the two manual free-spin fields say together. */
sealed interface ManualParse {
    /** Both blank: the rider has no manual point (and clears a stored one). */
    data object Cleared : ManualParse
    data class Value(val manual: PwmManual) : ManualParse

    /** One blank, or a non-number, or not positive and finite. */
    data object Invalid : ManualParse
}

private fun positiveFloat(text: String): Float? =
    text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

/** Free-spin km/h at V: both numbers or neither. Comma and dot decimals both parse. */
fun parsePwmManual(kmhText: String, voltsText: String): ManualParse {
    if (kmhText.isBlank() && voltsText.isBlank()) return ManualParse.Cleared
    val kmh = positiveFloat(kmhText) ?: return ManualParse.Invalid
    val volts = positiveFloat(voltsText) ?: return ManualParse.Invalid
    return ManualParse.Value(PwmManual(kmh, volts))
}

/** A stored 81.7 reads back as "81.7", a stored 96.0 as "96". */
fun pwmNumberText(v: Float?): String {
    if (v == null || !v.isFinite()) return ""
    return if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()
}

/** Whether the calibration wizard is offered, and if not, which reason the editor row gives. */
enum class PwmEntry { WIZARD, WHEEL_REPORTS, NOT_KNOWN_YET }

/** Rider rule: the wizard exists only for a wheel PROVEN not to report PWM. */
fun pwmEntryOf(evidence: PwmEvidence): PwmEntry = when (evidence) {
    PwmEvidence.NEVER_REPORTED -> PwmEntry.WIZARD
    PwmEvidence.REPORTS -> PwmEntry.WHEEL_REPORTS
    PwmEvidence.UNKNOWN -> PwmEntry.NOT_KNOWN_YET
}

/**
 * The calibration the editor may show as "the calibration": a learned or wizard one only. A stored MANUAL-sourced
 * row carries a STALE k (the live estimator replaces it on read with the rider's typed value, see PwmEstimator), so
 * its k is never shown; the manual values come from the vehicle's own `pwmManual`.
 */
data class PwmCalibrationSummary(val k: Float, val source: PwmCalibrationSource, val points: Int)

fun pwmSummaryOf(calibration: PwmCalibration?): PwmCalibrationSummary? =
    calibration?.takeIf { it.source != PwmCalibrationSource.MANUAL && it.k.isFinite() && it.k > 0f }
        ?.let { PwmCalibrationSummary(it.k, it.source, it.points) }

/** The hub row's one-line summary. */
enum class PwmHubSummary { REPORTS, NOT_KNOWN_YET, CALIBRATED, UNCALIBRATED }

/**
 * Evidence first: a wheel that reports PWM (or has not proven it does not) is never "calibrated" or
 * "uncalibrated", because for it the estimate does not exist. Only a proven non-reporting wheel is judged on whether
 * it has a learned/wizard calibration or a typed manual point.
 */
fun pwmHubSummaryOf(evidence: PwmEvidence, calibration: PwmCalibrationSummary?, manual: PwmManual?): PwmHubSummary =
    when (evidence) {
        PwmEvidence.REPORTS -> PwmHubSummary.REPORTS
        PwmEvidence.UNKNOWN -> PwmHubSummary.NOT_KNOWN_YET
        PwmEvidence.NEVER_REPORTED ->
            if (calibration != null || manual != null) PwmHubSummary.CALIBRATED else PwmHubSummary.UNCALIBRATED
    }

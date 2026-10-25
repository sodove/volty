package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.domain.usecase.AlertKind
import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.util.UnitFormatter
import ru.sodovaya.volty.util.UnitSystem
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.roundToInt

// The pure half of the banner's alert rows. The @Composable banner only picks a string resource per
// kind and hands it the text built here - Compose is not unit-testable in this repo, so every
// decision (which number, which unit, which colour) lives in these functions.

/**
 * The identity of an alert's row in the banner's stack: the KIND, never the value text, so the
 * second episode of a kind counts on the same row instead of opening a new one. Prefixed so it can
 * never collide with a decoder's fault message.
 */
internal fun alertRowKey(kind: AlertKind): String = when (kind) {
    // One row, "Battery N C", for the whole warn -> high escalation: two keys would show a lingering
    // warn row beside the high one, and count warn again as a second episode when high released.
    AlertKind.TEMPERATURE_WARN, AlertKind.TEMPERATURE_HIGH -> "alert:BATTERY_TEMP"
    else -> "alert:${kind.name}"
}

/**
 * The number part of an alert row's label, ready to drop into the localized template.
 * Speed is converted to the rider's unit (the template names mph or km/h); temperatures stay in
 * degrees C like everywhere else in the app; duty and charge carry their percent sign.
 */
internal fun alertBannerNumber(kind: AlertKind, value: Float, units: UnitSystem): String = when (kind) {
    AlertKind.DUTY_HIGH, AlertKind.SOC_LOW, AlertKind.SOC_CUTOFF -> "${value.roundToInt()} %"
    AlertKind.SPEED_HIGH -> UnitFormatter.speed(value, units)
    AlertKind.CELL_HIGH, AlertKind.CELL_LOW -> formatFixed(value, 2)
    else -> value.roundToInt().toString()
}

internal enum class BannerTone { ERROR, WARNING }

/** Red when anything in the stack is a fault or a CRITICAL alert; amber when it is only warnings. */
internal fun bannerTone(entries: List<RideDashboardComponent.FaultEntry>): BannerTone =
    if (entries.any { it.alert == null || it.alert.severity == AlertSeverity.CRITICAL }) BannerTone.ERROR
    else BannerTone.WARNING

/** The banner's title is "faults" while any real fault is listed, "alerts" when it is only ours. */
internal fun bannerTitleIsFaults(entries: List<RideDashboardComponent.FaultEntry>): Boolean =
    entries.any { it.alert == null }

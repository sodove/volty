package ru.sodovaya.volty.presentation.ride

// The episode rule (EPISODE_GAP, startsNewEpisode) moved to domain/alert/FaultEpisode.kt 2026-10-25:
// the BMS-fault notification shares it, and presentation must not own a rule the domain needs.

/** The choices offered for how long a cleared row stays in the banner, in seconds (0 = while active). */
internal val LINGER_OPTIONS_SEC: List<Int> = listOf(0, 5, 10, 30, 60, 120, 300)

/**
 * The offered option closest to a stored [seconds] value, for display; an exact match is itself.
 * A tie goes to the shorter option. Negative values count as 0.
 */
internal fun lingerOptionFor(seconds: Int): Int {
    val value = seconds.coerceAtLeast(0)
    return LINGER_OPTIONS_SEC.minWith(compareBy({ kotlin.math.abs(it - value) }, { it }))
}

/**
 * How long a cleared banner row stays, in seconds. A **fault** row (no [alert]) uses [faultSec]; one of
 * our own alert rows uses the linger of its episode's **worst** severity - an escalated warning ->
 * critical row is a critical row (the record's alert is already folded to the worst) - so a
 * frequent first-level warning leaves quickly while a device fault stays longer.
 */
internal fun lingerSecFor(
    alert: ru.sodovaya.volty.domain.alert.ActiveAlert?,
    faultSec: Int,
    criticalSec: Int,
    warningSec: Int
): Int = when (alert?.severity) {
    null -> faultSec
    ru.sodovaya.volty.domain.usecase.AlertSeverity.CRITICAL -> criticalSec
    else -> warningSec
}

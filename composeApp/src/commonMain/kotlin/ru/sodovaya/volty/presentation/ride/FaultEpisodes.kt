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

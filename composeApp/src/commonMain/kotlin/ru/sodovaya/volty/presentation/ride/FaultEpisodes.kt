package ru.sodovaya.volty.presentation.ride

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The one place that decides what an "episode" of a fault (or, later, an alert) is.
 *
 * ANT BMS repeats its fault flags in every frame, and a Begode link at the edge of range flickers.
 * Counting frames produced "discharge disabled x9992". An episode is one stretch of presence: it
 * ends only after the condition has been absent for [EPISODE_GAP] continuously, measured by the
 * samples' own timestamps (never a timer), and a return within that window continues the episode.
 */
internal val EPISODE_GAP: Duration = 2.seconds

/** True when a condition cleared at [clearedAt] and seen again at [now] is a NEW episode. */
@OptIn(ExperimentalTime::class)
internal fun startsNewEpisode(clearedAt: Instant?, now: Instant): Boolean =
    clearedAt != null && now - clearedAt >= EPISODE_GAP

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

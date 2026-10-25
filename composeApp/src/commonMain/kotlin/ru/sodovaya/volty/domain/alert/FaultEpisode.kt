package ru.sodovaya.volty.domain.alert

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
 *
 * Lives in the domain because two layers share the rule and must not each keep a copy: the ride
 * banner's occurrence counter (presentation) and the BMS-fault notification (AlertEngine).
 */
internal val EPISODE_GAP: Duration = 2.seconds

/** True when a condition cleared at [clearedAt] and seen again at [now] is a NEW episode. */
@OptIn(ExperimentalTime::class)
internal fun startsNewEpisode(clearedAt: Instant?, now: Instant): Boolean =
    clearedAt != null && now - clearedAt >= EPISODE_GAP

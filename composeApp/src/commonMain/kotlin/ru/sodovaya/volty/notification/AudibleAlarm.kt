package ru.sodovaya.volty.notification

import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.alert.AlarmState

/**
 * The continuous audible alarm (F §4) — the half of Volty that works when the
 * rider is not looking at the screen.
 *
 * Distinct from [Notifier], and deliberately so (F §2): a notification is a
 * discrete event that happened, this is a *live* signal that a condition is true
 * right now. It plays while the condition holds, escalates with it, and stops
 * when it clears. It is not a notification channel and does not post anything.
 *
 * ### The contract
 *
 * - **Silent at level 0.** [AlarmState.level] 0 means nothing is wrong; nothing
 *   sounds and nothing buzzes.
 * - **Idempotent per level.** Calling [update] repeatedly with the same level
 *   must not restart the tone or stutter the haptic. The alarm is driven from a
 *   sample stream arriving several times a second, so "no change" is the common
 *   case and it has to cost nothing. Enforced in common code by
 *   `AlarmSignalPlanner`, which compares whole commands rather than trusting each
 *   platform to remember.
 * - **Urgency refines, it never carries.** F §12: three of the four alert kinds
 *   ship a single level, so urgency is a step function for them. Each of the
 *   three steps must therefore be recognisable on its own — see `AlarmToneTable`.
 *
 * ### Who calls what
 *
 * Task 8's `MonitoringService` owns the instance: it feeds [update] from
 * `AlarmController`, mirrors the prefs into [setModalities], and calls [release]
 * when the service dies. Task 9's settings screen calls [preview]. Nothing else
 * should touch it — two owners would mean two things fighting over one speaker.
 */
expect class AudibleAlarm {

    /**
     * Play, change, or stop the alarm to match [state].
     *
     * Cheap and idempotent when nothing changed; safe to call on every sample.
     */
    fun update(state: AlarmState)

    /**
     * Apply the rider's three switches, taking effect immediately on anything
     * already playing — a rider turning tones off mid-alarm means *now*.
     */
    fun setModalities(modalities: AlarmModalities)

    /**
     * Play [level]'s signal once, briefly, for the settings screen's
     * "проверить сигнал" button (F §11).
     *
     * Plays the step at urgency 0 — its plainest, most identifiable form — for
     * long enough to hear the rhythm, then hands the alarm back to the live
     * state. **Refused while a real alarm is sounding**; a demo must never take
     * the speaker away from the thing it is demonstrating.
     */
    fun preview(level: Int)

    /**
     * Silence everything and forget the last state, without tearing the object
     * down. What Task 8 calls when the ride ends or the link drops.
     */
    fun stop()

    /**
     * Release every platform resource — audio track, tone thread, audio focus,
     * vibrator. The instance is not usable afterwards. Called from the service's
     * `onDestroy`; a tone thread that outlives its service is a battery drain on
     * a phone the rider is still carrying.
     */
    fun release()
}

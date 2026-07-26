package ru.sodovaya.volty.notification

import ru.sodovaya.volty.domain.usecase.AlertSeverity
import ru.sodovaya.volty.util.formatFixed
import ru.sodovaya.volty.util.formatSigned

data class LiveSummary(
    val vehicleName: String,
    val socPercent: Int,
    val voltageV: Float,
    val currentA: Float,
    val etaText: String?,
    /**
     * True while a rider's "Silence" is suppressing the audible alarm — see
     * [ru.sodovaya.volty.domain.alert.AlarmDriver.isSilenced].
     *
     * Defaulted so the alert-side callers that never touch the alarm keep
     * compiling honestly: *not silenced* is the truth for anything that has no
     * driver to ask.
     */
    val alarmSilenced: Boolean = false
)

/**
 * **What the live notification says** — decided here, rendered by the platform
 * notifier.
 *
 * ### Why this is not in `AndroidNotifier`
 *
 * Two reasons, and the second is the one that got this file written. The first
 * is the standing rule: `AndroidNotifier` cannot be unit-tested in this repo, so
 * anything it *decides* is untested by construction.
 *
 * The second is [LiveSummary.alarmSilenced]. A silence in force used to be
 * completely invisible: the same button, the same content line, and an alarm
 * that simply never sounds — which from the saddle is indistinguishable from
 * "nothing is wrong". [AlarmSilencer][ru.sodovaya.volty.domain.alert.AlarmSilencer]
 * concedes that the suppression can last the rest of the ride on a dead
 * controller link, so the rider has to be able to *see* it, and seeing it is a
 * decision worth a test.
 *
 * ### Why these strings are hardcoded English
 *
 * This whole notifier bypasses `composeResources`: `Notifier.showLive` is not a
 * suspend function and Compose Resources' `getString` is, so reaching the
 * `values/` and `values-ru/` catalogues from here would mean making the
 * interface suspend — a restructuring that is out of scope. What was *not*
 * acceptable was the state it was in: a Cyrillic "Заглушить" sitting next to an
 * English "Disconnect", so **every** locale got a mixed-language notification.
 *
 * English throughout is the least-bad consistent option, because `values/` — the
 * fallback every non-Russian locale resolves to — is English, and the rest of
 * this notification ("Starting…", the `Volty · name` title) already is. A
 * Russian rider now reads two English words instead of one; nobody reads two
 * languages at once. The proper fix is moving the notifier onto
 * `composeResources`, recorded as follow-up.
 */
object LiveNotificationText {

    /** The silence action while the alarm can still sound. */
    const val SILENCE: String = "Silence"

    /**
     * The same action while a silence is in force.
     *
     * A label rather than a verb on purpose: the button is idempotent (pressing
     * it again re-silences nothing), so what it owes the rider here is the
     * *state*, not another instruction.
     */
    const val SILENCED: String = "Silenced"

    /** Ends the BLE session. English for the reason above; it always was. */
    const val DISCONNECT: String = "Disconnect"

    /**
     * The clause appended to the content line while a silence is in force.
     *
     * The action label alone would not do it: notification actions collapse away
     * on a locked screen and in some launchers' compact views, and the content
     * line is the part that always shows.
     */
    const val SILENCED_NOTE: String = "alarm silenced"

    /** The silence action's label for [silenced]. */
    fun silenceAction(silenced: Boolean): String = if (silenced) SILENCED else SILENCE

    /** The line under the title: the ride, plus a silence if one is in force. */
    fun content(summary: LiveSummary): String = listOfNotNull(
        "${summary.socPercent}%",
        formatFixed(summary.voltageV, 1) + " V",
        formatSigned(summary.currentA, 1) + " A",
        summary.etaText,
        SILENCED_NOTE.takeIf { summary.alarmSilenced }
    ).joinToString(" · ")
}

interface Notifier {
    fun showLive(summary: LiveSummary)
    fun cancelLive()
    fun showAlert(title: String, text: String, severity: AlertSeverity, alertId: Int)
}

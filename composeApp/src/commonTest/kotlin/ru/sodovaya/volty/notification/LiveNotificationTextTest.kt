package ru.sodovaya.volty.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * What the live notification says — the half of `AndroidNotifier` that decides
 * anything, pulled into common code precisely so it can be asserted.
 *
 * The load-bearing tests are the silenced ones. A rider who presses "Silence" on
 * a stuck duty alarm gets a suppression that, on the frozen controller link the
 * button exists for, never lifts by itself — and before this the notification
 * looked identical either way. An alarm that never sounds is indistinguishable
 * from nothing being wrong, so the difference has to be on screen.
 */
class LiveNotificationTextTest {

    private fun summary(silenced: Boolean = false, eta: String? = null) = LiveSummary(
        vehicleName = "Wheel",
        socPercent = 62,
        voltageV = 58.4f,
        currentA = -12.5f,
        etaText = eta,
        alarmSilenced = silenced
    )

    // ------------------------------------------------------- the ordinary line

    @Test
    fun `the content line is the ride, in the order the rider reads it`() {
        assertEquals("62% · 58.4 V · -12.5 A", LiveNotificationText.content(summary()))
    }

    @Test
    fun `an eta is carried when there is one`() {
        assertEquals("62% · 58.4 V · -12.5 A · 40 km", LiveNotificationText.content(summary(eta = "40 km")))
    }

    /**
     * The default matters: [LiveSummary.alarmSilenced] defaults to false, and a
     * caller with no alarm driver to ask must not accidentally claim a silence.
     */
    @Test
    fun `a summary built without an alarm says nothing about a silence`() {
        val bare = LiveSummary(vehicleName = "Wheel", socPercent = 62, voltageV = 58.4f, currentA = -12.5f, etaText = null)
        assertFalse(bare.alarmSilenced)
        assertFalse(LiveNotificationText.content(bare).contains(LiveNotificationText.SILENCED_NOTE))
        assertEquals(LiveNotificationText.SILENCE, LiveNotificationText.silenceAction(bare.alarmSilenced))
    }

    // ----------------------------------------------------- a silence in force

    /**
     * The whole point. Both cues are asserted because they fail independently:
     * the action label collapses away on a locked screen, and the content line is
     * what always shows.
     */
    @Test
    fun `a silence in force changes both the action label and the content line`() {
        val quiet = summary(silenced = false)
        val silenced = summary(silenced = true)

        assertNotEquals(
            LiveNotificationText.silenceAction(false),
            LiveNotificationText.silenceAction(true),
            "the button must not read the same whether or not the alarm is muted"
        )
        assertEquals(LiveNotificationText.SILENCED, LiveNotificationText.silenceAction(true))
        assertEquals(LiveNotificationText.SILENCE, LiveNotificationText.silenceAction(false))

        assertNotEquals(
            LiveNotificationText.content(quiet),
            LiveNotificationText.content(silenced),
            "and neither must the line the rider actually reads"
        )
        assertTrue(
            LiveNotificationText.content(silenced).contains(LiveNotificationText.SILENCED_NOTE),
            "the silenced line must say so in words: 'it never sounds' is not a message"
        )
    }

    /** The ride figures survive the note — a silence must not cost the rider their telemetry line. */
    @Test
    fun `the silenced line still carries the ride`() {
        assertEquals("62% · 58.4 V · -12.5 A · alarm silenced", LiveNotificationText.content(summary(silenced = true)))
    }

    // ------------------------------------------------------ one language, F §3

    /**
     * A Cyrillic "Заглушить" used to sit beside an English "Disconnect", so
     * **every** locale got a mixed-language notification. The notifier cannot
     * reach `composeResources` without being made suspend, so consistency with
     * each other is what is owed until it can; `values/` — what every non-Russian
     * locale falls back to — is English, so English is the consistent choice.
     */
    @Test
    fun `every action label is in one language`() {
        val labels = listOf(
            LiveNotificationText.SILENCE,
            LiveNotificationText.SILENCED,
            LiveNotificationText.DISCONNECT,
            LiveNotificationText.SILENCED_NOTE
        )
        for (label in labels) {
            assertTrue(label.isNotBlank(), "an empty label is not a label")
            assertTrue(
                label.none { it in 'А'..'я' || it == 'ё' || it == 'Ё' },
                "'$label' mixes Cyrillic into a notification whose other buttons are English"
            )
        }
    }
}

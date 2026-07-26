package ru.sodovaya.volty.domain.alert

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Заглушить" — the rider's escape from an alarm they cannot otherwise stop
 * (F §14), and the one decision behind it: **suppress until the level returns to
 * 0, then re-arm**.
 *
 * The alternative — silence this instant, re-raise on the next sample — is the
 * one that would be useless in the scenario that motivates the button, so the
 * first test here is the one that tells the two apart.
 */
class AlarmSilencerTest {

    private fun sounding(level: Int) = AlarmState(level = level, urgency = 0.5f)

    /**
     * **The headline.** On a frozen controller link the same hot reading arrives
     * again and again — or, worse, never arrives again and the last state stands.
     * A silence that lifted on the next state would put the tone straight back in
     * the rider's pocket, and they would spend the ride pressing the button.
     */
    @Test
    fun a_silenced_alarm_stays_silent_while_the_danger_reading_persists() {
        val silencer = AlarmSilencer()
        assertEquals(sounding(3), silencer.gate(sounding(3)), "fixture check: it passes states through until silenced")

        silencer.silence()

        repeat(5) {
            assertEquals(
                AlarmState.SILENT,
                silencer.gate(sounding(3)),
                "the tone came back while the reading that caused it was still there"
            )
        }
    }

    /** It is a silence, not a disarm: the next genuinely new danger sounds. */
    @Test
    fun the_silence_lifts_when_the_alarm_recovers_and_the_next_danger_sounds() {
        val silencer = AlarmSilencer()
        silencer.silence()
        assertEquals(AlarmState.SILENT, silencer.gate(sounding(2)))

        // The rider backed off / the link came back / the motor cooled.
        assertEquals(AlarmState.SILENT, silencer.gate(AlarmState.SILENT))
        assertFalse(silencer.isSilenced, "recovery must re-arm it, or the ride continues unguarded")

        assertEquals(sounding(1), silencer.gate(sounding(1)), "the next threshold crossing must be audible")
    }

    /**
     * The escalation case, and the one a naive "remember the level" design gets
     * wrong: a silenced step 2 that becomes a step 3 is **still silenced**. The
     * rider silenced the alarm, not that particular step, and re-raising on
     * escalation would defeat the button on any reading that drifts upward.
     */
    @Test
    fun escalating_past_the_silenced_step_does_not_re_raise_it() {
        val silencer = AlarmSilencer()
        silencer.gate(sounding(2))
        silencer.silence()

        assertEquals(AlarmState.SILENT, silencer.gate(sounding(3)))
        assertTrue(silencer.isSilenced)
    }

    /**
     * Silencing nothing is a no-op rather than an arm-for-next-time. A "silence"
     * that swallowed an alarm the rider had never heard would be a disarm wearing
     * the wrong label — that is what the master switch is for.
     */
    @Test
    fun silencing_while_nothing_sounds_does_not_swallow_the_next_alarm() {
        val silencer = AlarmSilencer()

        silencer.silence()
        assertEquals(AlarmState.SILENT, silencer.gate(AlarmState.SILENT))
        assertFalse(silencer.isSilenced, "a silence pressed against silence is spent immediately")

        assertEquals(sounding(2), silencer.gate(sounding(2)), "the next alarm must be heard")
    }

    /** Untouched, it is the identity: safe to run every state through. */
    @Test
    fun an_un_silenced_gate_changes_nothing() {
        val silencer = AlarmSilencer()

        listOf(AlarmState.SILENT, sounding(1), sounding(2), sounding(3)).forEach {
            assertEquals(it, silencer.gate(it))
        }
        assertFalse(silencer.isSilenced)
    }

    /** Pressing it twice is one silence, and the second press does not outlive the first. */
    @Test
    fun a_second_press_does_not_extend_the_silence_past_the_recovery() {
        val silencer = AlarmSilencer()
        silencer.silence()
        silencer.silence()

        assertEquals(AlarmState.SILENT, silencer.gate(sounding(3)))
        silencer.gate(AlarmState.SILENT)

        assertEquals(sounding(3), silencer.gate(sounding(3)), "two presses must not stack into a longer silence")
    }
}

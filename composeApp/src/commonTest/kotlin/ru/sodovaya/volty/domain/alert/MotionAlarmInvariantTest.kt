package ru.sodovaya.volty.domain.alert

import ru.sodovaya.volty.domain.stats.DutyBands
import ru.sodovaya.volty.domain.stats.TempBands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F §11.1 — the dial always reddens *before* the alarm sounds.
 *
 * ```
 * first(levels).threshold >= band.warn   // the alarm never precedes the amber dial
 * last(levels).threshold  >= band.red    // the dial always reddens before the alarm sounds
 * ```
 *
 * Read deliberately from the **live** [DutyBands]/[TempBands] constants rather
 * than copies, so that lowering an alarm default *or* raising a dashboard band
 * turns this test red instead of silently shipping an alarm that fires while the
 * dial is merely amber (F §10.1's train-them-to-ignore-it failure).
 */
class MotionAlarmInvariantTest {

    private data class Band(val warn: Float, val red: Float)

    /** The dashboard band a kind is coloured by, or null if the dial has none. */
    private fun bandFor(kind: MotionAlertKind): Band? = when (kind) {
        MotionAlertKind.DUTY -> Band(
            warn = DutyBands.DEFAULT_WARN_PERCENT,
            red = DutyBands.DEFAULT_CRITICAL_PERCENT
        )
        MotionAlertKind.ESC_TEMP -> Band(warn = TempBands.ESC_WARN_C, red = TempBands.ESC_CRITICAL_C)
        MotionAlertKind.MOTOR_TEMP -> Band(warn = TempBands.MOTOR_WARN_C, red = TempBands.MOTOR_CRITICAL_C)
        // Speed is not a coloured dial band — it has no amber/red to order against.
        MotionAlertKind.SPEED -> null
    }

    @Test fun every_default_alarm_level_list_sits_at_or_above_its_dashboard_band() {
        var checked = 0
        MotionAlertKind.entries.forEach { kind ->
            val band = bandFor(kind) ?: return@forEach
            val levels = AlarmDefaults.rule(kind).levels
            if (levels.isEmpty()) return@forEach
            val first = levels.first().thresholdValue
            val last = levels.last().thresholdValue
            assertTrue(
                first >= band.warn,
                "$kind: first alarm level $first is below the dial's amber ${band.warn} — " +
                    "the alarm would sound before the dial even warns"
            )
            assertTrue(
                last >= band.red,
                "$kind: last alarm level $last is below the dial's red ${band.red} — " +
                    "the alarm would top out while the dial is still amber"
            )
            checked++
        }
        assertEquals(3, checked, "expected duty + ESC temp + motor temp to be checked")
    }

    @Test fun a_kind_with_no_dashboard_band_ships_no_default_levels() {
        // Otherwise a default would escape the invariant above with nothing to
        // order it against: give SPEED a default number and this fails, forcing
        // whoever does it to say what band it must sit above.
        MotionAlertKind.entries.filter { bandFor(it) == null }.forEach { kind ->
            assertTrue(
                AlarmDefaults.rule(kind).isOff,
                "$kind has no dashboard band, so it cannot ship default levels"
            )
        }
    }

    /**
     * **This test is meant to be edited** — it is a notification, not a veto.
     *
     * The task brief asks that editing `DutyBands`/`TempBands` fail a test. The
     * inequality above cannot deliver that on its own: moving a band *down*
     * (dial warns even earlier against a fixed alarm) strengthens the ordering
     * §10.1 asks for rather than violating it, so it stays green. This pin makes
     * every band change surface, and it also catches a refactor that re-points a
     * band constant at something else while still compiling.
     *
     * So a failure here is not "you broke the ordering". It is "a band moved —
     * confirm the alarm still sits above it, then update the pin".
     */
    @Test fun the_bands_this_test_pins_against_are_the_ones_the_dashboard_uses() {
        fun pin(expected: Float, actual: Float, name: String) = assertEquals(
            expected, actual,
            "$name changed. Moved it on purpose? Re-check AlarmDefaults still sits " +
                "at or above it (see the test above), then update this pin to the new value."
        )
        pin(75f, DutyBands.DEFAULT_WARN_PERCENT, "DutyBands.DEFAULT_WARN_PERCENT")
        pin(90f, DutyBands.DEFAULT_CRITICAL_PERCENT, "DutyBands.DEFAULT_CRITICAL_PERCENT")
        pin(70f, TempBands.ESC_WARN_C, "TempBands.ESC_WARN_C")
        pin(85f, TempBands.ESC_CRITICAL_C, "TempBands.ESC_CRITICAL_C")
        pin(85f, TempBands.MOTOR_WARN_C, "TempBands.MOTOR_WARN_C")
        pin(100f, TempBands.MOTOR_CRITICAL_C, "TempBands.MOTOR_CRITICAL_C")
    }
}

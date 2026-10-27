package ru.sodovaya.volty.domain.alert

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * "A PWM calibration is in progress" - one app-wide signal (a Koin singleton).
 *
 * **Why it exists.** A PWM calibration has the rider lift the wheel and spin it in the air to ~80 km/h. On 0.7.54
 * that was a perfectly real duty and speed reading to every alert path: the sound alarm sounded, and the dashboard
 * banner kept "Duty 93 % x8", "PWM above the threshold (wheel) x8" and "Speed 80 km/h x7". During the wizard's run
 * the duty and speed readings are not riding, so the consumers ([AlarmDriver], the notification engine, the banner
 * tracker) ask [isActive] and leave out the duty and speed kinds (and the wheel's own PWM-alarm row). Temperatures,
 * battery and faults are NOT muted: a spinning wheel can still overheat.
 *
 * **A stuck signal is impossible by construction.** Every [begin] sets a deadline [TIME_BOX_MS] ahead and [isActive]
 * is false once the clock passes it, so a wizard that died without calling [end] (process death does not run
 * `doOnDestroy`) releases the alarms by itself after three minutes. A live wizard renews it with every state change
 * ([begin] is its heartbeat). The deadline lives inside this class and is checked at read time: there is no timer and
 * no delayed loop to wedge a test.
 *
 * Single-writer in practice (the one open wizard); readers are on other threads, hence the volatile deadline.
 */
@OptIn(ExperimentalTime::class)
class CalibrationMute(
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    @kotlin.concurrent.Volatile
    private var deadlineMs: Long = NOT_ACTIVE

    /** Calibration is running now (also the heartbeat): extends the deadline to [TIME_BOX_MS] from now. */
    fun begin() { deadlineMs = nowMs() + TIME_BOX_MS }

    /** The wizard left its running steps (result, cancel, blocked, destroyed). */
    fun end() { deadlineMs = NOT_ACTIVE }

    /** True while a calibration run is in progress and its time box has not run out. */
    fun isActive(): Boolean {
        val d = deadlineMs
        return d != NOT_ACTIVE && nowMs() < d
    }

    companion object {
        /** Auto-off after this long without a [begin]: 3 minutes. */
        const val TIME_BOX_MS = 3 * 60 * 1000L
        private const val NOT_ACTIVE = Long.MIN_VALUE
    }
}

/** The armed rules without the kinds a calibration run mutes (duty, speed); temperatures stay. */
internal fun ArmedRules.withoutCalibrationMuted(): ArmedRules =
    ArmedRules(rules.filter { it.kind != MotionAlertKind.DUTY && it.kind != MotionAlertKind.SPEED })

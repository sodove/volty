package ru.sodovaya.volty.notification

import android.content.Context
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.alert.AlarmOutput
import ru.sodovaya.volty.domain.alert.AlarmState

/**
 * Owns the process's one [AudibleAlarm] across the foreground service's comings
 * and goings.
 *
 * ### Why this exists, and why it is not over-engineering
 *
 * Two requirements collided head-on:
 *
 *  - `AudibleAlarm` is a Koin **single** because there is one speaker and two
 *    would fight over it — the service drives the live alarm, Task 9's settings
 *    screen previews through the same object so a preview cannot steal the
 *    speaker from a real alarm;
 *  - `MonitoringService.onDestroy` **must** call `AudibleAlarm.release()`, or the
 *    tone thread outlives the service (Task 7 §8.7).
 *
 * But `release()` is *terminal*: it latches `released = true` and every entry
 * point returns immediately afterwards. A process-scoped single released by a
 * service that stops and starts several times a session — every time the rider
 * parks and rides again — would be **permanently dead from the second ride on**,
 * with no error and no sound. That is worse than the leak it fixes, and it is the
 * exact silent-failure shape this part keeps finding.
 *
 * So the *holder* is the single and the alarm is per service lifetime: exactly
 * one live instance at any moment, [release]d with the service that owns it, and
 * rebuilt by the next caller that asks. Nothing about `AudibleAlarm` changes; a
 * released instance is simply never handed out again.
 *
 * Thread-safe. [acquire] may be called from the service's main thread and from
 * Task 9's UI, and the actual `release()` runs outside [lock] because it joins
 * the tone thread for up to half a second.
 */
class AudibleAlarmHolder(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var current: AudibleAlarm? = null

    /** The live alarm, building one if the last was released. Never returns a released instance. */
    fun acquire(): AudibleAlarm = synchronized(lock) {
        current ?: AudibleAlarm(appContext).also { current = it }
    }

    /**
     * Tear the current alarm down — audio track, tone thread, focus, vibrator —
     * and forget it. A no-op when nothing was acquired, which is the case when the
     * service stopped itself before ever starting the alarm.
     */
    fun release() {
        val dying = synchronized(lock) {
            val previous = current
            current = null
            previous
        }
        dying?.release()
    }
}

/**
 * [AudibleAlarm] seen through the driver's port.
 *
 * A thin adapter rather than `AudibleAlarm : AlarmOutput` because an `expect
 * class` may not gain a supertype without editing it, and because the port is
 * deliberately narrower than the class: `preview`, `stop` and `release` stay off
 * it so no collector can reach them.
 */
fun AudibleAlarm.asAlarmOutput(): AlarmOutput = object : AlarmOutput {
    override fun update(state: AlarmState) = this@asAlarmOutput.update(state)
    override fun setModalities(modalities: AlarmModalities) =
        this@asAlarmOutput.setModalities(modalities)
}

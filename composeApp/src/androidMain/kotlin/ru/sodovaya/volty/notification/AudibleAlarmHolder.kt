package ru.sodovaya.volty.notification

import android.content.Context
import ru.sodovaya.volty.domain.alert.AlarmModalities
import ru.sodovaya.volty.domain.alert.AlarmOutput
import ru.sodovaya.volty.domain.alert.AlarmState

/**
 * Owns the process's one [AudibleAlarm] across the foreground service's comings
 * and goings, and is the **only** thing that ever holds a reference to it.
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
 * exact silent-failure shape this part keeps finding. (The latch itself is right
 * and must stay: it is what makes a state arriving from a just-cancelled
 * collector harmless after teardown.)
 *
 * So the *holder* is the single and the alarm is per service lifetime: exactly
 * one live instance at any moment, [release]d with the service that owns it, and
 * rebuilt by the next caller that asks. Nothing about `AudibleAlarm` changes; a
 * released instance is simply never handed out again.
 *
 * ### No caller may hold an `AudibleAlarm`
 *
 * There is deliberately no public `acquire()`. A component that stored the
 * instance — a Task 9 ViewModel, say — would keep working until the next
 * `onDestroy` and then go silently dead, which is the very bug this class exists
 * to prevent, relocated one layer up. So every operation is a method *here*,
 * routed through whichever instance is live at the moment of the call:
 *
 *  - [openOutput] brings the alarm up and returns the driver's port. The port
 *    never creates an alarm of its own, so a state arriving after [release] —
 *    from a collector already cancelled but not yet unwound — is a no-op rather
 *    than a resurrection nothing would ever release;
 *  - [preview] and [stop] are for Task 9's settings screen.
 *
 * ### Threading
 *
 * Every method serialises on [lock], **including the teardown inside [release]**.
 * That means a concurrent call can block for as long as `AudibleAlarm.release()`
 * takes, which is bounded by its 500 ms tone-thread join. That is the deliberate
 * trade: releasing outside the lock would let a concurrent [openOutput] build
 * instance #2 while #1's tone thread was still winding down — two live alarms and
 * two `AudioTrack`s, which is precisely what "exactly one live instance" is
 * supposed to rule out.
 */
class AudibleAlarmHolder(context: Context) {

    private val appContext = context.applicationContext
    private val lock = Any()
    private var current: AudibleAlarm? = null

    /**
     * The driver's port: a stable adapter that routes to whatever alarm is live
     * **without ever building one**. See the class doc for why creation is not
     * allowed here.
     */
    private val port = object : AlarmOutput {
        override fun update(state: AlarmState) {
            synchronized(lock) { current?.update(state) }
        }

        override fun setModalities(modalities: AlarmModalities) {
            synchronized(lock) { current?.setModalities(modalities) }
        }
    }

    /**
     * Bring the alarm up and hand back the port to drive it with — one call, so
     * there is no order in which a caller can start driving a speaker that was
     * never opened.
     */
    fun openOutput(): AlarmOutput {
        synchronized(lock) { acquireLocked() }
        return port
    }

    /** Play one step's signal for the settings screen's "проверить сигнал" button. */
    fun preview(level: Int) {
        synchronized(lock) { acquireLocked().preview(level) }
    }

    /**
     * Silence whatever is playing without tearing anything down. A no-op when no
     * alarm is live — nothing is sounding, so there is nothing to build in order
     * to quieten.
     */
    fun stop() {
        synchronized(lock) { current?.stop() }
    }

    /**
     * Tear the current alarm down — audio track, tone thread, focus, vibrator —
     * and forget it. A no-op when nothing was opened, which is the case when the
     * service stopped itself before ever starting the alarm.
     */
    fun release() {
        synchronized(lock) {
            val dying = current ?: return
            current = null
            // Inside the lock on purpose — see the class doc's threading note.
            dying.release()
        }
    }

    private fun acquireLocked(): AudibleAlarm =
        current ?: AudibleAlarm(appContext).also { current = it }
}

package ru.sodovaya.volty.domain.alert

/**
 * The rider's own escape from a sounding alarm: **suppress until it recovers**.
 *
 * ### What it is for
 *
 * F §14 records a hole nothing in Part F may close: on a vehicle with a BMS on
 * one link and a controller on another, a *controller* link that dies while the
 * BMS keeps delivering leaves `activeMotion` frozen on the last hot reading with
 * `isConnected = true`, and the vehicle-level state at
 * [ru.sodovaya.volty.domain.model.ConnectionState.Connected]. Both of
 * [AlarmDriver]'s stop mechanisms are defeated at once, so a rider who was at
 * 92 % duty when the link went keeps step 3 — 2.4 kHz, three pulses, ~2.2
 * bursts/s, on `USAGE_ALARM` so it plays through silent mode and DND — in their
 * pocket for the rest of the ride. The only escape before this class was the live
 * notification's `Disconnect` action, which ends the BLE session.
 *
 * The root cause is `VehicleConnection`'s split staleness sweep and is fixed
 * there. This is the survivability half: **something the rider can press that
 * stops the noise without ending the ride.**
 *
 * ### The semantics, decided: suppress until the level returns to 0
 *
 * Two readings of "silence" were on the table:
 *
 *  1. *silence this instant* — the next sample re-raises the alarm. Useless for
 *     the case that motivates the button: the frozen reading arrives again a
 *     fraction of a second later and the tone comes straight back. A button that
 *     does nothing in the one scenario it exists for is worse than no button,
 *     because the rider spends the ride pressing it;
 *  2. *suppress until the alarm recovers on its own, then re-arm* — [gate] returns
 *     [AlarmState.SILENT] for as long as the engine says anything is sounding, and
 *     the moment the engine itself comes back to level 0 the suppression lifts, so
 *     the **next** time a threshold is crossed the alarm sounds normally.
 *
 * **This class implements 2.** It is the only one that helps, and it is a
 * *silence*, not a disarm: nothing about the rider's thresholds or the engine's
 * hysteresis changes, and the very next fresh danger after a recovery is audible.
 *
 * ### The failure mode, stated rather than buried
 *
 * A suppression that outlives the danger is its own safety bug, and this one can:
 *
 *  - **the frozen-reading case it was built for is exactly the case where the
 *    level never returns to 0.** A duty number stuck at 92 % holds step 3 forever,
 *    so the suppression also lasts forever — for as long as the link stays dead.
 *    While it does, a *real* duty overrun on a controller that came back is
 *    audible only after one sample at level 0 has passed through [gate];
 *  - so the rider who silences a stale alarm and rides on is riding **without a
 *    duty alarm** until something clears it.
 *
 * Three things clear it, and they are why this is an acceptable trade rather than
 * a trap:
 *
 *  - the engine dropping to level 0 — which is what a recovered link, a cooled
 *    motor or a rider backing off produces on the first honest sample;
 *  - any link state other than `Connected`: [AlarmDriver] resets the controller
 *    there, and the resulting level-0 state re-arms this on its way to the
 *    speaker. So a dropout that the app *can* see re-arms the alarm;
 *  - the foreground service stopping — a new ride builds a new driver and a new
 *    silencer, so a silence never survives the ride it was pressed in.
 *
 * The alternative to accepting that window is an alarm the rider cannot stop
 * except by disconnecting, which is what F §14 describes and is worse: it ends
 * the telemetry link outright, and it teaches the rider that the way to deal with
 * this alarm is to kill it.
 *
 * ### Not thread-safe
 *
 * Same contract as [AlarmController], and for the same reason: [AlarmDriver]
 * drives both from its one collector. Do not call [silence] directly from a
 * broadcast receiver — go through [AlarmDriver.silence], which hands the request
 * to that collector.
 */
class AlarmSilencer {

    /** True while a rider's silence is in force. Visible for tests and for logging. */
    var isSilenced: Boolean = false
        private set

    /**
     * The rider pressed "Заглушить". Takes effect on the next [gate], which
     * [AlarmDriver] performs immediately.
     *
     * Pressing it while nothing is sounding is deliberately a no-op rather than
     * an arm-for-next-time: a pre-emptive mute is the master switch's job, and a
     * "silence" that swallowed a future alarm nobody had heard yet would be a
     * disarm wearing the wrong label.
     */
    fun silence() { isSilenced = true }

    /**
     * The engine's state as the speaker should hear it.
     *
     * Silent while a silence is in force and the engine is still sounding; the
     * state itself, and the suppression lifted, as soon as the engine reaches
     * level 0. Returns its argument unchanged when no silence is in force, so it
     * is safe to run every state through it.
     */
    fun gate(state: AlarmState): AlarmState {
        if (!isSilenced) return state
        if (!state.isSounding) {
            isSilenced = false
            return state
        }
        return AlarmState.SILENT
    }
}

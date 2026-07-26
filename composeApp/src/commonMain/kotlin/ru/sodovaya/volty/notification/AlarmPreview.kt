package ru.sodovaya.volty.notification

/**
 * The settings screen's one line to the speaker: play a step so the rider can
 * hear it (F §11 — "проверить сигнал").
 *
 * This exists because the alarm itself lives in `androidMain` behind
 * `AudibleAlarmHolder`, and the settings component lives in `commonMain`. It is
 * deliberately *narrower* than the alarm:
 *
 *  - **no `stop()`**. The platform preview ends itself on a timer, and a `stop()`
 *    reachable from the UI would let a screen being disposed silence a **real**
 *    alarm that had started while it was open — the exact failure
 *    `AlarmSignalPlanner.preview` refuses in the other direction;
 *  - **no instance to hold.** Implementations must route to whichever alarm is
 *    live at the moment of the call (that is what `AudibleAlarmHolder` is for);
 *    a component that captured an `AudibleAlarm` would go permanently, silently
 *    dead after the first service restart.
 *
 * A preview is refused outright while a live alarm is sounding — see
 * `AlarmSignalPlanner.preview`. Nothing here needs to know that; it is enforced
 * where the speaker is.
 */
interface AlarmPreview {
    /** Play [level]'s signal once, briefly. Levels are 1..[ru.sodovaya.volty.domain.alert.AlertRule.MAX_LEVELS]. */
    fun preview(level: Int)
}

package ru.sodovaya.volty.notification

/**
 * [AlarmPreview] routed through [AudibleAlarmHolder] — the only way the settings
 * screen is allowed to reach the speaker.
 *
 * Holds the *holder*, never an `AudibleAlarm`: `AudibleAlarm.release()` is
 * terminal and `MonitoringService.onDestroy` calls it every time the rider parks,
 * so an instance captured here would be silently dead from the second ride on.
 * The holder rebuilds after a release and this adapter therefore always talks to
 * whatever is live at the moment of the call.
 *
 * A separate class rather than a method on the holder because the holder is Task
 * 8's file and the settings screen is Task 9's need; nothing about the holder
 * changes to satisfy it.
 */
class HolderAlarmPreview(private val holder: AudibleAlarmHolder) : AlarmPreview {
    override fun preview(level: Int) = holder.preview(level)
}

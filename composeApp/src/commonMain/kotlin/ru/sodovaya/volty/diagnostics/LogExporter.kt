package ru.sodovaya.volty.diagnostics

/**
 * Collects the app's own runtime logs and hands them to the platform's share
 * mechanism so the user can send them (email, messenger, …) for support.
 *
 * Android reads its own logcat buffer; other platforms would supply their own
 * actual. Fire-and-forget: the actual does its own IO off the caller's thread
 * and opens the share sheet itself.
 */
expect class LogExporter {
    fun exportLogs()
}

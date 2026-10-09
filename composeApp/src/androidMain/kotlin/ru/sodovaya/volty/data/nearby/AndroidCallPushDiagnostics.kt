package ru.sodovaya.volty.data.nearby

import android.os.SystemClock
import android.util.Log

/** Process-local bounded trace. Accepts fixed enums, never push payloads or exception text. */
internal object AndroidCallPushDiagnostics {
    private val history = ArrayDeque<Pair<Long, CallPushDiagnosticEvent>>()
    @Synchronized fun record(event: CallPushDiagnosticEvent) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (history.size == 128) history.removeFirst()
            history.addLast(now to event)
            Log.i("VoltyCallPush", event.name)
        } catch (_: Exception) { /* Observability must not decide whether a call is accepted. */ }
    }
    @Synchronized fun export(): String = "trace_scope=PROCESS_LOCAL; native_post=SUBMISSION_ONLY\n" +
        history.joinToString("\n") { (at, event) -> "$at ${event.name}" }
}

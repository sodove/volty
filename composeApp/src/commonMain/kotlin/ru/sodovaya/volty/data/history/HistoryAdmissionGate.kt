package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.domain.history.HistoryTick

/** Nonblocking send reservation; only the control owner suspends when closing intake. */
internal class HistoryAdmissionGate(origin: HistoryTick) {
    private data class State(val open: Boolean, val pending: Int, val last: HistoryTick)
    private val state = MutableStateFlow(State(true, 0, origin))

    fun reserve(tick: HistoryTick): Boolean {
        while (true) {
            val current = state.value
            if (!current.open) return false
            check(current.pending < Int.MAX_VALUE)
            val updated = current.copy(pending = current.pending + 1,
                last = if (tick.elapsedMillis > current.last.elapsedMillis) tick else current.last)
            if (state.compareAndSet(current, updated)) return true
        }
    }

    fun release() {
        state.update { check(it.pending > 0); it.copy(pending = it.pending - 1) }
    }

    fun close(): HistoryTick {
        while (true) {
            val current = state.value
            if (!current.open) return current.last
            if (state.compareAndSet(current, current.copy(open = false))) return current.last
        }
    }

    suspend fun awaitIdle() { state.first { it.pending == 0 } }
}

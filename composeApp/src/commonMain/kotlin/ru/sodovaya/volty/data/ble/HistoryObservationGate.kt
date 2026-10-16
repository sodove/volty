package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/** Attempt-owned nonblocking dispatch reservations. Closing is irreversible and
 * waits only for callback issuance; the controller separately drains queued work.
 */
internal class HistoryObservationGate {
    private enum class Phase { PENDING, OPEN, CLOSED }
    private data class State(val phase: Phase = Phase.PENDING, val pending: Int = 0)
    private val state = MutableStateFlow(State())
    val isOpen: Boolean get() = state.value.phase == Phase.OPEN

    fun open(): Boolean {
        while (true) {
            val current = state.value
            when (current.phase) {
                Phase.CLOSED -> return false
                Phase.OPEN -> return true
                Phase.PENDING -> if (state.compareAndSet(current, current.copy(phase = Phase.OPEN))) return true
            }
        }
    }

    fun dispatch(action: () -> Unit): Boolean {
        while (true) {
            val current = state.value
            if (current.phase != Phase.OPEN) return false
            check(current.pending < Int.MAX_VALUE)
            if (state.compareAndSet(current, current.copy(pending = current.pending + 1))) break
        }
        try { action() }
        finally { state.update { current ->
            check(current.pending > 0)
            current.copy(pending = current.pending - 1)
        } }
        return true
    }

    suspend fun closeAndJoin() {
        state.update { it.copy(phase = Phase.CLOSED) }
        state.first { it.pending == 0 }
    }
}

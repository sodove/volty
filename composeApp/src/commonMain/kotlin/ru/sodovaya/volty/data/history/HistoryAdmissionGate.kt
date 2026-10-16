package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.domain.history.HistoryTick

/** Nonblocking send reservation; only the control owner suspends when closing intake. */
internal class HistoryAdmissionGate(origin: HistoryTick) {
    internal class SourceReservation(private val onRelease: () -> Unit) {
        private val released = MutableStateFlow(false)
        fun release() { if (released.compareAndSet(false, true)) onRelease() }
    }
    private data class State(val open: Boolean, val pending: Int, val last: HistoryTick,
        val pendingSources: Map<String, Int> = emptyMap(),
        val lastBySource: Map<String, HistoryTick> = emptyMap(),
        val closedSources: Set<String> = emptySet())
    private val state = MutableStateFlow(State(true, 0, origin))

    fun reserve(tick: HistoryTick): Boolean = reserveOperation(tick, emptySet())

    private fun reserveOperation(tick: HistoryTick, sourceIds: Set<String>, boundary: Boolean = false): Boolean {
        while (true) {
            val current = state.value
            if (!current.open || (!boundary && sourceIds.any { it in current.closedSources })) return false
            check(current.pending < Int.MAX_VALUE)
            val pending = current.pendingSources.toMutableMap()
            val lastBySource = current.lastBySource.toMutableMap()
            sourceIds.forEach { source ->
                pending[source] = (pending[source] ?: 0) + 1
                if (lastBySource[source]?.let { it.elapsedMillis >= tick.elapsedMillis } != true)
                    lastBySource[source] = tick
            }
            val updated = current.copy(pending = current.pending + 1,
                last = if (tick.elapsedMillis > current.last.elapsedMillis) tick else current.last,
                pendingSources = pending, lastBySource = lastBySource)
            if (state.compareAndSet(current, updated)) return true
        }
    }

    fun release() = releaseOperation(emptySet())

    private fun releaseOperation(sourceIds: Set<String>) {
        state.update { current ->
            check(current.pending > 0)
            val pending = current.pendingSources.toMutableMap()
            sourceIds.forEach { source ->
                val count = checkNotNull(pending[source])
                check(count > 0)
                if (count == 1) pending.remove(source) else pending[source] = count - 1
            }
            current.copy(pending = current.pending - 1, pendingSources = pending)
        }
    }

    fun close(): HistoryTick {
        while (true) {
            val current = state.value
            if (!current.open) return current.last
            if (state.compareAndSet(current, current.copy(open = false))) return current.last
        }
    }

    suspend fun awaitIdle() { state.first { it.pending == 0 } }

    fun reserveTelemetry(tick: HistoryTick, sourceIds: Set<String>): SourceReservation? {
        val snapshot = frozenSources(sourceIds)
        if (!reserveOperation(tick, snapshot)) return null
        return SourceReservation { releaseOperation(snapshot) }
    }

    /** Trusted control metadata can stamp the fenced source, without admitting numeric receipts. */
    fun reserveBoundary(tick: HistoryTick, sourceIds: Set<String>): SourceReservation? {
        val snapshot = frozenSources(sourceIds)
        if (!reserveOperation(tick, snapshot, boundary = true)) return null
        return SourceReservation { releaseOperation(snapshot) }
    }

    fun blockedSources(sourceIds: Set<String>): Set<String> =
        frozenSources(sourceIds).intersect(state.value.closedSources)

    suspend fun closeSources(sourceIds: Set<String>): HistoryTick? {
        val snapshot = frozenSources(sourceIds)
        state.update { it.copy(closedSources = it.closedSources + snapshot) }
        val drained = state.first { current -> snapshot.all { (current.pendingSources[it] ?: 0) == 0 } }
        return snapshot.mapNotNull { drained.lastBySource[it] }.maxByOrNull { it.elapsedMillis }
    }

    fun reopenSources(sourceIds: Set<String>): Boolean {
        val snapshot = frozenSources(sourceIds)
        while (true) {
            val current = state.value
            if (!current.open || snapshot.none { it in current.closedSources }) return false
            if (state.compareAndSet(current, current.copy(closedSources = current.closedSources - snapshot))) return true
        }
    }

    private fun frozenSources(sourceIds: Set<String>): Set<String> = sourceIds.toSet().also {
        require(it.isNotEmpty() && it.all(String::isNotBlank))
    }
}

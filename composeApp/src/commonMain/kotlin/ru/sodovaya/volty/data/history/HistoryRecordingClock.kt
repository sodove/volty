package ru.sodovaya.volty.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.history.HistoryTick

/** One app-process clock shared by BLE receipt, GPS admission and unfinished-ride reads.
 * Prepare before installing any new recording owner. Recovery must not run alongside a writer.
 * Platform elapsed time is time since boot, including sleep, rather than since process startup.
 */
internal class HistoryRecordingClock(
    private val prepareGeneration: suspend () -> Long,
    private val epochMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
) {
    private val preparation = Mutex()
    private val generation = MutableStateFlow<Long?>(null)

    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        preparation.withLock {
            currentCoroutineContext().ensureActive()
            if (generation.value != null) return@withLock
            val prepared = prepareGeneration()
            require(prepared >= 0L) { "Invalid recording clock generation" }
            currentCoroutineContext().ensureActive()
            generation.value = prepared
        }
    }

    fun readTick(): HistoryTick {
        val prepared = checkNotNull(generation.value) { "Recording clock not prepared" }
        return HistoryTick(epochMillis(), elapsedRealtimeMillis(), prepared)
    }
}

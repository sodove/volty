package ru.sodovaya.volty.data.history

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.history.HistoryTick

/** One app-process clock shared by BLE receipt, GPS admission and unfinished-ride reads.
 * Prepare before installing any new recording owner. Recovery must not run alongside a writer.
 * Platform elapsed time is time since boot, including sleep, rather than since process startup.
 * Preparation has a cooperative deadline, including mutex wait. Blocking native IO may still
 * finish later, but its expired acknowledgement cannot publish a clock or admit recording.
 */
internal class HistoryRecordingClock(
    private val prepareGeneration: suspend () -> Long,
    private val epochMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    private val prepareTimeoutMillis: Long = 10_000,
) {
    init { require(prepareTimeoutMillis > 0) }
    private val preparation = Mutex()
    private val generation = MutableStateFlow<Long?>(null)

    suspend fun prepare() {
        currentCoroutineContext().ensureActive()
        withTimeout(prepareTimeoutMillis) {
            preparation.withLock {
                currentCoroutineContext().ensureActive()
                if (generation.value != null) return@withLock
                val prepared = prepareGeneration()
                require(prepared >= 0L) { "Invalid recording clock generation" }
                currentCoroutineContext().ensureActive()
                generation.value = prepared
            }
        }
    }

    fun readTick(): HistoryTick {
        val prepared = checkNotNull(generation.value) { "Recording clock not prepared" }
        return HistoryTick(epochMillis(), elapsedRealtimeMillis(), prepared)
    }
}

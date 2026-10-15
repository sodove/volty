package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.domain.history.HistoryBatch

/** One accepted native queue reservation; consumer and discard race for one terminal action.
 * The captured owner cannot finish until its verdict is latched and this reservation is released.
 */
internal class QueuedHistoryTelemetry(
    private val onOffer: (HistoryBatch) -> HistoryOffer,
    private val onDiscard: () -> Unit,
    private val onRelease: () -> Unit,
) {
    private val completed = MutableStateFlow(false)
    fun offer(batch: HistoryBatch): HistoryOffer {
        if (!completed.compareAndSet(false, true)) return HistoryOffer.RETIRED
        try { return onOffer(batch) }
        finally { onRelease() }
    }
    fun discard(): Boolean {
        if (!completed.compareAndSet(false, true)) return false
        try { onDiscard() }
        finally { onRelease() }
        return true
    }
}

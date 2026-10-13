package ru.sodovaya.volty.domain.history

/** Holds at most first/min/max/last for one 500ms window. Unknowns are emitted explicitly. */
class HistoryDetailGate(private val origin: HistoryTick, private val key: HistorySeriesKey) {
    private data class Window(val slot: Long, val first: HistorySample, val minimum: HistorySample,
        val maximum: HistorySample, val last: HistorySample) {
        fun samples() = listOf(first, minimum, maximum, last).distinct().sortedBy { it.tick.elapsedMillis }
    }
    private var window: Window? = null
    private var last: HistoryTick? = null

    fun offer(sample: HistorySample): List<HistorySample> {
        require(sample.key == key && historyAxisEpochMillis(origin, sample.tick) != null)
        require(last == null || sample.tick.elapsedMillis >= checkNotNull(last).elapsedMillis)
        val old = window
        val slot = (sample.tick.elapsedMillis - origin.elapsedMillis) / HISTORY_DETAIL_BUCKET_MILLIS
        val boundary = last?.let { clockBoundary(it, sample.tick) } == true
        last = sample.tick
        if (sample.value == null) return flush() + sample
        if (old == null || old.slot != slot || boundary) {
            val emitted = flush()
            window = Window(slot, sample, sample, sample, sample)
            return emitted
        }
        window = old.copy(last = sample,
            minimum = if (sample.value < checkNotNull(old.minimum.value)) sample else old.minimum,
            maximum = if (sample.value > checkNotNull(old.maximum.value)) sample else old.maximum)
        return emptyList()
    }

    fun flush(): List<HistorySample> = window?.samples().orEmpty().also { window = null }
    fun fork(): HistoryDetailGate = HistoryDetailGate(origin, key).also { it.window = window; it.last = last }
}

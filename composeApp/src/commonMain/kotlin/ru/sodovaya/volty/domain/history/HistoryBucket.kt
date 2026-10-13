package ru.sodovaya.volty.domain.history

const val HISTORY_SUMMARY_BUCKET_MILLIS = 60000L
const val HISTORY_DETAIL_BUCKET_MILLIS = 500L

data class HistoryBucket(
    val key: HistorySeriesKey,
    val slot: Long,
    val first: HistoryTick,
    val last: HistoryTick,
    val minimum: HistorySample?,
    val maximum: HistorySample?,
    val energyWh: Double?,
    val sampleCount: Long,
    val complete: Boolean,
) {
    init {
        require(slot >= 0 && sampleCount > 0)
        require(first.clockGeneration == last.clockGeneration && first.elapsedMillis <= last.elapsedMillis)
        require((minimum == null) == (maximum == null))
        listOfNotNull(minimum, maximum).forEach {
            require(it.key == key && it.value != null && it.tick.clockGeneration == first.clockGeneration)
            require(it.tick.elapsedMillis in first.elapsedMillis..last.elapsedMillis)
        }
        if (minimum != null && maximum != null) require(checkNotNull(minimum.value) <= checkNotNull(maximum.value))
        require(energyWh == null || energyWh.isFinite())
        require(!complete || minimum != null)
    }
    val stats: HistoryStats get() = HistoryStats(minimum?.value, maximum?.value, energyWh, complete)
}

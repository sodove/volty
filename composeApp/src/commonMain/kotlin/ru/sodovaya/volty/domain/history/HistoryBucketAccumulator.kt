package ru.sodovaya.volty.domain.history

/** Constant memory per series; snapshots come from accepted samples, never from detail thinning. */
class HistoryBucketAccumulator(
    private val origin: HistoryTick,
    private val key: HistorySeriesKey,
    private val maxIntervalMillis: Long = 2000,
) {
    private data class State(
        val slot: Long,
        val first: HistoryTick,
        val last: HistorySample,
        val minimum: HistorySample?,
        val maximum: HistorySample?,
        val sampleCount: Long,
        val energy: Double = 0.0,
        val correction: Double = 0.0,
        val hasEnergy: Boolean = false,
        val numericFailure: Boolean = false,
        val complete: Boolean = true,
        val barrier: Boolean = false,
    )
    init { require(maxIntervalMillis > 0) }
    private var state: State? = null
    private val multiplier = HistoryMetricRegistry.descriptor(key.metricId)?.consumptionMultiplier

    fun offer(sample: HistorySample): List<HistoryBucket> {
        require(sample.key == key && historyAxisEpochMillis(origin, sample.tick) != null)
        val old = state
        require(old == null || sample.tick.elapsedMillis >= old.last.tick.elapsedMillis)
        val slot = (sample.tick.elapsedMillis - origin.elapsedMillis) / HISTORY_SUMMARY_BUCKET_MILLIS
        val pair = old?.let { historyStats(listOf(it.last, sample), emptyList(), multiplier, maxIntervalMillis) }
        val usable = pair?.complete == true && old?.barrier == false
        val changed = old != null && slot != old.slot
        val completed = if (changed) snapshot() else null
        val carry = old?.last?.takeIf { changed && usable }
        val base = if (old == null || changed) {
            State(slot, carry?.tick ?: sample.tick, sample, carry, carry, 0,
                complete = old == null || usable)
        } else old
        val minimum = sample.takeIf { it.value != null && (base.minimum == null || checkNotNull(it.value) < checkNotNull(base.minimum.value)) } ?: base.minimum
        val maximum = sample.takeIf { it.value != null && (base.maximum == null || checkNotNull(it.value) > checkNotNull(base.maximum.value)) } ?: base.maximum
        var updated = base.copy(last = sample, minimum = minimum, maximum = maximum,
            sampleCount = base.sampleCount + 1, complete = base.complete && sample.value != null && (old == null || usable), barrier = false)
        val wh = pair?.energyWh?.takeIf { usable }
        if (wh != null) {
            val adjusted = wh - updated.correction
            val next = updated.energy + adjusted
            updated = if (next.isFinite()) updated.copy(energy = next,
                correction = (next - updated.energy) - adjusted, hasEnergy = true)
            else updated.copy(numericFailure = true, complete = false)
        } else if (old != null && !old.barrier && multiplier != null &&
            old.last.value != null && sample.value != null &&
            !clockBoundary(old.last.tick, sample.tick) &&
            sample.tick.elapsedMillis - old.last.tick.elapsedMillis in 1..maxIntervalMillis) {
            // Earlier logic checked `usable`, which excludes the numerical failure itself.
            // Keep unknown energy sticky in this bucket even after a later usable interval.
            updated = updated.copy(numericFailure = true, complete = false)
        }
        state = updated
        return listOfNotNull(completed, snapshot())
    }

    fun mark(marker: HistoryMarker): HistoryBucket? {
        require(historyAxisEpochMillis(origin, marker.tick) != null)
        val old = state ?: return null
        val breaks = when (marker.kind) {
            HistoryMarkerKind.BLE_GAP, HistoryMarkerKind.CLOCK_CHANGE,
            HistoryMarkerKind.OVERFLOW, HistoryMarkerKind.INTERRUPTED -> true
            HistoryMarkerKind.CONNECTED -> marker.tick.elapsedMillis > old.first.elapsedMillis
            else -> false
        }
        if (!breaks) return null
        state = old.copy(complete = false, barrier = true)
        return snapshot()
    }

    fun snapshot(): HistoryBucket? = state?.let {
        HistoryBucket(key, it.slot, it.first, it.last.tick, it.minimum, it.maximum,
            it.energy.takeIf { _ -> it.hasEnergy && !it.numericFailure }, it.sampleCount, it.complete)
    }

    /** An IO owner advances the fork only after its snapshot transaction is acknowledged. */
    fun fork(): HistoryBucketAccumulator = HistoryBucketAccumulator(origin, key, maxIntervalMillis).also { it.state = state }
}

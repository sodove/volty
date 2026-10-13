package ru.sodovaya.volty.domain.history

/** Must receive the accepted full stream, before detail/overview thinning. Values remain canonical. */
fun historyStats(
    samples: List<HistorySample>,
    markers: List<HistoryMarker>,
    consumptionMultiplier: Double? = null,
    maxIntervalMillis: Long = 2000,
): HistoryStats {
    requireSingleHistoryKey(samples)
    require(consumptionMultiplier == null || consumptionMultiplier.isFinite())
    if (consumptionMultiplier != null && samples.isNotEmpty()) {
        require(HistoryMetricRegistry.descriptor(samples.first().key.metricId)?.unit == HistoryUnit.WATT)
    }
    val continuity = HistoryContinuityIndex(markers, maxIntervalMillis)
    val known = samples.mapNotNull { it.value }
    if (known.isEmpty()) return HistoryStats(null, null, null, false)
    var complete = known.size == samples.size
    var energy = 0.0
    var correction = 0.0
    var intervals = 0
    var numericFailure = false
    samples.zipWithNext().forEach { (a, b) ->
        if (continuity.breakTick(a, b) != null) {
            complete = false
        } else if (consumptionMultiplier != null && b.tick.elapsedMillis > a.tick.elapsedMillis) {
            // Dividing each endpoint first avoids overflowing (a+b) for finite high readings.
            val average = checkNotNull(a.value) / 2 + checkNotNull(b.value) / 2
            val weight = consumptionMultiplier * ((b.tick.elapsedMillis - a.tick.elapsedMillis).toDouble() / 3600000)
            val wh = average * weight
            val adjusted = wh - correction
            val next = energy + adjusted
            if (wh.isFinite() && next.isFinite()) {
                correction = (next - energy) - adjusted
                energy = next
                intervals++
            } else {
                numericFailure = true
                complete = false
            }
        }
    }
    return HistoryStats(known.minOrNull(), known.maxOrNull(),
        energy.takeIf { intervals > 0 && !numericFailure }, complete)
}

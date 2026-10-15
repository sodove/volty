package ru.sodovaya.volty.domain.history

/** Bounded projection of ordered v2 read pages; statistics remain a separate store query. */
class HistoryOverviewAccumulator(markers: List<HistoryMarker>, private val maxPoints: Int = 600) {
    init { require(maxPoints in 3..20000) }
    private val continuity = HistoryContinuityIndex(markers)
    private var points: List<HistorySample> = emptyList()
    private var previousRaw: HistorySample? = null

    fun offerPage(samples: List<HistorySample>) {
        requireSingleHistoryKey(samples)
        if (samples.isEmpty()) return
        require(previousRaw?.let { it.key == samples.first().key } != false)
        val segmented = buildList {
            var previous = previousRaw
            // Dense-boundary compaction may end at the last known point and
            // omit a trailing unknown. Carry the real raw boundary forward;
            // an envelope endpoint is not evidence that acquisition resumed.
            previous?.takeIf { it.unknown != null && points.lastOrNull() != it }?.let(::add)
            for (sample in samples) {
                val prior = previous
                if (prior?.value != null && sample.value != null) {
                    continuity.breakTick(prior, sample)?.let {
                        add(HistorySample(it, sample.key, null, HistoryUnknownReason.GAP))
                    }
                }
                add(sample)
                previous = sample
            }
        }
        val projected = projectHistoryOverviewRows(points + segmented, maxPoints)
        points = projected
        previousRaw = samples.last()
    }

    fun snapshot(): List<HistorySample> = points.toList()
}

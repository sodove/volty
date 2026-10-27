package ru.sodovaya.volty.domain.history

/**
 * Bounded projection of ordered v2 read pages; statistics remain a separate store query.
 *
 * Controller-power rides recorded before the Begode 0x00 fix carry an artefact unknown between every pair of values;
 * [HistoryPartialSourceBridge] removes exactly those before segmentation (see its KDoc for scope and risk).
 */
class HistoryOverviewAccumulator(markers: List<HistoryMarker>, private val maxPoints: Int = 600) {
    init { require(maxPoints in 3..20000) }
    private val continuity = HistoryContinuityIndex(markers)
    private var points: List<HistorySample> = emptyList()
    private var previousRaw: HistorySample? = null
    private var bridge: HistoryPartialSourceBridge? = null

    fun offerPage(samples: List<HistorySample>) {
        requireSingleHistoryKey(samples)
        if (samples.isEmpty()) return
        require(previousRaw?.let { it.key == samples.first().key } != false)
        val active = bridge ?: HistoryPartialSourceBridge(samples.first().key).also { bridge = it }
        ingest(samples.flatMap(active::offer))
    }

    private fun ingest(samples: List<HistorySample>) {
        if (samples.isEmpty()) return
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

    /** Unknowns still held at the end of the stream have no later value to bracket them, so they are real. */
    fun snapshot(): List<HistorySample> {
        val tail = bridge?.pendingTail().orEmpty()
        if (tail.isEmpty()) return points.toList()
        val savedPoints = points
        val savedRaw = previousRaw
        ingest(tail)
        return points.toList().also { points = savedPoints; previousRaw = savedRaw }
    }
}

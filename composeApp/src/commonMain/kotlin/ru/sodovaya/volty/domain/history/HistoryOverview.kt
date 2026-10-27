package ru.sodovaya.volty.domain.history

/**
 * Min/max envelope with gaps split first. This view never supplies exact ride statistics. Recorded controller-power
 * artefact unknowns are bridged first ([HistoryPartialSourceBridge]); every other unknown still splits.
 */
fun historyOverview(rawSamples: List<HistorySample>, markers: List<HistoryMarker>, maxPoints: Int = 600): List<HistorySample> {
    require(maxPoints in 3..20000)
    requireSingleHistoryKey(rawSamples)
    val samples = bridgePartialSourceArtefact(rawSamples)
    if (samples.isEmpty()) return emptyList()
    val continuity = HistoryContinuityIndex(markers)
    val rows = buildList {
        samples.forEachIndexed { index, sample ->
            val previous = samples.getOrNull(index - 1)
            if (previous?.value != null && sample.value != null) {
                continuity.breakTick(previous, sample)?.let {
                    add(HistorySample(it, sample.key, null, HistoryUnknownReason.GAP))
                }
            }
            add(sample)
        }
    }
    return projectHistoryOverviewRows(rows, maxPoints)
}

/** Input already contains acquisition boundaries; sparse retained points cannot imply new gaps. */
internal fun projectHistoryOverviewRows(rows: List<HistorySample>, maxPoints: Int): List<HistorySample> {
    require(maxPoints in 3..20000)
    requireSingleHistoryKey(rows)
    if (rows.size <= maxPoints) return rows
    val knownIndices = rows.indices.filter { rows[it].value != null }
    val minimum = knownIndices.minByOrNull { checkNotNull(rows[it].value) }
    val maximum = knownIndices.maxByOrNull { checkNotNull(rows[it].value) }
    val mandatory = mutableSetOf(0, rows.lastIndex)
    rows.forEachIndexed { index, sample ->
        if (sample.unknown != null || rows.getOrNull(index - 1)?.unknown != null || rows.getOrNull(index + 1)?.unknown != null) mandatory += index
    }
    minimum?.let(mandatory::add); maximum?.let(mandatory::add)
    if (mandatory.size > maxPoints) {
        // More boundaries than the budget can represent. Show isolated earned extrema/endpoints,
        // separated by explicit unknowns, rather than joining across omitted segments.
        val chosen = listOfNotNull(minimum, maximum, knownIndices.firstOrNull(), knownIndices.lastOrNull())
            .distinct().take((maxPoints + 1) / 2).sorted()
        if (chosen.isEmpty()) return listOf(rows.first(), rows.last())
        return buildList {
            chosen.forEachIndexed { index, row ->
                if (index > 0) add(HistorySample(rows[row].tick, rows[row].key, null, HistoryUnknownReason.GAP))
                add(rows[row])
            }
        }
    }
    val selected = mandatory.toMutableSet()
    val buckets = (maxPoints - mandatory.size) / 2
    if (buckets > 0) {
        repeat(buckets) { bucket ->
            val start = bucket * rows.size / buckets
            val end = (bucket + 1) * rows.size / buckets
            val candidates = (start until end).filter { rows[it].value != null && it !in mandatory }
            candidates.minByOrNull { checkNotNull(rows[it].value) }?.let(selected::add)
            candidates.maxByOrNull { checkNotNull(rows[it].value) }?.let(selected::add)
        }
    }
    return selected.sorted().map(rows::get)
}

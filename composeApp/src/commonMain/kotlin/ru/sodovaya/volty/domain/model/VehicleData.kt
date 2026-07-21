package ru.sodovaya.volty.domain.model

/**
 * What the UI observes for the active vehicle.
 *
 * [aggregate] is derived from [packs] by
 * [ru.sodovaya.volty.domain.stats.PackAggregator] — never stored, never
 * written to by anything but the aggregator.
 */
data class VehicleData(
    val packs: List<PackState> = emptyList(),
    val aggregate: BmsData = BmsData(),
    val topology: PackTopology = PackTopology.PARALLEL,
    /** true when some packs are offline and [aggregate] covers only the rest. */
    val isPartial: Boolean = false
)

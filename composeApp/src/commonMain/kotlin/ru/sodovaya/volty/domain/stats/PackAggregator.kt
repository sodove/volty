package ru.sodovaya.volty.domain.stats

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Derives a vehicle-level [BmsData] from its packs. Pure — no BLE, no clock
 * reads beyond the fallback timestamp, no state. All multi-pack maths lives
 * here so it can be tested without a radio.
 *
 * [BmsData.cellVoltages] is the union of all ONLINE packs' cells, in pack
 * order, under both topologies (mirroring [BmsData.temperatures]). The union
 * exists so alert thresholds see every cell of the vehicle — the alert engine
 * only compares min/max/spread, so a cell over or under limit in any pack
 * still fires. Positional per-cell display (the dashboard cell grid, where
 * "cell #14" must name a physical cell) reads [PackState.data.cellVoltages]
 * per pack instead, never the concatenated aggregate.
 */
@OptIn(ExperimentalTime::class)
object PackAggregator {

    fun build(packs: List<PackState>, topology: PackTopology): VehicleData {
        val collapsed = collapseAliases(packs)
        return VehicleData(
            packs = collapsed,
            aggregate = aggregate(collapsed, topology),
            topology = topology,
            isPartial = collapsed.isNotEmpty() && collapsed.any { !it.isOnline }
        )
    }

    fun aggregate(packs: List<PackState>, topology: PackTopology): BmsData {
        val collapsed = collapseAliases(packs)
        val online = collapsed.filter { it.isOnline }
        if (online.isEmpty()) return BmsData(isConnected = false)

        val data = online.map { it.data }
        // SoC is an evidence-bearing measurement: a value with socKnown =
        // false must not participate in an average or become a series
        // string's weakest link. Charge and capacity have no corresponding
        // knownness flag, so their physical folds still use every online pack.
        val knownSoc = data.filter { it.socKnown }
        val measuredCurrent = data.measuring { it.hasCurrent }
        val measuredPower = data.measuring { it.hasPower }
        val labelled = online.size > 1

        val voltage = when (topology) {
            PackTopology.PARALLEL -> data.map { it.voltage }.average().toFloat()
            PackTopology.SERIES -> data.sumOf { it.voltage.toDouble() }.toFloat()
        }
        val current = when (topology) {
            PackTopology.PARALLEL -> measuredCurrent.sumOf { it.current.toDouble() }.toFloat()
            PackTopology.SERIES -> measuredCurrent.map { it.current }.average().toFloat()
        }
        val hasCurrent = when (topology) {
            PackTopology.PARALLEL -> data.all { it.hasCurrent }
            PackTopology.SERIES -> data.any { it.hasCurrent }
        }
        // Total power is the sum of pack powers under BOTH topologies:
        // parallel  P = V * SUM(I) = SUM(P_i)
        // series    P = SUM(V_i) * I = SUM(P_i)
        val power = measuredPower.sumOf { it.power.toDouble() }.toFloat()
        val hasPower = data.all { it.hasPower }

        val charge: Float
        val capacity: Float
        val soc: Float
        when (topology) {
            PackTopology.PARALLEL -> {
                charge = data.sumOf { it.charge.toDouble() }.toFloat()
                capacity = data.sumOf { it.capacity.toDouble() }.toFloat()
                // Parallel branches contribute energy together, so their
                // known SoC values are capacity-weighted. Falls back to a
                // plain mean when none of the measured branches reports
                // capacity.
                // Widen BEFORE multiplying: a Float x Float product rounds to
                // Float precision first, breaking the single-pack identity
                // (e.g. 19f x 13.6f aggregates to 18.999998f, truncating to
                // 18% and tripping the low-SoC alert a point early).
                val knownCapacity = knownSoc.sumOf { it.capacity.toDouble() }
                soc = when {
                    knownSoc.isEmpty() -> 0f
                    knownCapacity > 0.0 ->
                        (knownSoc.sumOf { it.soc.toDouble() * it.capacity.toDouble() } / knownCapacity).toFloat()
                    else -> knownSoc.map { it.soc }.average().toFloat()
                }
            }
            PackTopology.SERIES -> {
                // A series string can only deliver as much as its weakest
                // measured link. An unknown SoC is not evidence of an empty
                // pack, so it cannot become the minimum.
                charge = data.minOf { it.charge }
                capacity = data.minOf { it.capacity }
                soc = knownSoc.minOfOrNull { it.soc } ?: 0f
            }
        }

        val cycleAh = when (topology) {
            PackTopology.PARALLEL -> data.sumOf { it.cycleCapacityAh.toDouble() }.toFloat()
            PackTopology.SERIES -> data.maxOf { it.cycleCapacityAh }
        }

        return BmsData(
            voltage = voltage,
            current = current,
            hasCurrent = hasCurrent,
            power = power,
            hasPower = hasPower,
            soc = soc,
            // One measured branch is enough to report SoC; unknown branches
            // were excluded above. All-unknown remains explicitly unknown.
            socKnown = knownSoc.isNotEmpty(),
            charge = charge,
            capacity = capacity,
            numCycles = data.maxOf { it.numCycles },
            cycleCapacityAh = cycleAh,
            cellVoltages = online.flatMap { it.data.cellVoltages },
            temperatures = online.flatMap { it.data.temperatures },
            chargeEnabled = data.all { it.chargeEnabled },
            dischargeEnabled = data.all { it.dischargeEnabled },
            bmsFaults = online.flatMap { p ->
                p.data.bmsFaults.map { if (labelled) "${p.pack.label}: $it" else it }
            },
            // In series a missing pack makes the aggregate physically wrong,
            // so the vehicle only counts as connected when every pack is up.
            isConnected = when (topology) {
                PackTopology.PARALLEL -> true
                PackTopology.SERIES -> online.size == collapsed.size
            },
            timestamp = data.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }

    /**
     * Collapses each `aliasGroup` (the same physical battery reached over two
     * paths — e.g. a direct ANT link and a gateway-hosted VESC-BMS bridge) to
     * a single representative, so alternate paths never double-count in the
     * aggregate. `null`-group packs (the common, non-aliased case) are always
     * kept as-is.
     *
     * Within a group: prefer an online member, lowest pack index first, so
     * the result is deterministic when several paths are up at once. If the
     * whole group is offline, still keep its lowest-index member — dropping
     * it entirely would make the battery vanish from the dashboard instead of
     * showing it as offline.
     */
    private fun collapseAliases(packs: List<PackState>): List<PackState> {
        val grouped = packs.groupBy { it.pack.aliasGroup }
        val result = ArrayList<PackState>(packs.size)
        for ((alias, members) in grouped) {
            if (alias == null) {
                result += members
                continue
            }
            val online = members.filter { it.isOnline }
            val chosen = (if (online.isNotEmpty()) online else members)
                .minByOrNull { it.pack.index }!!
            result += chosen
        }
        return result.sortedBy { it.pack.index }
    }

    /** Keep the one-pack identity when no contributor carries a measurement. */
    private fun List<BmsData>.measuring(knows: (BmsData) -> Boolean): List<BmsData> =
        filter(knows).ifEmpty { this }
}

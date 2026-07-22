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

    fun build(packs: List<PackState>, topology: PackTopology): VehicleData = VehicleData(
        packs = packs,
        aggregate = aggregate(packs, topology),
        topology = topology,
        isPartial = packs.isNotEmpty() && packs.any { !it.isOnline }
    )

    fun aggregate(packs: List<PackState>, topology: PackTopology): BmsData {
        val online = packs.filter { it.isOnline }
        if (online.isEmpty()) return BmsData(isConnected = false)

        val data = online.map { it.data }
        val labelled = online.size > 1

        val voltage = when (topology) {
            PackTopology.PARALLEL -> data.map { it.voltage }.average().toFloat()
            PackTopology.SERIES -> data.sumOf { it.voltage.toDouble() }.toFloat()
        }
        val current = when (topology) {
            PackTopology.PARALLEL -> data.sumOf { it.current.toDouble() }.toFloat()
            PackTopology.SERIES -> data.map { it.current }.average().toFloat()
        }
        // Total power is the sum of pack powers under BOTH topologies:
        // parallel  P = V * SUM(I) = SUM(P_i)
        // series    P = SUM(V_i) * I = SUM(P_i)
        val power = data.sumOf { it.power.toDouble() }.toFloat()

        val charge: Float
        val capacity: Float
        val soc: Float
        when (topology) {
            PackTopology.PARALLEL -> {
                charge = data.sumOf { it.charge.toDouble() }.toFloat()
                capacity = data.sumOf { it.capacity.toDouble() }.toFloat()
                // Capacity-weighted average of the packs' reported SoC —
                // reduces to the identity for a single pack. Falls back to
                // the plain mean when no pack reports capacity.
                // Widen BEFORE multiplying: a Float x Float product rounds to
                // Float precision first, breaking the single-pack identity
                // (e.g. 19f x 13.6f aggregates to 18.999998f, truncating to
                // 18% and tripping the low-SoC alert a point early).
                soc = if (capacity > 0f)
                    (data.sumOf { it.soc.toDouble() * it.capacity.toDouble() } / capacity).toFloat()
                else data.map { it.soc }.average().toFloat()
            }
            PackTopology.SERIES -> {
                // A series string can only deliver as much as its weakest link.
                charge = data.minOf { it.charge }
                capacity = data.minOf { it.capacity }
                soc = data.minOf { it.soc }
            }
        }

        val cycleAh = when (topology) {
            PackTopology.PARALLEL -> data.sumOf { it.cycleCapacityAh.toDouble() }.toFloat()
            PackTopology.SERIES -> data.maxOf { it.cycleCapacityAh }
        }

        return BmsData(
            voltage = voltage,
            current = current,
            power = power,
            soc = soc,
            // The aggregate SoC is only meaningful if every ONLINE pack's SoC
            // was: an unknown pack contributes its placeholder 0 to the
            // parallel weighted average and always wins the series minOf, so
            // a single unknown pack pollutes the number under both
            // topologies. In practice this is all-or-nothing — every
            // existing setup is all-known, a dumb Begode is its lone
            // unknown pack.
            socKnown = data.all { it.socKnown },
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
                PackTopology.SERIES -> online.size == packs.size
            },
            timestamp = data.maxOfOrNull { it.timestamp } ?: Clock.System.now()
        )
    }
}

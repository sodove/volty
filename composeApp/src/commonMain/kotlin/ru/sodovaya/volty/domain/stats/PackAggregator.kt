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
 * Deliberately NOT aggregated: [BmsData.cellVoltages]. Concatenating cells
 * across packs would make "worst cell #14" point at an index that exists in
 * neither pack. Per-cell data is read from [PackState.data] instead.
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
                soc = if (capacity > 0f)
                    (data.sumOf { (it.soc * it.capacity).toDouble() } / capacity).toFloat()
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
            charge = charge,
            capacity = capacity,
            numCycles = data.maxOf { it.numCycles },
            cycleCapacityAh = cycleAh,
            cellVoltages = emptyList(),
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

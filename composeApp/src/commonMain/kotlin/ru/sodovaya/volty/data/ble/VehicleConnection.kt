package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Holds the live state of every pack of one vehicle and derives the
 * vehicle-level view from it.
 *
 * Deliberately synchronous and free of coroutines — and NOT thread-safe:
 * the backing pack list is a plain unguarded MutableList, and there is no
 * internal funnel or queue. This is safe today only because exactly one
 * [ConnectionSession] exists at a time and its single observe coroutine is
 * the only caller of [submit] / [markOffline] / [markOnline]; the repository
 * tears the previous session down (cancelAndJoin) before installing a new
 * orchestrator, so calls never overlap. Anything that introduces a second
 * concurrent caller — e.g. a second BLE link with its own session
 * coroutine — MUST serialise samples (single consumer channel or
 * equivalent) before they reach this class, rather than adding locks here.
 */
@OptIn(ExperimentalTime::class)
internal class VehicleConnection(
    packs: List<Pack>,
    private val topology: PackTopology,
    private val onVehicleData: (VehicleData) -> Unit
) {

    private val states: MutableList<PackState> = packs
        .sortedBy { it.index }
        .map { PackState(pack = it, data = BmsData(), isOnline = false) }
        .toMutableList()

    /**
     * Feed a freshly parsed sample for one pack and return the resulting
     * vehicle snapshot — the same instance that was just pushed through
     * [onVehicleData] — so callers do not rebuild the aggregate a second
     * time. Unknown indices leave the state untouched and emit nothing; the
     * returned snapshot then simply reflects the unchanged state.
     */
    fun submit(packIndex: Int, data: BmsData): VehicleData {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0) return snapshot()
        states[slot] = states[slot].copy(
            data = data,
            isOnline = true,
            lastSeenAt = Clock.System.now()
        )
        val snap = snapshot()
        onVehicleData(snap)
        return snap
    }

    /**
     * Mark a pack as no longer reporting. Its last data is kept so the UI can
     * grey it out with the values it had, rather than blanking the card.
     */
    fun markOffline(packIndex: Int) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0 || !states[slot].isOnline) return
        states[slot] = states[slot].copy(isOnline = false)
        emit()
    }

    fun markOnline(packIndex: Int) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0 || states[slot].isOnline) return
        states[slot] = states[slot].copy(isOnline = true)
        emit()
    }

    fun snapshot(): VehicleData = PackAggregator.build(states.toList(), topology)

    private fun emit() = onVehicleData(snapshot())
}

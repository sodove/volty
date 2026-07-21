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
 * Deliberately synchronous and free of coroutines: samples arrive from
 * several [ConnectionSession] coroutines at once, so the repository funnels
 * them through a single consumer and calls in here from that one place. That
 * keeps the shared state single-threaded by construction instead of by lock.
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

    /** Feed a freshly parsed sample for one pack. Unknown indices are ignored. */
    fun submit(packIndex: Int, data: BmsData) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0) return
        states[slot] = states[slot].copy(
            data = data,
            isOnline = true,
            lastSeenAt = Clock.System.now()
        )
        emit()
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

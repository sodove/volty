package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.PackAggregator
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

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
    private val onVehicleData: (VehicleData) -> Unit,
    /**
     * Injected so tests can drive staleness with a controllable time source
     * (same pattern as [ru.sodovaya.volty.domain.usecase.AlertEngine]).
     */
    private val clock: () -> Instant = { Clock.System.now() }
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
     *
     * Every submit doubles as the liveness check for the OTHER packs: any
     * pack whose last sample is older than [BleConfig.packOfflineAfterMs] is
     * marked offline in the same pass, so its stale current and charge stop
     * feeding the aggregate. Piggybacking on the sample rate keeps this class
     * free of timers and coroutines; the one case a submit can't see — the
     * whole link going quiet — is already handled by [ConnectionSession]'s
     * stale-sample watchdog, which tears the connection down.
     *
     * Liveness here keys on "the protocol produced NEW data for this pack"
     * (enforced upstream by [PackSampleGate]), not on any specific frame
     * arriving. Known gap: a Begode branch whose `0x01` telemetry stops while
     * its cell frames keep coming still mints fresh [BmsData] instances
     * (`parseCells` → `rebuild`) carrying a frozen current, so this sweep
     * never fires for it and the aggregate keeps counting current the branch
     * is not delivering. Whether real firmware ever behaves that way is
     * unobserved — if the balancing board cuts a branch, its BMS most likely
     * goes silent entirely — so the gap is documented (see the open question
     * in the multi-pack spec), not guessed at with machinery.
     */
    fun submit(packIndex: Int, data: BmsData): VehicleData {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0) return snapshot()
        val now = clock()
        states[slot] = states[slot].copy(
            data = data,
            isOnline = true,
            lastSeenAt = now
        )
        // Sweep the other packs for staleness. Folded into this submit's
        // single emission — no extra onVehicleData call per marked pack.
        for (i in states.indices) {
            if (i == slot) continue
            val other = states[i]
            if (!other.isOnline) continue
            val seenAt = other.lastSeenAt ?: continue
            if ((now - seenAt).inWholeMilliseconds > BleConfig.packOfflineAfterMs) {
                // Keep the last data: the UI greys the pack out with the
                // values it had rather than blanking the card.
                states[i] = other.copy(isOnline = false)
            }
        }
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
        // Refresh the timestamp too: revived with its old lastSeenAt, the
        // pack would be re-marked offline by the very next submit's sweep.
        states[slot] = states[slot].copy(isOnline = true, lastSeenAt = clock())
        emit()
    }

    fun snapshot(): VehicleData = PackAggregator.build(states.toList(), topology)

    private fun emit() = onVehicleData(snapshot())
}

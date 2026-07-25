package ru.sodovaya.volty.data.ble

import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.Controller
import ru.sodovaya.volty.domain.model.ControllerData
import ru.sodovaya.volty.domain.model.ControllerState
import ru.sodovaya.volty.domain.model.Pack
import ru.sodovaya.volty.domain.model.PackState
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SectionState
import ru.sodovaya.volty.domain.model.VehicleData
import ru.sodovaya.volty.domain.stats.MotionAggregator
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
    /**
     * Slots the PROTOCOL says may exist but [packs] (the stored profile) does
     * not know about. A latent slot is invisible — absent from every snapshot
     * — until its first sample, and a full citizen from then on.
     *
     * This is what keeps a Begode without a smart BMS honest: the protocol
     * always reports packCount = 2 (it cannot know before the frames arrive),
     * but a dumb wheel only ever produces data for pack 0. Publishing the
     * second slot eagerly would pin a permanently-offline phantom "Pack 2"
     * card and a permanent isPartial on every such wheel; keeping it latent
     * means the configuration follows the stream (see the multi-pack spec,
     * "Откуда берутся пакеты"). A stored pack is never latent — user
     * configuration may legitimately be offline and must stay visible.
     */
    latentPacks: List<Pack> = emptyList(),
    /**
     * Stored controllers of this vehicle, mirroring [packs] on the battery
     * side. Defaults empty so existing single-side (battery-only)
     * construction sites keep compiling unchanged.
     */
    controllers: List<Controller> = emptyList(),
    /**
     * Latent controller slots, mirroring [latentPacks]: invisible until
     * their first [submitMotion] sample, a full citizen from then on.
     */
    latentControllers: List<Controller> = emptyList(),
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
     * Latent slots not yet materialised. A stored index always wins over a
     * latent duplicate: [submit] matches slots by index, and a duplicate
     * would leave one of them permanently unreachable. Same thread-safety
     * terms as [states] — mutated only from the session's single funnel.
     */
    private val latent: MutableList<Pack> = latentPacks
        .filter { lp -> packs.none { it.index == lp.index } }
        .toMutableList()

    /** Mirrors [states] on the motion side. */
    private val ctrlStates: MutableList<ControllerState> = controllers
        .sortedBy { it.index }
        .map { ControllerState(controller = it, data = ControllerData(), isOnline = false) }
        .toMutableList()

    /** Mirrors [latent] on the motion side. */
    private val latentCtrl: MutableList<Controller> = latentControllers
        .filter { lc -> controllers.none { it.index == lc.index } }
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
     *
     * [sections] is the protocol's physical-assembly breakdown for this pack,
     * read at the same moment as the sample (see `routePackSamples`), so the
     * two always describe one decode state. It overwrites unconditionally:
     * the breakdown is per-sample truth, and keeping a stale one after the
     * protocol stopped vouching for it (a reset, a reconnect) would pin old
     * assembly voltages next to fresh cells.
     */
    fun submit(
        packIndex: Int,
        data: BmsData,
        sections: List<SectionState> = emptyList()
    ): VehicleData {
        var slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0) {
            slot = materialiseLatent(packIndex)
            if (slot < 0) return snapshot()
        }
        val now = clock()
        states[slot] = states[slot].copy(
            data = data,
            sections = sections,
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
     * Promote the latent slot with [packIndex] into [states], keeping index
     * order, and return its position — or -1 when no such latent slot exists
     * (then the index is genuinely unknown and the sample is dropped, as
     * before). Called only from [submit], so materialisation and the sample
     * that caused it are one state change and one emission.
     */
    private fun materialiseLatent(packIndex: Int): Int {
        val li = latent.indexOfFirst { it.index == packIndex }
        if (li < 0) return -1
        val pack = latent.removeAt(li)
        states.add(PackState(pack = pack, data = BmsData(), isOnline = false))
        states.sortBy { it.pack.index }
        return states.indexOfFirst { it.pack.index == packIndex }
    }

    /**
     * Feed a freshly parsed sample for one controller and return the
     * resulting vehicle snapshot, mirroring [submit] on the motion side:
     * same slot-find / materialise / update-with-[now] / staleness-sweep /
     * snapshot-and-emit flow, staleness measured against the same
     * [BleConfig.packOfflineAfterMs] threshold.
     */
    fun submitMotion(controllerIndex: Int, data: ControllerData): VehicleData {
        var slot = ctrlStates.indexOfFirst { it.controller.index == controllerIndex }
        if (slot < 0) {
            slot = materialiseLatentController(controllerIndex)
            if (slot < 0) return snapshot()
        }
        val now = clock()
        ctrlStates[slot] = ctrlStates[slot].copy(
            data = data,
            isOnline = true,
            lastSeenAt = now
        )
        // Sweep the other controllers for staleness, folded into this
        // submit's single emission — mirrors submit's pack sweep.
        for (i in ctrlStates.indices) {
            if (i == slot) continue
            val other = ctrlStates[i]
            if (!other.isOnline) continue
            val seenAt = other.lastSeenAt ?: continue
            if ((now - seenAt).inWholeMilliseconds > BleConfig.packOfflineAfterMs) {
                ctrlStates[i] = other.copy(isOnline = false)
            }
        }
        val snap = snapshot()
        onVehicleData(snap)
        return snap
    }

    /**
     * Promote the latent slot with [controllerIndex] into [ctrlStates],
     * keeping index order, and return its position — or -1 when no such
     * latent slot exists. Mirrors [materialiseLatent].
     */
    private fun materialiseLatentController(controllerIndex: Int): Int {
        val li = latentCtrl.indexOfFirst { it.index == controllerIndex }
        if (li < 0) return -1
        val controller = latentCtrl.removeAt(li)
        ctrlStates.add(ControllerState(controller = controller, data = ControllerData(), isOnline = false))
        ctrlStates.sortBy { it.controller.index }
        return ctrlStates.indexOfFirst { it.controller.index == controllerIndex }
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

    /**
     * Has [packIndex] stopped reporting? **The same staleness rule [submit]'s
     * own sweep applies** — the same [BleConfig.packOfflineAfterMs] threshold
     * measured against the same injected [clock] — asked on demand instead of
     * having to wait for a sibling pack's submit to run the sweep.
     *
     * That "instead of" is the whole reason this exists (Part C §5). The sweep
     * is folded into [submit] and only ever runs over the OTHER packs, so a
     * vehicle whose only battery is the one that went quiet is never swept at
     * all: a head unit that keeps delivering its CAN controllers while the
     * battery it hosts drops out of range produces motion samples forever and
     * pack samples never, and the hosted pack would sit `isOnline = true` on a
     * frozen reading for the rest of the ride. The alias handoff asks this on
     * every sample of either kind, so the answer arrives on the head unit's
     * own traffic without a second clock or a timer.
     *
     * A pack that has never reported is NOT stale — it is simply unseen, and
     * treating the two alike would fire the handoff's re-raise before the
     * hosted battery had ever said anything.
     */
    fun isPackStale(packIndex: Int): Boolean {
        val slot = states.firstOrNull { it.pack.index == packIndex } ?: return false
        val seenAt = slot.lastSeenAt ?: return false
        if (!slot.isOnline) return true
        return (clock() - seenAt).inWholeMilliseconds > BleConfig.packOfflineAfterMs
    }

    fun markOnline(packIndex: Int) {
        val slot = states.indexOfFirst { it.pack.index == packIndex }
        if (slot < 0 || states[slot].isOnline) return
        // Refresh the timestamp too: revived with its old lastSeenAt, the
        // pack would be re-marked offline by the very next submit's sweep.
        states[slot] = states[slot].copy(isOnline = true, lastSeenAt = clock())
        emit()
    }

    /**
     * Build both aggregates into one [VehicleData]: [PackAggregator] for the
     * battery side, [MotionAggregator] for the motion side. Either [submit]
     * or [submitMotion] can trigger this, so a battery sample and a motion
     * sample each publish a complete snapshot carrying both.
     */
    fun snapshot(): VehicleData {
        val battery = PackAggregator.build(states.toList(), topology)
        val motion = MotionAggregator.build(ctrlStates.toList())
        return VehicleData(
            packs = battery.packs,
            aggregate = battery.aggregate,
            topology = topology,
            isPartial = battery.isPartial,
            controllers = ctrlStates.toList(),
            motion = motion.aggregate,
            motionPartial = motion.partial
        )
    }

    private fun emit() = onVehicleData(snapshot())
}

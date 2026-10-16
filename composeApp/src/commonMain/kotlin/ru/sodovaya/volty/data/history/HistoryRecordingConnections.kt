package ru.sodovaya.volty.data.history

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.history.*

/** App-owned connection intent. Install the complete immutable vehicle registry before any attempt.
 * This owner must be the exclusive lifecycle command source for its controller.
 * Native session ownership still fences receipt delivery independently.
 *
 * A selection describes all planned links, including those not connected yet. Partial link loss
 * cannot change that registry or pretend the whole vehicle disconnected. A replacement selection
 * closes old intake immediately. Incompatible vehicle/source selections finish the old ride,
 * including GPS, before any new ready link. Same-plan replacements retain the ride with a gap.
 * No BLE calls, background jobs, UI lifetime or native clock are owned by this class.
 */
internal class HistoryRecordingConnections(
    private val controller: RideRecordingController,
    private val readTick: () -> HistoryTick,
) {
    internal class Selection internal constructor()
    internal class Attempt internal constructor()
    private class LinkAttempt(val capability: Attempt, val sourceIds: Set<String>,
        var ready: Boolean = false, var retired: Boolean = false)
    private class Selected(
        val capability: Selection,
        val vehicleId: String,
        val generation: Long,
        val links: Map<String, List<HistorySource>>,
        val attempts: MutableMap<String, LinkAttempt> = mutableMapOf(),
    ) {
        val sources: List<HistorySource> = links.values.flatten()
        fun hasReady(): Boolean = attempts.values.any { it.ready && !it.retired }
    }
    private val controls = Mutex()
    private var selected: Selected? = null
    private var generation = 0L

    /** Snapshot and validate before retiring any existing selection. Physical aliases must be
     * resolved by the native admission planner, never independently double-bound here.
     */
    suspend fun install(vehicleId: String, links: Map<String, List<HistorySource>>): Selection {
        require(vehicleId.isNotBlank())
        val snapshot = links.mapValues { (_, sources) -> sources.toList() }.toMap()
        require(snapshot.keys.all { it.isNotBlank() })
        val sources = snapshot.values.flatten()
        require(sources.map { it.id }.distinct().size == sources.size) { "Duplicate physical source binding" }
        return control {
            check(generation < Long.MAX_VALUE) { "Connection generation exhausted" }
            if (selected != null) {
                val now = tick()
                // A previous selection may never have connected, so its generation was not
                // issued to the controller. Fence the actual current recording owner instead.
                controller.execute(RecordingInput.Selected(vehicleId, now,
                    controller.state.value.intent.connectionGeneration), sources)
                val intent = controller.state.value.intent
                if (intent.linkConnected) {
                    controller.execute(RecordingInput.LinkLost(checkNotNull(intent.connectionGeneration), now))
                }
            }
            val next = Selected(Selection(), vehicleId, ++generation, snapshot)
            selected = next
            next.capability
        }
    }

    /** A physical reconnect always gets a fresh capability, even on the same address/link ID. */
    suspend fun newAttempt(selection: Selection, linkId: String): Attempt? = control {
        val current = selected?.takeIf { it.capability === selection } ?: return@control null
        if (linkId !in current.links) return@control null
        current.attempts[linkId]?.let { retire(current, it) }
        LinkAttempt(Attempt(), checkNotNull(current.links[linkId]).map { it.id }.toSet())
            .also { current.attempts[linkId] = it }.capability
    }

    /** Call from preparation, before native observation starts. Return follows durable begin ACK. */
    suspend fun ready(attempt: Attempt): Boolean = control {
        val current = selected ?: return@control false
        val link = current.attempts.values.firstOrNull { it.capability === attempt } ?: return@control false
        if (link.retired) return@control false
        if (link.ready) return@control true
        if (!current.hasReady()) {
            controller.execute(RecordingInput.Connected(current.vehicleId, current.generation, tick()), current.sources)
        } else {
            controller.sourceReady(current.generation, link.sourceIds, tick())
        }
        link.ready = true
        true
    }

    suspend fun lost(attempt: Attempt): Boolean = control {
        val current = selected ?: return@control false
        val link = current.attempts.values.firstOrNull { it.capability === attempt } ?: return@control false
        if (link.retired) return@control false
        retire(current, link)
        true
    }

    /** Deliberate current-selection action; invalidates every pending and ready native attempt. */
    suspend fun disconnect(selection: Selection): Boolean = control {
        if (selected?.capability !== selection) return@control false
        // A same-plan replacement may not have become ready, so its connection generation
        // is not issued yet. This validated user action still ends the retained gap ride.
        controller.execute(RecordingInput.ExplicitDisconnect(tick()))
        selected = null
        true
    }

    /** Ride ID is mandatory so a delayed stop cannot end an explicit restart on the same link. */
    suspend fun stop(selection: Selection, rideId: String): Boolean = control {
        val current = selected?.takeIf { it.capability === selection } ?: return@control false
        val intent = controller.state.value.intent
        if (intent.ride?.id != rideId || intent.ride.vehicleId != current.vehicleId) return@control false
        controller.execute(RecordingInput.Stop(tick(), intent.connectionGeneration, rideId))
        true
    }

    /** Manual restart does not fabricate a connected link while every attempt is absent. */
    suspend fun start(selection: Selection): Boolean = control {
        val current = selected?.takeIf { it.capability === selection && it.hasReady() } ?: return@control false
        controller.execute(RecordingInput.Start(current.vehicleId, current.generation, tick()), current.sources)
        true
    }

    private suspend fun retire(current: Selected, link: LinkAttempt) {
        if (link.retired) return
        if (link.ready && current.attempts.values.count { it.ready && !it.retired } == 1) {
            controller.execute(RecordingInput.LinkLost(current.generation, tick()))
        } else if (link.ready) {
            controller.sourceUnavailable(current.generation, link.sourceIds, tick())
        }
        link.ready = false
        link.retired = true
    }

    /** Controller ignores an incompatible clock; do not publish physical lifecycle success
     * if its recording transition was rejected. Native clock/recovery must share this domain.
     */
    private fun tick(): HistoryTick = readTick().also { now ->
        controller.state.value.intent.ride?.let { ride ->
            require(historyAxisEpochMillis(ride.started, now) != null) { "Incompatible recording clock" }
        }
    }

    private suspend fun <T> control(action: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        val result = controls.withLock {
            currentCoroutineContext().ensureActive()
            // The controller already reconciles accepted controls noncancellably. Keep our
            // physical-link ledger in the same transaction, even if its requesting UI closes.
            withContext(NonCancellable) { action() }
        }
        currentCoroutineContext().ensureActive()
        return result
    }
}

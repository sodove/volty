package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.sodovaya.volty.domain.history.*

data class HistoryRecordingFailure(val rideId: String, val failureType: String)

fun interface HistoryRecordingDemand { suspend fun setEnabled(enabled: Boolean) }
data class HistoryRecordingRuntimeState(val intent: RecordingIntent = RecordingIntent(), val demandFailure: String? = null)

/** Immutable capability for one controller admission segment, captured at native receipt. */
internal class CapturedHistoryRecording internal constructor(
    val rideId: String,
    val connectionGeneration: Long,
    internal val generation: Long,
    internal val gate: HistoryAdmissionGate,
)

/** GPS recording lifetime spans BLE admission segments, but never another recording owner. */
internal class CapturedHistoryGpsRecording internal constructor(
    val rideId: String,
    internal val generation: Long,
    internal val identity: Any,
)

/** Recording lifetime is independent of screens, BLE reconnects and GPS availability. */
class RideRecordingController(
    private val writer: HistoryWriter,
    private val demand: HistoryRecordingDemand,
    private val newId: () -> String,
    private val recoverInterrupted: suspend () -> Int,
) {
    private data class Owner(val generation: Long, val intent: RecordingIntent, val sources: Set<String>,
        val last: HistoryTick, val gate: HistoryAdmissionGate = HistoryAdmissionGate(last),
        val failure: MutableStateFlow<String?> = MutableStateFlow(null), val identity: Any = Any())
    private val mutableState = MutableStateFlow(HistoryRecordingRuntimeState())
    val state: StateFlow<HistoryRecordingRuntimeState> = mutableState
    private val admission = MutableStateFlow<Owner?>(null)
    // Reopen is observable even when the ride survives a BLE control. A GPS event
    // rejected while intake was fenced must be reconciled without a new provider fix.
    internal val gpsAdmissionChanges = admission.map { Unit }
    private val mutableTelemetryFailure = MutableStateFlow<HistoryRecordingFailure?>(null)
    val telemetryFailure: StateFlow<HistoryRecordingFailure?> = mutableTelemetryFailure
    private val controls = Mutex()
    private var owned: Owner? = null
    private var nextGeneration = 0L
    private var recovered = false

    /** Foreground/permission opportunity: reconcile current intent, not a delayed ride snapshot. */
    internal suspend fun retryDemand() {
        currentCoroutineContext().ensureActive()
        controls.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                // A failed durable finish still owns its writer/service obligation. Its
                // intake is fenced; retrying GPS cannot reopen it or clear that failure.
                if (owned != null && admission.value == null) return@withContext
                requestDemand(owned != null)
            }
        }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun sourceUnavailable(connectionGeneration: Long, sourceIds: Set<String>, tick: HistoryTick): Boolean =
        changeSourceAvailability(connectionGeneration, sourceIds, tick, available = false)

    internal suspend fun sourceReady(connectionGeneration: Long, sourceIds: Set<String>, tick: HistoryTick): Boolean =
        changeSourceAvailability(connectionGeneration, sourceIds, tick, available = true)

    /** Physical-attempt owner qualifies the event; this control qualifies its recording registry.
     * Only the affected prefix drains. The global gate and GPS capability remain unchanged.
     */
    private suspend fun changeSourceAvailability(connectionGeneration: Long, sourceIds: Set<String>,
        tick: HistoryTick, available: Boolean): Boolean = controls.withLock {
        val snapshot = sourceIds.toSet()
        val owner = admission.value ?: return@withLock false
        val ride = owner.intent.ride ?: return@withLock false
        if (snapshot.isEmpty() || snapshot.any { it !in owner.sources } ||
            connectionGeneration != owner.intent.connectionGeneration || !owner.intent.linkConnected ||
            historyAxisEpochMillis(ride.started, tick) == null) return@withLock false
        withContext(NonCancellable) {
            val blocked = owner.gate.blockedSources(snapshot)
            val changed = if (available) blocked else snapshot - blocked
            if (changed.isEmpty()) return@withContext false
            val last = owner.gate.closeSources(changed)
            val boundaryTick = if (last != null && last.elapsedMillis > tick.elapsedMillis) last else tick
            val reservation = owner.gate.reserveBoundary(boundaryTick, changed) ?: return@withContext false
            try {
                offerMarkers(owner, changed.map { source -> HistoryMarker(boundaryTick,
                    if (available) HistoryMarkerKind.CONNECTED else HistoryMarkerKind.BLE_GAP, source) })
            } catch (error: Exception) {
                recordTelemetryFailure(owner, error::class.simpleName ?: "SourceBoundaryFailure")
                throw error
            } finally { reservation.release() }
            if (available) owner.gate.reopenSources(changed) else true
        }
    }

    /** Null retains an active same-vehicle registry; explicit empty starts a GPS-only segment.
     * A new ride never inherits a retired registry. IDs must encode physical/calibration identity.
     */
    suspend fun execute(input: RecordingInput, sources: List<HistorySource>? = null) = controls.withLock {
        val sourceSnapshot = sources?.toList()
        val sourcePlan = if (input is RecordingInput.Connected || input is RecordingInput.Start || input is RecordingInput.Selected) {
            sourceSnapshot?.let { registry ->
                require(registry.map { it.id }.distinct().size == registry.size)
                registry.associate { it.id to it.kind }
            }
        } else null
        // Once submitted, control receipts must reconcile with ownership even if the requesting screen closes.
        withContext(NonCancellable) {
            if (!recovered) { recoverInterrupted(); recovered = true }
            val previous = mutableState.value.intent
            val proposed = recordingTransition(previous, input, sourcePlan, newId)
            if (proposed.intent == previous && proposed.markers.isEmpty() && proposed.finish == null && proposed.begin == null) return@withContext
            val tick = input.tick()
            if (previous.ride?.let { historyAxisEpochMillis(it.started, tick) == null } == true) return@withContext
            fence()?.let { owned = it }
            val last = owned?.last
            // A queued UI command cannot end the ride before facts reserved before its ingress fence.
            val effective = if (last != null && last.elapsedMillis > tick.elapsedMillis) input.withTick(last) else input
            val transition = if (effective == input) proposed else recordingTransition(previous, effective, sourcePlan,
                { checkNotNull(proposed.begin).id })
            transition.finish?.let { finished ->
                val owner = checkNotNull(owned)
                offerMarkers(owner, transition.finishedMarkers)
                val status = if (writer.diagnostics.value.failure != null || owner.failure.value != null) HistoryRideStatus.INTERRUPTED else finished.status
                writer.finish(owner.generation, checkNotNull(finished.ended), status, finished.id)
                owned = null
                mutableState.value = mutableState.value.copy(intent = previous.copy(ride = null, linkConnected = false))
                requestDemand(false)
            }
            transition.begin?.let { ride ->
                check(nextGeneration < Long.MAX_VALUE) { "Recording generation exhausted" }
                val generation = ++nextGeneration
                val recordedSources = sourceSnapshot ?: emptyList()
                writer.begin(generation, ride, recordedSources)
                owned = Owner(generation, transition.intent, recordedSources.map { it.id }.toSet(), ride.started)
                mutableTelemetryFailure.value = null
            }
            val owner = owned?.let {
                val markerTick = transition.markers.maxByOrNull { marker -> marker.tick.elapsedMillis }?.tick
                val lastFact = if (markerTick != null && markerTick.elapsedMillis > it.last.elapsedMillis) markerTick else it.last
                it.copy(intent = transition.intent, last = lastFact, gate = HistoryAdmissionGate(lastFact))
            }
            if (owner != null) offerMarkers(owner, transition.markers)
            owned = owner
            mutableState.value = mutableState.value.copy(intent = transition.intent)
            admission.value = owner
            if (transition.begin != null) requestDemand(true)
        }
    }

    /** Nonblocking ownership transfer to the bounded native queue, before notification returns. */
    internal fun reserveQueuedTelemetry(context: CapturedHistoryRecording, tick: HistoryTick,
        sourceId: String): QueuedHistoryTelemetry? {
        val owner = admission.value ?: return null
        val ride = owner.intent.ride ?: return null
        if (context.gate !== owner.gate || context.generation != owner.generation || context.rideId != ride.id ||
            sourceId !in owner.sources || !acceptsRecordingTick(owner.intent, context.connectionGeneration, tick) ||
            historyAxisEpochMillis(ride.started, tick) == null) return null
        val reservation = owner.gate.reserveTelemetry(tick, setOf(sourceId)) ?: return null
        return QueuedHistoryTelemetry(onOffer = { batch ->
            // Admission may now be closed. This exact previously reserved prefix still belongs
            // to the captured owner; no later owner can begin until its reservation is released.
            if (batch.rideId != ride.id || batch.gps.isNotEmpty() || batch.markers.isNotEmpty() ||
                batch.buckets.isNotEmpty() || batch.samples.any { it.key.sourceId != sourceId || it.tick != tick }) {
                recordTelemetryFailure(owner, "QueuedPayloadMismatch")
                HistoryOffer.RETIRED
            } else try { writer.offer(owner.generation, batch) }
            catch (error: Exception) {
                recordTelemetryFailure(owner, error::class.simpleName ?: "QueuedTelemetryFailure")
                throw error
            }
        }, onDiscard = { recordTelemetryFailure(owner, "IngressLoss") }, onRelease = reservation::release)
    }

    internal fun captureTelemetryRecording(): CapturedHistoryRecording? {
        val owner = admission.value ?: return null
        val ride = owner.intent.ride ?: return null
        if (!owner.intent.linkConnected) return null
        val connectionGeneration = owner.intent.connectionGeneration ?: return null
        return CapturedHistoryRecording(ride.id, connectionGeneration, owner.generation, owner.gate)
    }

    internal fun offerTelemetry(context: CapturedHistoryRecording, batch: HistoryBatch): HistoryOffer {
        val owner = admission.value ?: return HistoryOffer.RETIRED
        if (context.gate !== owner.gate || context.generation != owner.generation ||
            context.rideId != batch.rideId) return HistoryOffer.RETIRED
        return offerTelemetry(owner, context.connectionGeneration, batch)
    }

    /** Loss verdict without invented numeric readings or writer IO on the BLE callback. */
    internal fun reportTelemetryIngressLoss(context: CapturedHistoryRecording, tick: HistoryTick, sourceId: String): Boolean =
        reportCapturedTelemetryLoss(context, tick, sourceId, "IngressLoss")

    internal fun reportTelemetryReceiptLoss(context: CapturedHistoryRecording, sourceId: String): Boolean =
        reportCapturedTelemetryLoss(context, null, sourceId, "ReceiptLoss")

    /** Caller must qualify the current BLE attempt; the captured recording cannot move to a new owner. */
    internal fun reportTelemetrySetupFailure(context: CapturedHistoryRecording, vehicleId: String): Boolean =
        reportCapturedTelemetryLoss(context, null, null, "SetupFailure", vehicleId)

    private fun reportCapturedTelemetryLoss(context: CapturedHistoryRecording, receiptTick: HistoryTick?,
        sourceId: String?, reason: String, vehicleId: String? = null): Boolean {
        val owner = admission.value ?: return false
        val ride = owner.intent.ride ?: return false
        // Receipt loss/setup failure cannot create a new acquired tick. Reuse only this
        // validated owner's existing admission bound for the failure verdict.
        val tick = receiptTick ?: owner.last
        val targetMatches = if (sourceId != null) sourceId in owner.sources
            else vehicleId == ride.vehicleId && owner.sources.isNotEmpty()
        if (context.gate !== owner.gate || context.generation != owner.generation ||
            context.rideId != ride.id || !targetMatches ||
            !acceptsRecordingTick(owner.intent, context.connectionGeneration, tick) ||
            historyAxisEpochMillis(ride.started, tick) == null) return false
        if (!owner.gate.reserve(tick)) return false
        try { recordTelemetryFailure(owner, reason); return true }
        finally { owner.gate.release() }
    }

    private fun recordTelemetryFailure(owner: Owner, type: String) {
        if (owner.failure.compareAndSet(null, type))
            mutableTelemetryFailure.value = HistoryRecordingFailure(checkNotNull(owner.intent.ride).id, type)
    }

    /** Called from accepted decoded telemetry, never from a conflated UI snapshot. No IO or suspension. */
    fun offerTelemetry(connectionGeneration: Long, batch: HistoryBatch): HistoryOffer {
        val owner = admission.value ?: return HistoryOffer.RETIRED
        return offerTelemetry(owner, connectionGeneration, batch)
    }

    private fun offerTelemetry(owner: Owner, connectionGeneration: Long, batch: HistoryBatch): HistoryOffer {
        val ride = owner.intent.ride ?: return HistoryOffer.RETIRED
        if (batch.rideId != ride.id || batch.gps.isNotEmpty() || batch.markers.isNotEmpty() || batch.buckets.isNotEmpty()) return HistoryOffer.RETIRED
        if (batch.samples.any { it.key.sourceId !in owner.sources ||
                !acceptsRecordingTick(owner.intent, connectionGeneration, it.tick) ||
                historyAxisEpochMillis(ride.started, it.tick) == null }) return HistoryOffer.RETIRED
        if (!owner.intent.linkConnected || connectionGeneration != owner.intent.connectionGeneration) return HistoryOffer.RETIRED
        val latest = batch.samples.maxByOrNull { it.tick.elapsedMillis }?.tick ?: owner.last
        val sourceIds = batch.samples.map { it.key.sourceId }.toSet()
        val reservation = if (sourceIds.isEmpty()) null else
            owner.gate.reserveTelemetry(latest, sourceIds) ?: return HistoryOffer.RETIRED
        if (sourceIds.isEmpty() && !owner.gate.reserve(latest)) return HistoryOffer.RETIRED
        // Earlier CAS-only reservation could be overtaken by finish before this send returned.
        try {
            val result = writer.offer(owner.generation, batch)
            // The writer's real bounded queue owns native delivery. Record its
            // rejection before releasing admission, so a concurrent Stop cannot
            // label a lossy ride completed merely because no exception escaped.
            when (result) {
                HistoryOffer.OVERFLOW -> recordTelemetryFailure(owner, "WriterOverflow")
                HistoryOffer.RETIRED -> recordTelemetryFailure(owner, "WriterRetired")
                HistoryOffer.ACCEPTED -> Unit
            }
            return result
        }
        catch (error: Exception) {
            // Record failure BEFORE releasing the reservation: finish cannot
            // outrun this verdict. Owner copies keep it across BLE reconnects.
            val type = error::class.simpleName ?: "TelemetryFailure"
            recordTelemetryFailure(owner, type)
            throw error
        }
        finally { if (reservation == null) owner.gate.release() else reservation.release() }
    }

    internal fun captureGpsRecording(): CapturedHistoryGpsRecording? {
        val owner = admission.value ?: return null
        return CapturedHistoryGpsRecording(checkNotNull(owner.intent.ride).id, owner.generation, owner.identity)
    }

    /** No clock read required: qualify the captured recording identity, even across BLE reconnects. */
    internal fun reportGpsObservationFailure(context: CapturedHistoryGpsRecording): Boolean {
        val owner = admission.value ?: return false
        if (context.identity !== owner.identity || context.generation != owner.generation ||
            context.rideId != owner.intent.ride?.id) return false
        if (!owner.gate.reserve(owner.last)) return false
        try { recordTelemetryFailure(owner, "GpsObservationFailure"); return true }
        finally { owner.gate.release() }
    }

    /** GPS keeps participating while BLE is reconnecting; the observer stamps the ride it captured. */
    fun offerGps(rideId: String, fix: HistoryGpsFix): HistoryOffer {
        val owner = admission.value ?: return HistoryOffer.RETIRED
        val ride = owner.intent.ride ?: return HistoryOffer.RETIRED
        if (rideId != ride.id || historyAxisEpochMillis(ride.started, fix.tick) == null) return HistoryOffer.RETIRED
        return offerGpsBatch(owner, fix.tick, HistoryBatch(ride.id, emptyList(), listOf(fix), emptyList()))
    }

    /** GPS status/expiry boundary, independent of BLE admission. No invented coordinate. */
    internal fun offerGpsGap(rideId: String, tick: HistoryTick): HistoryOffer {
        val owner = admission.value ?: return HistoryOffer.RETIRED
        val ride = owner.intent.ride ?: return HistoryOffer.RETIRED
        if (rideId != ride.id || historyAxisEpochMillis(ride.started, tick) == null) return HistoryOffer.RETIRED
        return offerGpsBatch(owner, tick, HistoryBatch(ride.id, emptyList(), emptyList(),
            listOf(HistoryMarker(tick, HistoryMarkerKind.GPS_GAP))))
    }

    private fun offerGpsBatch(owner: Owner, tick: HistoryTick, batch: HistoryBatch): HistoryOffer {
        if (!owner.gate.reserve(tick)) return HistoryOffer.RETIRED
        try { return writer.offer(owner.generation, batch) }
        catch (error: Exception) {
            // The failure verdict must precede reservation release: a waiting stop owns finish.
            recordTelemetryFailure(owner, "GpsOfferFailure")
            throw error
        }
        finally { owner.gate.release() }
    }

    private suspend fun fence(): Owner? {
        while (true) {
            val current = admission.value ?: return null
            if (admission.compareAndSet(current, null)) {
                val last = current.gate.close()
                current.gate.awaitIdle()
                return current.copy(last = last)
            }
        }
    }

    private fun offerMarkers(owner: Owner, markers: List<HistoryMarker>) {
        if (markers.isNotEmpty()) writer.offer(owner.generation,
            HistoryBatch(checkNotNull(owner.intent.ride).id, emptyList(), emptyList(), markers))
    }

    private suspend fun requestDemand(enabled: Boolean) {
        try {
            demand.setEnabled(enabled)
            mutableState.value = mutableState.value.copy(demandFailure = null)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            // Telemetry remains usable if GPS/service demand cannot be admitted.
            mutableState.value = mutableState.value.copy(demandFailure = error.message ?: "Recording demand failed")
        }
    }
}

private fun RecordingInput.tick(): HistoryTick = when (this) {
    is RecordingInput.Selected -> tick
    is RecordingInput.Connected -> tick
    is RecordingInput.LinkLost -> tick
    is RecordingInput.Stop -> tick
    is RecordingInput.ExplicitDisconnect -> tick
    is RecordingInput.Start -> tick
}

private fun RecordingInput.withTick(value: HistoryTick): RecordingInput = when (this) {
    is RecordingInput.Selected -> copy(tick = value)
    is RecordingInput.Connected -> copy(tick = value)
    is RecordingInput.LinkLost -> copy(tick = value)
    is RecordingInput.Stop -> copy(tick = value)
    is RecordingInput.ExplicitDisconnect -> copy(tick = value)
    is RecordingInput.Start -> copy(tick = value)
}

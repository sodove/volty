package ru.sodovaya.volty.data.history

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
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

/** Recording lifetime is independent of screens, BLE reconnects and GPS availability. */
class RideRecordingController(
    private val writer: HistoryWriter,
    private val demand: HistoryRecordingDemand,
    private val newId: () -> String,
    private val recoverInterrupted: suspend () -> Int,
) {
    private data class Owner(val generation: Long, val intent: RecordingIntent, val sources: Set<String>,
        val last: HistoryTick, val gate: HistoryAdmissionGate = HistoryAdmissionGate(last),
        val failure: MutableStateFlow<String?> = MutableStateFlow(null))
    private val mutableState = MutableStateFlow(HistoryRecordingRuntimeState())
    val state: StateFlow<HistoryRecordingRuntimeState> = mutableState
    private val admission = MutableStateFlow<Owner?>(null)
    private val mutableTelemetryFailure = MutableStateFlow<HistoryRecordingFailure?>(null)
    val telemetryFailure: StateFlow<HistoryRecordingFailure?> = mutableTelemetryFailure
    private val controls = Mutex()
    private var owned: Owner? = null
    private var nextGeneration = 0L
    private var recovered = false

    suspend fun execute(input: RecordingInput, sources: List<HistorySource> = emptyList()) = controls.withLock {
        // Once submitted, control receipts must reconcile with ownership even if the requesting screen closes.
        withContext(NonCancellable) {
            if (!recovered) { recoverInterrupted(); recovered = true }
            val previous = mutableState.value.intent
            val proposed = recordingTransition(previous, input, newId)
            if (proposed.intent == previous && proposed.markers.isEmpty() && proposed.finish == null && proposed.begin == null) return@withContext
            val tick = input.tick()
            if (previous.ride?.let { historyAxisEpochMillis(it.started, tick) == null } == true) return@withContext
            if (proposed.begin != null) require(sources.map { it.id }.distinct().size == sources.size)
            fence()?.let { owned = it }
            val last = owned?.last
            // A queued UI command cannot end the ride before facts reserved before its ingress fence.
            val effective = if (last != null && last.elapsedMillis > tick.elapsedMillis) input.withTick(last) else input
            val transition = if (effective == input) proposed else recordingTransition(previous, effective,
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
                writer.begin(generation, ride, sources)
                owned = Owner(generation, transition.intent, sources.map { it.id }.toSet(), ride.started)
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
        if (!owner.gate.reserve(latest)) return HistoryOffer.RETIRED
        // Earlier CAS-only reservation could be overtaken by finish before this send returned.
        try { return writer.offer(owner.generation, batch) }
        catch (error: Exception) {
            // Record failure BEFORE releasing the reservation: finish cannot
            // outrun this verdict. Owner copies keep it across BLE reconnects.
            val type = error::class.simpleName ?: "TelemetryFailure"
            recordTelemetryFailure(owner, type)
            throw error
        }
        finally { owner.gate.release() }
    }

    /** GPS keeps participating while BLE is reconnecting; the observer stamps the ride it captured. */
    fun offerGps(rideId: String, fix: HistoryGpsFix): HistoryOffer {
        val owner = admission.value ?: return HistoryOffer.RETIRED
        val ride = owner.intent.ride ?: return HistoryOffer.RETIRED
        if (rideId != ride.id || historyAxisEpochMillis(ride.started, fix.tick) == null) return HistoryOffer.RETIRED
        if (!owner.gate.reserve(fix.tick)) return HistoryOffer.RETIRED
        try { return writer.offer(owner.generation, HistoryBatch(ride.id, emptyList(), listOf(fix), emptyList())) }
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
    is RecordingInput.Connected -> tick
    is RecordingInput.LinkLost -> tick
    is RecordingInput.Stop -> tick
    is RecordingInput.ExplicitDisconnect -> tick
    is RecordingInput.Start -> tick
}

private fun RecordingInput.withTick(value: HistoryTick): RecordingInput = when (this) {
    is RecordingInput.Connected -> copy(tick = value)
    is RecordingInput.LinkLost -> copy(tick = value)
    is RecordingInput.Stop -> copy(tick = value)
    is RecordingInput.ExplicitDisconnect -> copy(tick = value)
    is RecordingInput.Start -> copy(tick = value)
}

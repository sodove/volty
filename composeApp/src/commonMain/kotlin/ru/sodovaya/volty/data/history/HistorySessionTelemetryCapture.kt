package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.ble.*
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.TelemetryOrigin
import ru.sodovaya.volty.domain.model.TelemetryRole
import ru.sodovaya.volty.domain.model.TelemetryObservation
import ru.sodovaya.volty.domain.model.requireTelemetryAddress

internal data class HistoryTelemetrySource(val origin: TelemetryOrigin, val localIndex: Int,
    val role: TelemetryRole = TelemetryRole.CONTRIBUTOR, val assemblyIndex: Int? = null) {
    init { requireTelemetryAddress(origin, localIndex, role, assemblyIndex) }
}

private fun TelemetryObservation.historySourceKey() = HistoryTelemetrySource(origin, localIndex, role, assemblyIndex)

internal data class CapturedHistoryTelemetry(
    val recording: CapturedHistoryRecording,
    val source: HistorySource,
    val tick: HistoryTick,
    val received: ReceivedTelemetry,
) {
    init {
        require(tick.epochMillis == received.receipt.time.epochMillis &&
            tick.elapsedMillis == received.receipt.time.elapsedMillis)
    }

    /** Call inside the observation admission fence (or the legacy serialized consumer). No IO. */
    fun offer(controller: RideRecordingController, ownsSession: () -> Boolean): HistoryOffer {
        if (!ownsSession()) return HistoryOffer.RETIRED
        return controller.offerTelemetry(recording, HistoryBatch(recording.rideId,
            historyTelemetrySamples(source, tick, received.observation), emptyList(), emptyList()))
    }
}

/**
 * BLE-attempt lifetime, independent of recording start/stop. Only sources with
 * unambiguous full role/address identity may use this map. Auxiliary and derived
 * sources require their distinct durable kinds and IDs; producer freshness and
 * calibration remain separate obligations of the source plan.
 */
internal fun historySessionTelemetryCapture(
    protocol: BmsProtocol,
    sessionId: String,
    controller: RideRecordingController,
    readTick: () -> HistoryTick,
    ownsSession: () -> Boolean,
    sources: Map<HistoryTelemetrySource, HistorySource>,
    onTelemetry: (CapturedHistoryTelemetry) -> Unit,
): SessionTelemetryCapture = historySessionTelemetryCapture(protocol, sessionId, controller, readTick,
    ownsSession, sources, null, onTelemetry = onTelemetry)

internal fun historySessionTelemetryCapture(
    protocol: BmsProtocol,
    sessionId: String,
    controller: RideRecordingController,
    readTick: () -> HistoryTick,
    ownsSession: () -> Boolean,
    sources: Map<HistoryTelemetrySource, HistorySource>,
    prepareRecording: (suspend () -> Unit)?,
    dispatchObservation: (() -> Unit) -> Unit = { action -> action() },
    onTelemetry: (CapturedHistoryTelemetry) -> Unit,
): SessionTelemetryCapture {
    // A caller can rebuild or mutate its map later; one attempt keeps its own plan.
    val binding = sources.toMap()
    binding.forEach { (key, source) ->
        val kind = historySourceKindForTelemetry(key.origin, key.role)
        require(source.kind == kind) { "A decoder source must preserve its qualified role" }
    }
    require(binding.values.map { it.id }.distinct().size == binding.size)
    var captured: Pair<CapturedHistoryRecording?, HistoryTick?>? = null
    return SessionTelemetryCapture(protocol, sessionId, {
        captured = null
        // Snapshot BEFORE trace/decoder callbacks. Retired gates cannot be reused
        // even if a later ride or controller has matching IDs/generation numbers.
        val recording = controller.captureTelemetryRecording()
        captured = recording to null
        val tick = readTick()
        captured = recording to tick
        TelemetryReceiptTime(tick.epochMillis, tick.elapsedMillis)
    }, ownsSession, { received ->
        dispatchObservation {
            val snapshot = captured
            val recording = snapshot?.first
            val tick = snapshot?.second
            if (recording != null && tick != null) {
                val observation = received.observation
                val source = binding[observation.historySourceKey()]
                if (source != null) onTelemetry(CapturedHistoryTelemetry(recording, source, tick, received))
            }
        }
    }, onUnreceiptedObservation = { observation ->
        dispatchObservation {
            val recording = captured?.first
            val source = binding[observation.historySourceKey()]
            if (recording != null && source != null) controller.reportTelemetryReceiptLoss(recording, source.id)
        }
    }, onPrepare = prepareRecording)
}

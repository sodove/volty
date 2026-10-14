package ru.sodovaya.volty.data.history

import ru.sodovaya.volty.data.ble.*
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.TelemetryOrigin

internal data class HistoryTelemetrySource(val origin: TelemetryOrigin, val localIndex: Int) {
    init { require(localIndex >= 0) }
}

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

    /** Call from the serialized consumer under its BLE ownership lock. No IO. */
    fun offer(controller: RideRecordingController, ownsSession: () -> Boolean): HistoryOffer {
        if (!ownsSession()) return HistoryOffer.RETIRED
        return controller.offerTelemetry(recording, HistoryBatch(recording.rideId,
            historyTelemetrySamples(source, tick, received.observation), emptyList(), emptyList()))
    }
}

/**
 * BLE-attempt lifetime, independent of recording start/stop. Only sources with
 * unambiguous origin/local-index identity may use this map; synthetic/assembly
 * roles need explicit attribution before they can be activated here.
 */
internal fun historySessionTelemetryCapture(
    protocol: BmsProtocol,
    sessionId: String,
    controller: RideRecordingController,
    readTick: () -> HistoryTick,
    ownsSession: () -> Boolean,
    sources: Map<HistoryTelemetrySource, HistorySource>,
    onTelemetry: (CapturedHistoryTelemetry) -> Unit,
): SessionTelemetryCapture {
    // A caller can rebuild or mutate its map later; one attempt keeps its own plan.
    val binding = sources.toMap()
    binding.forEach { (key, source) ->
        val kind = when (key.origin) {
            TelemetryOrigin.BATTERY -> HistorySourceKind.BATTERY
            TelemetryOrigin.CONTROLLER -> HistorySourceKind.CONTROLLER
        }
        require(source.kind == kind) { "A decoder source must name a physical contributor" }
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
        val snapshot = captured
        val recording = snapshot?.first
        val tick = snapshot?.second
        if (recording != null && tick != null) {
            val observation = received.observation
            val source = binding[HistoryTelemetrySource(observation.origin, observation.localIndex)]
            if (source != null) onTelemetry(CapturedHistoryTelemetry(recording, source, tick, received))
        }
    }, onUnreceiptedObservation = { observation ->
        val recording = captured?.first
        val source = binding[HistoryTelemetrySource(observation.origin, observation.localIndex)]
        if (recording != null && source != null) controller.reportTelemetryReceiptLoss(recording, source.id)
    })
}

package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.Vehicle

internal enum class HistoryTelemetryIngressFailure { FULL, CLOSED, SETUP }
internal data class HistoryTelemetryIngressDiagnostics(
    val droppedObservations: Long = 0,
    val setupFailures: Long = 0,
    val lastFailure: HistoryTelemetryIngressFailure? = null,
)

/** Attempt-owned source plan and bounded nonblocking transport; diagnostics contain no raw data. */
internal class HistoryTelemetryIngress(
    private val controller: RideRecordingController,
    private val readTick: () -> HistoryTick,
    private val sources: (LinkSpec, Vehicle?) -> Map<HistoryTelemetrySource, HistorySource>,
) {
    private val mutableDiagnostics = MutableStateFlow(HistoryTelemetryIngressDiagnostics())
    val diagnostics: StateFlow<HistoryTelemetryIngressDiagnostics> = mutableDiagnostics

    fun capture(protocol: BmsProtocol, sessionId: String, spec: LinkSpec, vehicle: Vehicle?,
        ownership: SampleSessionOwnership, channel: SendChannel<Sample>): SessionTelemetryCapture? {
        // Capture before the planner: control may replace this ride while setup runs.
        val recording = controller.captureTelemetryRecording()
        return try {
            historySessionTelemetryCapture(protocol, sessionId, controller, readTick, ownership::isCurrent,
                sources(spec, vehicle)) { telemetry ->
                val sent = channel.trySend(HistoryTelemetrySample(telemetry, controller, ownership))
                if (sent.isFailure) {
                    // A counter alone cannot qualify a completed ride as partial.
                    // Latch on the captured owner without bypassing numeric delivery.
                    controller.reportTelemetryIngressLoss(telemetry.recording, telemetry.tick, telemetry.source.id)
                    mutableDiagnostics.update {
                        it.copy(droppedObservations = increment(it.droppedObservations),
                            lastFailure = if (sent.isClosed) HistoryTelemetryIngressFailure.CLOSED else HistoryTelemetryIngressFailure.FULL)
                    }
                }
            }
        } catch (_: Exception) {
            // Setup has no acquired observation/source ID. Conservatively qualify
            // only this vehicle's captured telemetry owner and current attempt.
            if (recording != null && vehicle != null) {
                try {
                    if (ownership.isCurrent()) controller.reportTelemetrySetupFailure(recording, vehicle.id)
                } catch (_: Exception) {
                    // An ownership predicate failure cannot break ordinary live BLE.
                }
            }
            // Recording setup cannot prevent the dashboard's ordinary BLE decode.
            mutableDiagnostics.update { it.copy(setupFailures = increment(it.setupFailures),
                lastFailure = HistoryTelemetryIngressFailure.SETUP) }
            null
        }
    }

    private fun increment(value: Long): Long = if (value < Long.MAX_VALUE) value + 1 else value
}

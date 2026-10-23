package ru.sodovaya.volty.data.ble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.data.bms.BegodeProtocol
import ru.sodovaya.volty.data.bms.BmsProtocol
import ru.sodovaya.volty.data.history.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.domain.model.Vehicle

internal enum class HistoryTelemetryIngressFailure { FULL, CLOSED, OVERFLOW, SETUP, RETIRED }
internal data class HistoryTelemetryIngressDiagnostics(
    val droppedObservations: Long = 0,
    val setupFailures: Long = 0,
    val lastFailure: HistoryTelemetryIngressFailure? = null,
)

/** Attempt-owned source plan and bounded nonblocking transport; diagnostics contain no raw data. */
internal class HistoryTelemetryIngress(
    private val controller: RideRecordingController,
    private val readTick: () -> HistoryTick,
    private val sources: ((LinkSpec, Vehicle?) -> Map<HistoryTelemetrySource, HistorySource>)? = null,
    private val prepareRecording: (suspend (LinkSpec, Vehicle?, List<HistorySource>) -> Unit)? = null,
    manageConnections: Boolean = false,
) {
    private val mutableDiagnostics = MutableStateFlow(HistoryTelemetryIngressDiagnostics())
    val diagnostics: StateFlow<HistoryTelemetryIngressDiagnostics> = mutableDiagnostics

    init { require(!manageConnections || prepareRecording == null) { "Choose one recording lifecycle owner" } }
    private val nativeLifecycle = if (manageConnections) NativeHistoryRecordingLifecycle(controller, readTick,
        sources ?: ::nativeHistorySourcesForSelectedLink, onFailure = {
        mutableDiagnostics.update { it.copy(setupFailures = increment(it.setupFailures),
            lastFailure = HistoryTelemetryIngressFailure.SETUP) }
    }, selectPhysicalPaths = sources == null) else null

    suspend fun installNativePlan(vehicleId: String, specs: List<LinkSpec>, vehicle: Vehicle?, ownsPlan: () -> Boolean) {
        nativeLifecycle?.install(vehicleId, specs, vehicle, ownsPlan)
    }
    suspend fun retireNativeAttempt(protocol: Any) { nativeLifecycle?.retire(protocol) }
    suspend fun disconnectNative(ownsDisconnect: () -> Boolean) { nativeLifecycle?.disconnect(ownsDisconnect) }
    suspend fun closeNative() { nativeLifecycle?.close() }
    fun manualControls(): NativeHistoryRecordingLifecycle.ManualControls? = nativeLifecycle?.manualControls()

    fun capture(protocol: BmsProtocol, sessionId: String, spec: LinkSpec, vehicle: Vehicle?,
        ownership: SampleSessionOwnership, channel: SendChannel<Sample>): SessionTelemetryCapture? {
        if (nativeLifecycle?.isAccepting == false) return null
        // Capture before the planner: control may replace this ride while setup runs.
        val recording = controller.captureTelemetryRecording()
        // Controller kinds have no battery type of their own; toBmsType() refuses them.
        val linkBatteryType = runCatching { spec.protocolKind.toBmsType() }.getOrNull()
        return try {
            val native = nativeLifecycle?.binding(spec, protocol, ownership::isCurrent)
            if (nativeLifecycle != null && native == null) return null
            val binding = native?.sources ?: (sources?.invoke(spec, vehicle) ?: nativeHistorySourcesForLink(spec, vehicle)).toMap()
            val preparation = native?.prepare ?: prepareRecording?.let { start -> suspend {
                try { start(spec, vehicle, binding.values.toList()) }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) {
                    mutableDiagnostics.update { it.copy(setupFailures = increment(it.setupFailures),
                        lastFailure = HistoryTelemetryIngressFailure.SETUP) }
                    throw error
                }
            } }
            historySessionTelemetryCapture(protocol, sessionId, controller, readTick, { ownership.isCurrent() && nativeLifecycle?.isAccepting != false },
                binding, preparation, dispatchObservation = native?.dispatchObservation ?: { action -> action() },
                enrich = { observation -> withHistoryEstimatedSoc(observation, vehicle, linkBatteryType) {
                    (protocol as? BegodeProtocol)?.derivedCellCount() } }) { telemetry ->
                if (nativeLifecycle != null) {
                    val offered = telemetry.offer(controller, ownership::isCurrent)
                    if (offered != HistoryOffer.ACCEPTED) {
                        mutableDiagnostics.update {
                            it.copy(droppedObservations = increment(it.droppedObservations),
                                lastFailure = when (offered) {
                                    HistoryOffer.ACCEPTED -> it.lastFailure
                                    HistoryOffer.OVERFLOW -> HistoryTelemetryIngressFailure.OVERFLOW
                                    HistoryOffer.RETIRED -> HistoryTelemetryIngressFailure.RETIRED
                                })
                        }
                    }
                    return@historySessionTelemetryCapture
                }

                val sample = HistoryTelemetrySample(telemetry, controller, ownership)
                val sent = channel.trySend(sample)
                if (sent.isFailure) {
                    // A counter alone cannot qualify a completed ride as partial.
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

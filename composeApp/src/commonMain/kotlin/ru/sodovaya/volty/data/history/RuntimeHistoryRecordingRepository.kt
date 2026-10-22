package ru.sodovaya.volty.data.history

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.data.ble.NativeHistoryRecordingLifecycle

/** Observes the app-owned runtime; resolving or collecting this never prepares it. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class RuntimeHistoryRecordingRepository(private val runtime: HistoryRecordingRuntime) : HistoryRecordingRepository {
    override val states: Flow<HistoryRecordingStatus> = runtime.status.flatMapLatest { status ->
        val binding = status.binding
        if (binding == null || status.closing) flowOf(HistoryRecordingStatus())
        else combine(binding.controller.state, binding.locations, binding.writerDiagnostics,
            binding.controller.gpsAdmissionChanges) { recording, location, writer, _ ->
            if (runtime.status.value.binding !== binding || runtime.status.value.closing) HistoryRecordingStatus()
            else {
                val intent = recording.intent
                val controls = binding.ingress.manualControls()
                val active = binding.controller.captureGpsRecording()
                val sameGpsOwner = active != null && location != null && active.rideId == location.recording.rideId &&
                    active.generation == location.recording.generation && active.identity === location.recording.identity
                val route = when {
                    location == null -> if (intent.ride == null) HistoryRecordingRouteStatus.IDLE else HistoryRecordingRouteStatus.UNAVAILABLE
                    !sameGpsOwner || !location.state.wanted || intent.ride == null -> HistoryRecordingRouteStatus.STOPPING
                    location.state.failure != null -> HistoryRecordingRouteStatus.UNAVAILABLE
                    location.state.capturing -> HistoryRecordingRouteStatus.RECORDING
                    else -> HistoryRecordingRouteStatus.STARTING
                }
                HistoryRecordingStatus(available = true, ride = intent.ride, linkConnected = intent.linkConnected,
                    manuallyStopped = intent.manuallyStopped,
                    canStart = controls != null && intent.manuallyStopped && intent.ride == null,
                    canStop = controls != null && intent.ride != null, route = route,
                    storageFailure = writer.failure != null || writer.closed,
                    controlTarget = controls?.let { HistoryRecordingControlTarget(this, binding, it.identity, wrap(binding, it)) },
                    telemetryLimited = controls?.telemetryLimited ?: false)
            }
        }
    }.distinctUntilChanged()

    override fun captureControls(observed: HistoryRecordingStatus): HistoryRecordingControls? {
        val status = runtime.status.value
        val binding = status.binding ?: return null
        if (status.closing) return null
        val target = observed.controlTarget ?: return null
        if (target.issuer !== this || target.binding !== binding) return null
        return target.controls
    }

    override suspend fun retryLocation(): HistoryRecordingActionResult {
        val status = runtime.status.value
        val binding = status.binding ?: return HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.UNAVAILABLE)
        if (status.closing) return HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.UNAVAILABLE)
        return try {
            // Permission/foreground opportunities reconcile the current desired
            // capture; they do not create a writer or start another recording.
            binding.controller.retryDemand()
            HistoryRecordingActionResult.Applied
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.STORAGE) }
    }

    private fun wrap(binding: HistoryRecordingRuntime.Binding,
        captured: NativeHistoryRecordingLifecycle.ManualControls): HistoryRecordingControls = object : HistoryRecordingControls {
            override suspend fun start() = apply { captured.start() }
            override suspend fun stop(rideId: String) = apply { captured.stop(rideId) }
            private suspend fun apply(action: suspend () -> Boolean): HistoryRecordingActionResult = try {
                if (action()) HistoryRecordingActionResult.Applied
                else HistoryRecordingActionResult.Failed(if (binding.writerDiagnostics.value.let { it.failure != null || it.closed })
                    HistoryRecordingActionFailure.STORAGE else HistoryRecordingActionFailure.NOT_APPLIED)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.STORAGE) }
    }
}

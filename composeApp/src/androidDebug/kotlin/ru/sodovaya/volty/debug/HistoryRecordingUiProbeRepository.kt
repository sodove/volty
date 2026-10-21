package ru.sodovaya.volty.debug

import kotlinx.coroutines.flow.MutableStateFlow
import ru.sodovaya.volty.domain.history.*

/** Explicit UI-only fixture. No writer, BLE connection, GPS demand or database changes. */
internal class HistoryRecordingUiProbeRepository(mode: String) : HistoryRecordingRepository {
    private val ride = HistoryRide("recording-ui-probe", "ui-bike", HistoryTick(1000, 10, 1), null,
        HistoryRideStatus.RECORDING)
    override val states = MutableStateFlow(HistoryRecordingStatus(available = true,
        ride = ride.takeUnless { mode == "stopped" }, linkConnected = mode != "offline",
        manuallyStopped = mode == "stopped", canStart = mode == "stopped", canStop = mode != "stopped",
        storageFailure = mode == "storage", route = when (mode) {
            "stopped" -> HistoryRecordingRouteStatus.IDLE
            "gps" -> HistoryRecordingRouteStatus.UNAVAILABLE
            else -> HistoryRecordingRouteStatus.RECORDING
        }))
    override fun captureControls(observed: HistoryRecordingStatus): HistoryRecordingControls = object : HistoryRecordingControls {
        override suspend fun start(): HistoryRecordingActionResult {
            if (states.value != observed) return changed()
            states.value = observed.copy(ride = ride, manuallyStopped = false, canStart = false, canStop = true,
                route = HistoryRecordingRouteStatus.RECORDING)
            return HistoryRecordingActionResult.Applied
        }
        override suspend fun stop(rideId: String): HistoryRecordingActionResult {
            if (states.value != observed || observed.ride?.id != rideId) return changed()
            states.value = observed.copy(ride = null, manuallyStopped = true, canStart = true, canStop = false,
                route = HistoryRecordingRouteStatus.IDLE)
            return HistoryRecordingActionResult.Applied
        }
    }
    override suspend fun retryLocation() = HistoryRecordingActionResult.Applied
    private fun changed() = HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.NOT_APPLIED)
}

package ru.sodovaya.volty.domain.history

import kotlinx.coroutines.flow.Flow

enum class HistoryRecordingRouteStatus { IDLE, STARTING, RECORDING, UNAVAILABLE, STOPPING }
data class HistoryRecordingStatus(val available: Boolean = false, val ride: HistoryRide? = null,
    val linkConnected: Boolean = false, val manuallyStopped: Boolean = false,
    val canStart: Boolean = false, val canStop: Boolean = false,
    val route: HistoryRecordingRouteStatus = HistoryRecordingRouteStatus.IDLE, val storageFailure: Boolean = false,
    val controlTarget: HistoryRecordingControlTarget? = null)

/** Opaque, issued with a displayed snapshot. Equality tracks the selection, not
 * telemetry counters or a newly allocated wrapper around the same capability. */
class HistoryRecordingControlTarget internal constructor(internal val issuer: Any, internal val binding: Any,
    internal val identity: Any, internal val controls: HistoryRecordingControls) {
    override fun equals(other: Any?): Boolean = other is HistoryRecordingControlTarget &&
        issuer === other.issuer && binding === other.binding && identity === other.identity
    override fun hashCode(): Int = (issuer.hashCode() * 31 + binding.hashCode()) * 31 + identity.hashCode()
}
enum class HistoryRecordingActionFailure { UNAVAILABLE, NOT_APPLIED, STORAGE }
sealed interface HistoryRecordingActionResult {
    data object Applied : HistoryRecordingActionResult
    data class Failed(val reason: HistoryRecordingActionFailure) : HistoryRecordingActionResult
}
interface HistoryRecordingControls {
    suspend fun start(): HistoryRecordingActionResult
    suspend fun stop(rideId: String): HistoryRecordingActionResult
}
interface HistoryRecordingRepository {
    val states: Flow<HistoryRecordingStatus>
    /** Use the displayed snapshot before scheduling work. Never retarget a queued click. */
    fun captureControls(observed: HistoryRecordingStatus): HistoryRecordingControls?
    /** Reconcile current GPS demand after an explicit permission/settings opportunity; never prepare a writer. */
    suspend fun retryLocation(): HistoryRecordingActionResult = HistoryRecordingActionResult.Failed(HistoryRecordingActionFailure.UNAVAILABLE)
}

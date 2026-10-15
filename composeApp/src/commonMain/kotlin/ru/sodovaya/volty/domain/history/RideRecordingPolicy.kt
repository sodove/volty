package ru.sodovaya.volty.domain.history

data class RecordingIntent(val ride: HistoryRide? = null, val connectionGeneration: Long? = null,
    val manuallyStopped: Boolean = false, val linkConnected: Boolean = false,
    /** Recorded identities and kinds; display names/order do not define a new ride. */
    val sourcePlan: Map<String, HistorySourceKind> = emptyMap())
sealed interface RecordingInput {
    data class Connected(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
    data class LinkLost(val generation: Long, val tick: HistoryTick) : RecordingInput
    data class Stop(val tick: HistoryTick, val generation: Long? = null, val rideId: String? = null) : RecordingInput
    data class ExplicitDisconnect(val tick: HistoryTick, val generation: Long? = null) : RecordingInput
    data class Start(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
}
/** Markers belong to intent.ride; finishedMarkers belong exclusively to finish. */
data class RecordingTransition(val intent: RecordingIntent, val markers: List<HistoryMarker>,
    val finish: HistoryRide?, val begin: HistoryRide?, val finishedMarkers: List<HistoryMarker> = emptyList())

fun recordingTransition(current: RecordingIntent, input: RecordingInput,
    sourcePlan: Map<String, HistorySourceKind>? = null, newId: () -> String): RecordingTransition {
    fun unchanged() = RecordingTransition(current, emptyList(), null, null)
    fun stopped(tick: HistoryTick, manual: Boolean): RecordingTransition {
        val finished = current.ride?.copy(ended = tick, status = HistoryRideStatus.COMPLETED)
        return RecordingTransition(current.copy(ride = null, manuallyStopped = manual, linkConnected = false, sourcePlan = emptyMap()),
            emptyList(), finished, null,
            if (finished != null) listOf(HistoryMarker(tick, HistoryMarkerKind.MANUAL_STOP)) else emptyList())
    }
    fun connect(vehicleId: String, generation: Long, tick: HistoryTick, explicit: Boolean): RecordingTransition {
        require(vehicleId.isNotBlank() && generation >= 0)
        val previousGeneration = current.connectionGeneration
        if (previousGeneration != null && generation < previousGeneration) return unchanged()
        if (!explicit && current.manuallyStopped) {
            return RecordingTransition(current.copy(connectionGeneration = generation), emptyList(), null, null)
        }
        val active = current.ride
        // A retired connection is a tombstone, not a new start trigger.
        if (!explicit && active == null && previousGeneration != null && generation == previousGeneration) return unchanged()
        if (active != null && active.vehicleId == vehicleId &&
            (sourcePlan == null || sourcePlan == current.sourcePlan)) {
            if (current.linkConnected && generation == previousGeneration) return unchanged()
            return RecordingTransition(current.copy(connectionGeneration = generation, linkConnected = true),
                listOf(HistoryMarker(tick, HistoryMarkerKind.CONNECTED)), null, null)
        }
        val ride = HistoryRide(newId(), vehicleId, tick, null, HistoryRideStatus.RECORDING)
        val finished = active?.copy(ended = tick, status = HistoryRideStatus.COMPLETED)
        return RecordingTransition(RecordingIntent(ride, generation, false, true, sourcePlan?.toMap() ?: emptyMap()),
            listOf(HistoryMarker(tick, HistoryMarkerKind.CONNECTED)), finished, ride,
            if (finished != null) listOf(HistoryMarker(tick,
                if (finished.vehicleId == vehicleId) HistoryMarkerKind.SOURCE_CHANGE else HistoryMarkerKind.VEHICLE_SWITCH))
            else emptyList())
    }
    return when (input) {
        is RecordingInput.Connected -> connect(input.vehicleId, input.generation, input.tick, explicit = false)
        is RecordingInput.Start -> connect(input.vehicleId, input.generation, input.tick, explicit = true)
        is RecordingInput.LinkLost -> {
            if (input.generation != current.connectionGeneration || current.ride == null || !current.linkConnected) unchanged()
            else RecordingTransition(current.copy(linkConnected = false),
                listOf(HistoryMarker(input.tick, HistoryMarkerKind.BLE_GAP)), null, null)
        }
        is RecordingInput.Stop -> {
            if ((input.generation != null && input.generation != current.connectionGeneration) ||
                (input.rideId != null && input.rideId != current.ride?.id)) unchanged()
            else stopped(input.tick, manual = true)
        }
        is RecordingInput.ExplicitDisconnect -> {
            if (input.generation != null && input.generation != current.connectionGeneration) unchanged()
            else stopped(input.tick, manual = false)
        }
    }
}

/** Telemetry admission; GPS has independent participation while the BLE link is absent. */
fun acceptsRecordingTick(intent: RecordingIntent, generation: Long, tick: HistoryTick): Boolean {
    val ride = intent.ride ?: return false
    if (!intent.linkConnected || generation != intent.connectionGeneration) return false
    return tick.clockGeneration == ride.started.clockGeneration && tick.elapsedMillis >= ride.started.elapsedMillis
}

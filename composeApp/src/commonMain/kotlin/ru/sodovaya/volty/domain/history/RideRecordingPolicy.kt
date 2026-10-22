package ru.sodovaya.volty.domain.history

data class RecordingIntent(val ride: HistoryRide? = null, val connectionGeneration: Long? = null,
    val manuallyStopped: Boolean = false, val linkConnected: Boolean = false,
    /** Recorded identities and kinds; display names/order do not define a new ride. */
    val sourcePlan: Map<String, HistorySourceKind> = emptyMap(),
    /** When the whole vehicle link went away under an open ride; null while connected. */
    val lostAt: HistoryTick? = null)

/** A vehicle gone this long ends its ride. GPS follows the open ride, not the link, so an
 * unbounded gap kept tracking the rider's phone overnight while the wheel stayed in the garage.
 * Short BLE dropouts mid-ride stay one ride with a gap.
 */
const val RIDE_ABSENCE_MILLIS = 5 * 60_000L
sealed interface RecordingInput {
    /** Retire an incompatible selected vehicle/registry without inventing a ready connection. */
    data class Selected(val vehicleId: String, val tick: HistoryTick, val generation: Long? = null) : RecordingInput
    data class Connected(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
    data class LinkLost(val generation: Long, val tick: HistoryTick) : RecordingInput
    data class Stop(val tick: HistoryTick, val generation: Long? = null, val rideId: String? = null) : RecordingInput
    data class ExplicitDisconnect(val tick: HistoryTick, val generation: Long? = null) : RecordingInput
    data class Start(val vehicleId: String, val generation: Long, val tick: HistoryTick) : RecordingInput
    /** The link lost at [tick] never came back within [RIDE_ABSENCE_MILLIS]; tick is the loss, not the timeout. */
    data class Absent(val generation: Long, val tick: HistoryTick) : RecordingInput
}
/** Markers belong to intent.ride; finishedMarkers belong exclusively to finish. */
data class RecordingTransition(val intent: RecordingIntent, val markers: List<HistoryMarker>,
    val finish: HistoryRide?, val begin: HistoryRide?, val finishedMarkers: List<HistoryMarker> = emptyList())

fun recordingTransition(current: RecordingIntent, input: RecordingInput,
    sourcePlan: Map<String, HistorySourceKind>? = null, newId: () -> String): RecordingTransition {
    fun unchanged() = RecordingTransition(current, emptyList(), null, null)
    fun stopped(tick: HistoryTick, manual: Boolean): RecordingTransition {
        val finished = current.ride?.copy(ended = tick, status = HistoryRideStatus.COMPLETED)
        return RecordingTransition(current.copy(ride = null, manuallyStopped = manual, linkConnected = false,
            sourcePlan = emptyMap(), lostAt = null),
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
        val lostAt = current.lostAt
        // Fallback for a late absence timer: the reconnect itself proves how long the vehicle was gone.
        val absent = current.ride != null && !current.linkConnected && lostAt != null &&
            lostAt.clockGeneration == tick.clockGeneration && tick.elapsedMillis - lostAt.elapsedMillis >= RIDE_ABSENCE_MILLIS
        val active = current.ride
        // A retired connection is a tombstone, not a new start trigger.
        if (!explicit && active == null && previousGeneration != null && generation == previousGeneration) return unchanged()
        if (active != null && active.vehicleId == vehicleId && !absent &&
            (sourcePlan == null || sourcePlan == current.sourcePlan)) {
            if (current.linkConnected && generation == previousGeneration) return unchanged()
            return RecordingTransition(current.copy(connectionGeneration = generation, linkConnected = true, lostAt = null),
                listOf(HistoryMarker(tick, HistoryMarkerKind.CONNECTED)), null, null)
        }
        val ride = HistoryRide(newId(), vehicleId, tick, null, HistoryRideStatus.RECORDING)
        val finished = active?.copy(ended = tick, status = HistoryRideStatus.COMPLETED)
        return RecordingTransition(RecordingIntent(ride, generation, false, true, sourcePlan?.toMap() ?: emptyMap()),
            listOf(HistoryMarker(tick, HistoryMarkerKind.CONNECTED)), finished, ride,
            if (finished != null && !absent) listOf(HistoryMarker(tick,
                if (finished.vehicleId == vehicleId) HistoryMarkerKind.SOURCE_CHANGE else HistoryMarkerKind.VEHICLE_SWITCH))
            else emptyList())
    }
    return when (input) {
        is RecordingInput.Selected -> {
            require(input.vehicleId.isNotBlank())
            val active = current.ride
            if ((input.generation != null && input.generation != current.connectionGeneration) || active == null ||
                (active.vehicleId == input.vehicleId && (sourcePlan == null || sourcePlan == current.sourcePlan))) unchanged()
            else RecordingTransition(current.copy(ride = null, linkConnected = false, sourcePlan = emptyMap(), lostAt = null),
                emptyList(), active.copy(ended = input.tick, status = HistoryRideStatus.COMPLETED), null,
                listOf(HistoryMarker(input.tick, if (active.vehicleId == input.vehicleId)
                    HistoryMarkerKind.SOURCE_CHANGE else HistoryMarkerKind.VEHICLE_SWITCH)))
        }
        is RecordingInput.Connected -> connect(input.vehicleId, input.generation, input.tick, explicit = false)
        is RecordingInput.Start -> connect(input.vehicleId, input.generation, input.tick, explicit = true)
        is RecordingInput.LinkLost -> {
            if (input.generation != current.connectionGeneration || current.ride == null || !current.linkConnected) unchanged()
            else RecordingTransition(current.copy(linkConnected = false, lostAt = input.tick),
                listOf(HistoryMarker(input.tick, HistoryMarkerKind.BLE_GAP)), null, null)
        }
        is RecordingInput.Stop -> {
            if ((input.generation != null && input.generation != current.connectionGeneration) ||
                (input.rideId != null && input.rideId != current.ride?.id)) unchanged()
            else stopped(input.tick, manual = true)
        }
        is RecordingInput.Absent -> {
            val ride = current.ride
            if (ride == null || current.linkConnected || input.generation != current.connectionGeneration) unchanged()
            // Forget the generation too: the morning reconnect reuses it and must start a new ride,
            // not hit the retired-connection tombstone.
            else RecordingTransition(RecordingIntent(), emptyList(),
                ride.copy(ended = input.tick, status = HistoryRideStatus.COMPLETED), null)
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

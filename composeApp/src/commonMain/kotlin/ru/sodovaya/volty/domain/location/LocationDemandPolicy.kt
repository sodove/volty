package ru.sodovaya.volty.domain.location

data class LocationDemandPolicyState(
    val demands: Set<LocationConsumer> = emptySet(),
    val status: RideLocationStatus = RideLocationStatus.NotRequested,
    val generation: Long = 0L,
    val lastAcceptedFix: RideLocationFix? = null,
    val gpsAccepted: Boolean = false,
)

data class LocationDemandTransition(
    val state: LocationDemandPolicyState,
    val changed: Boolean,
    val shouldStart: Boolean,
    val shouldStop: Boolean,
)

data class LocationFixAcceptance(
    val state: LocationDemandPolicyState,
    val accepted: Boolean,
)

/** Pure state transitions shared by all platform owners and consumers. */
object LocationDemandPolicy {
    val initialState: LocationDemandPolicyState = LocationDemandPolicyState()

    fun setDemand(
        state: LocationDemandPolicyState,
        consumer: LocationConsumer,
        enabled: Boolean,
    ): LocationDemandTransition {
        val updatedDemands = if (enabled) state.demands + consumer else state.demands - consumer
        if (updatedDemands == state.demands) {
            return LocationDemandTransition(
                state = state,
                changed = false,
                shouldStart = false,
                shouldStop = false,
            )
        }

        val wasEmpty = state.demands.isEmpty()
        val isEmpty = updatedDemands.isEmpty()
        val nextState = when {
            wasEmpty && !isEmpty -> state.copy(
                demands = updatedDemands,
                status = RideLocationStatus.Searching,
                generation = state.generation + 1L,
                lastAcceptedFix = null,
                gpsAccepted = false,
            )
            !wasEmpty && isEmpty -> state.copy(
                demands = emptySet(),
                status = RideLocationStatus.NotRequested,
                generation = state.generation + 1L,
                lastAcceptedFix = null,
                gpsAccepted = false,
            )
            else -> state.copy(demands = updatedDemands)
        }
        return LocationDemandTransition(
            state = nextState,
            changed = true,
            shouldStart = wasEmpty && !isEmpty,
            shouldStop = !wasEmpty && isEmpty,
        )
    }

    fun setStatus(
        state: LocationDemandPolicyState,
        status: RideLocationStatus,
    ): LocationDemandPolicyState = if (state.demands.isEmpty()) {
        state.copy(status = RideLocationStatus.NotRequested)
    } else {
        state.copy(status = status)
    }

    /** Starts a new platform registration generation without changing demand ownership. */
    fun restart(state: LocationDemandPolicyState): LocationDemandPolicyState = if (state.demands.isEmpty()) {
        state.copy(
            status = RideLocationStatus.NotRequested,
            generation = state.generation + 1L,
            lastAcceptedFix = null,
            gpsAccepted = false,
        )
    } else {
        state.copy(
            status = RideLocationStatus.Searching,
            generation = state.generation + 1L,
            lastAcceptedFix = null,
            gpsAccepted = false,
        )
    }

    fun acceptFix(
        state: LocationDemandPolicyState,
        callbackGeneration: Long,
        fix: RideLocationFix,
    ): LocationFixAcceptance {
        if (state.demands.isEmpty() || callbackGeneration != state.generation) {
            return LocationFixAcceptance(state = state, accepted = false)
        }
        if (state.gpsAccepted && fix.source != LocationSource.GPS) {
            return LocationFixAcceptance(state = state, accepted = false)
        }
        val previous = state.lastAcceptedFix
        if (previous != null && !isNewer(previous, fix)) {
            return LocationFixAcceptance(state = state, accepted = false)
        }
        val nextState = state.copy(
            status = RideLocationStatus.Available(fix),
            lastAcceptedFix = fix,
            gpsAccepted = state.gpsAccepted || fix.source == LocationSource.GPS,
        )
        return LocationFixAcceptance(state = nextState, accepted = true)
    }

    private fun isNewer(previous: RideLocationFix, candidate: RideLocationFix): Boolean {
        val previousElapsed = previous.elapsedRealtimeMillis
        val candidateElapsed = candidate.elapsedRealtimeMillis
        return if (previousElapsed != null && candidateElapsed != null) {
            candidateElapsed > previousElapsed
        } else {
            candidate.capturedAtEpochMillis > previous.capturedAtEpochMillis
        }
    }
}

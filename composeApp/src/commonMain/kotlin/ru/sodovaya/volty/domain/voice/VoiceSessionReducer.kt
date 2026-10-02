package ru.sodovaya.volty.domain.voice

object VoiceSessionReducer {
    /** Effects must be dispatched in order by a single owner; acknowledgements are tagged. */
    fun reduce(state: VoiceSessionState, event: VoiceSessionEvent): VoiceTransition = when (event) {
        is VoiceSessionEvent.Join -> join(state, event)
        is VoiceSessionEvent.SessionGranted -> grant(state, event)
        VoiceSessionEvent.Stop -> stop(state)
        is VoiceSessionEvent.Stopped -> if (event.generation == state.generation && !state.wanted) {
            unchanged(state.copy(
                cleanupPending = false,
                phase = if (state.phase == VoicePhase.STOPPING) VoicePhase.IDLE else state.phase,
            ))
        } else unchanged(state)
        is VoiceSessionEvent.Failed -> if (event.generation == state.generation && state.wanted) {
            val stopped = stop(state)
            stopped.copy(state = stopped.state.copy(phase = VoicePhase.FAILED))
        } else unchanged(state)
    }

    private fun join(state: VoiceSessionState, event: VoiceSessionEvent.Join): VoiceTransition {
        // An intentional retry must use a fresh operation ID; cancelled IDs have backend tombstones.
        if (event.operationId == state.operationId) return unchanged(state)
        val generation = VoiceGeneration(state.generation.value + 1)
        val effects = if (state.wanted) stop(state).effects else emptyList()
        return VoiceTransition(
            VoiceSessionState(generation, event.operationId, phase = VoicePhase.JOINING, wanted = true),
            effects + VoiceSessionEffect.RequestJoin(event.operationId, generation),
        )
    }

    private fun grant(state: VoiceSessionState, event: VoiceSessionEvent.SessionGranted): VoiceTransition {
        // Never close the currently owned session on a repeated delivery of its grant.
        if (state.wanted && state.sessionId == event.sessionId) return unchanged(state)
        if (!state.wanted || state.generation != event.generation || state.operationId != event.operationId ||
            state.sessionId != null
        ) return VoiceTransition(state, listOf(VoiceSessionEffect.StopSession(event.sessionId)))
        return VoiceTransition(
            state.copy(sessionId = event.sessionId, phase = VoicePhase.CONNECTING),
            listOf(VoiceSessionEffect.StartEngine(VoiceSessionTag(event.sessionId, event.generation))),
        )
    }

    private fun stop(state: VoiceSessionState): VoiceTransition {
        if (!state.wanted) return unchanged(state)
        val effects = buildList {
            if (state.sessionId == null) state.operationId?.let {
                add(VoiceSessionEffect.CancelJoin(it, state.generation))
            }
            add(VoiceSessionEffect.StopEngine(state.generation))
            state.sessionId?.let { add(VoiceSessionEffect.StopSession(it)) }
            add(VoiceSessionEffect.RemoveNearbyDemand)
            add(VoiceSessionEffect.ClearSharingQueue)
        }
        return VoiceTransition(state.copy(wanted = false, cleanupPending = true, phase = VoicePhase.STOPPING), effects)
    }

    private fun unchanged(state: VoiceSessionState) = VoiceTransition(state, emptyList())
}

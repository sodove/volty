package ru.sodovaya.volty.domain.voice

object VoiceSessionReducer {
    private const val LEASE_ACK_TIMEOUT_MILLIS = 30_000L
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
        is VoiceSessionEvent.Tagged -> media(state, event)
        is VoiceSessionEvent.Tick -> if (hasExpiredLease(state, event.nowMillis)) stop(state) else unchanged(state)
        is VoiceSessionEvent.SetMuted -> setMuted(state, event.value)
        is VoiceSessionEvent.SetDeafened -> setDeafened(state, event.value)
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
            VoiceSessionState(generation, event.operationId, phase = VoicePhase.JOINING, wanted = true,
                muted = state.muted, deafened = state.deafened),
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
            state.copy(sessionId = event.sessionId, phase = VoicePhase.CONNECTING, lastLeaseAckMillis = event.nowMillis),
            listOf(VoiceSessionEffect.StartEngine(VoiceSessionTag(event.sessionId, event.generation), state.muted, state.deafened)),
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
        return VoiceTransition(state.withoutMediaEvidence().copy(
            wanted = false, cleanupPending = true, phase = VoicePhase.STOPPING, lastLeaseAckMillis = null,
        ), effects)
    }

    private fun media(state: VoiceSessionState, event: VoiceSessionEvent.Tagged): VoiceTransition {
        if (!state.wanted || state.sessionId != event.tag.sessionId || state.generation != event.tag.generation) {
            return unchanged(state)
        }
        return when (event) {
            is VoiceSessionEvent.TransportConnected -> VoiceTransition(
                state.copy(transportConnected = true, phase = VoicePhase.CONNECTED),
                if (state.phase == VoicePhase.RECONNECTING) listOf(
                    VoiceSessionEffect.SetMuted(event.tag, state.muted),
                    VoiceSessionEffect.SetDeafened(event.tag, state.deafened),
                ) else emptyList(),
            )
            is VoiceSessionEvent.CaptureStarted -> unchanged(state.copy(captureStarted = true))
            is VoiceSessionEvent.CaptureStopped -> unchanged(state.copy(captureStarted = false))
            is VoiceSessionEvent.Published -> unchanged(state.copy(published = true))
            is VoiceSessionEvent.Unpublished -> unchanged(state.copy(published = false))
            is VoiceSessionEvent.Subscribed -> unchanged(state.copy(subscribedPeers = state.subscribedPeers + event.peer))
            is VoiceSessionEvent.Unsubscribed -> unchanged(state.copy(subscribedPeers = state.subscribedPeers - event.peer))
            is VoiceSessionEvent.RenderStarted -> unchanged(state.copy(renderStarted = true))
            is VoiceSessionEvent.RenderStopped -> unchanged(state.copy(renderStarted = false))
            is VoiceSessionEvent.Reconnecting -> unchanged(state.withoutMediaEvidence().copy(phase = VoicePhase.RECONNECTING))
            is VoiceSessionEvent.LeaseAck -> when {
                hasExpiredLease(state, event.nowMillis) -> stop(state)
                event.nowMillis >= (state.lastLeaseAckMillis ?: event.nowMillis) ->
                    unchanged(state.copy(lastLeaseAckMillis = event.nowMillis))
                else -> unchanged(state)
            }
        }
    }

    private fun hasExpiredLease(state: VoiceSessionState, nowMillis: Long): Boolean {
        if (!state.wanted || state.sessionId == null) return false
        val lastAck = state.lastLeaseAckMillis ?: return true
        return nowMillis > lastAck && nowMillis - lastAck > LEASE_ACK_TIMEOUT_MILLIS
    }

    private fun setMuted(state: VoiceSessionState, value: Boolean): VoiceTransition {
        if (state.muted == value) return unchanged(state)
        val effects = state.activeTag()?.let { listOf(VoiceSessionEffect.SetMuted(it, value)) } ?: emptyList()
        return VoiceTransition(state.copy(muted = value), effects)
    }

    private fun setDeafened(state: VoiceSessionState, value: Boolean): VoiceTransition {
        if (state.deafened == value) return unchanged(state)
        val effects = state.activeTag()?.let { listOf(VoiceSessionEffect.SetDeafened(it, value)) } ?: emptyList()
        return VoiceTransition(state.copy(deafened = value), effects)
    }

    private fun VoiceSessionState.activeTag(): VoiceSessionTag? =
        sessionId?.takeIf { wanted }?.let { VoiceSessionTag(it, generation) }

    /** Clears readiness for the requested session; engine release still needs Stopped. */
    private fun VoiceSessionState.withoutMediaEvidence() = copy(
        transportConnected = false, captureStarted = false, published = false,
        subscribedPeers = emptySet(), renderStarted = false,
    )

    private fun unchanged(state: VoiceSessionState) = VoiceTransition(state, emptyList())
}

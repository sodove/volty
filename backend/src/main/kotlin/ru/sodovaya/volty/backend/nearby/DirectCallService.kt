package ru.sodovaya.volty.backend.nearby

data class DirectCallAction(val decision: DirectCallDecision, val session: SessionAction? = null, val failure: SfuFailure? = null)

/** Blocking bounded composition; call/claim ownership remains in the SQL pair owners. */
class DirectCallService(
    private val ringing: JdbcDirectCallRingingRepository,
    private val media: JdbcDirectCallMediaRepository,
    private val repository: SessionRepository,
    private val voice: SessionService,
    private val sfu: SfuLeaseClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun create(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision =
        cleanupTerminal(actor, reconcileAccepted(actor, ringing.create(actor, callee, call, key, operation, now)))

    fun status(actor: String, call: String): DirectCallDecision =
        cleanupTerminal(actor, reconcileAccepted(actor, ringing.status(actor, call, now)))

    fun accept(actor: String, call: String, operation: String, revision: Long): DirectCallAction {
        require(revision >= 0)
        val lookup = ringing.status(actor, call, now)
        val found = lookup.call ?: return DirectCallAction(lookup)
        if (actor != found.calleeId) return DirectCallAction(lookup.copy(rejection = DirectCallRejection.ACCESS_DENIED))
        val decision = cleanupTerminal(actor, reconcileAccepted(actor, lookup))
        val current = checkNotNull(decision.call)
        if (current.terminal) return DirectCallAction(decision.copy(rejection = DirectCallRejection.TERMINAL))
        if (decision.rejection != null) return DirectCallAction(decision)
        val epoch = if (current.phase == DirectCallPhase.ACCEPTED) {
            val original = repository.get(actor, operation)
            if (original?.operation?.destinationKind != VoiceDestinationKind.DIRECT_CALL || original.operation.destinationId != call)
                return DirectCallAction(decision.copy(rejection = DirectCallRejection.IDENTITY_MISMATCH))
            checkNotNull(original.sfuEpoch)
        } else {
            if (current.revision != revision) return DirectCallAction(decision.copy(rejection = DirectCallRejection.REVISION_MISMATCH))
            val response = sfu.inspect("volty-call-$call")
            if (response is SfuReply.Rejected) return inspectionFailure(actor, call, response.reason)
            val node = (response as SfuReply.Accepted).value
            if (!node.ready || !node.singleNode || !node.canRegisterNew) return inspectionFailure(actor, call, SfuFailure.UNAVAILABLE)
            try {SfuEpoch("volty-call-$call", node.nodeEpoch)}
            catch (_: IllegalArgumentException) {return inspectionFailure(actor, call, SfuFailure.INVALID_RESPONSE)}
        }
        val prepared = media.accept(actor, call, operation, revision, epoch, now)
        if (prepared.rejection != null || prepared.call?.phase != DirectCallPhase.ACCEPTED)
            return DirectCallAction(cleanupTerminal(actor, prepared))
        return resume(actor, call, operation)
    }

    fun resume(actor: String, call: String, operation: String): DirectCallAction {
        val original = repository.get(actor, operation)
        if (original?.operation?.destinationKind != VoiceDestinationKind.DIRECT_CALL || original.operation.destinationId != call)
            return DirectCallAction(DirectCallDecision(null, DirectCallRejection.IDENTITY_MISMATCH))
        val before = status(actor, call)
        val current = before.call ?: return DirectCallAction(before)
        if (current.terminal) return DirectCallAction(before, voice.status(actor, operation))
        if (current.phase != DirectCallPhase.ACCEPTED || before.rejection != null) return DirectCallAction(before)
        val action = voice.resumeCall(actor, operation, call)
        val latest = status(actor, call)
        // Never return a bearer when a concurrent stop won after local issuance.
        val authorized = if (latest.call == null || latest.rejection != null) voice.stop(actor, operation)
            else if (latest.call.terminal) voice.status(actor, operation) else action
        return DirectCallAction(latest, authorized)
    }

    fun stop(actor: String, call: String): DirectCallDecision = cleanupTerminal(actor, media.stop(actor, call, now))

    fun cancelOutgoing(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision {
        val original = ringing.cancelOutgoingRinging(actor, callee, call, key, operation, now)
        return if (original.call?.phase == DirectCallPhase.ACCEPTED && original.rejection == DirectCallRejection.NOT_RINGING)
            stop(actor, original.call.callId) else cleanupTerminal(actor, original)
    }

    fun decline(actor: String, call: String): DirectCallDecision = ringing.decline(actor, call, now)
    fun incoming(actor: String, limit: Int, cursor: String?): IncomingCallPage = ringing.incoming(actor, limit, cursor, now)

    private fun reconcileAccepted(actor: String, decision: DirectCallDecision): DirectCallDecision =
        if (decision.call?.phase == DirectCallPhase.ACCEPTED && decision.rejection == DirectCallRejection.NOT_RINGING)
            media.reconcile(actor, decision.call.callId, now) else decision

    private fun inspectionFailure(actor: String, call: String, failure: SfuFailure): DirectCallAction {
        // Earlier failures returned the preflight RINGING row even after an in-flight stop.
        val latest = status(actor, call)
        return DirectCallAction(latest, failure = failure.takeUnless {latest.call?.terminal == true})
    }

    private fun cleanupTerminal(actor: String, decision: DirectCallDecision): DirectCallDecision {
        val original = decision.call ?: return decision
        if (original.terminal && decision.rejection != DirectCallRejection.ACCESS_DENIED) {
            for (row in media.terminalSessions(actor, original.callId)) {
                try {voice.stop(row.operation.actorId, row.operation.operationId)}
                catch (_: Exception) {
                    // SQL stop already committed. Preserve actual recorded ACK evidence;
                    // this failure never prevents attempting the original peer cleanup.
                }
            }
        }
        return decision
    }
}

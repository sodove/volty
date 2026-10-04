package ru.sodovaya.volty.backend.nearby

import java.util.UUID

enum class SessionDenial { MEMBERSHIP, OPERATION_CONFLICT, BUSY }
sealed interface SessionAction {
    data class Ready(val credentials: SessionCredentials, val remainingMillis: Long) : SessionAction {
        init { require(remainingMillis in 1..30_000) }
    }
    data class Confirmed(val sessionId: String, val deadlineEpochMillis: Long,
                         val remainingMillis: Long, val media: SessionMedia = SessionMedia()) : SessionAction {
        init { require(remainingMillis in 1..30_000) }
    }
    data class Pending(val state: SessionJournalEntry) : SessionAction
    data class Ended(val state: SessionJournalEntry) : SessionAction
    data class Unavailable(val reason: SfuFailure) : SessionAction
    data class Denied(val reason: SessionDenial) : SessionAction
}

/** Blocking bounded ports; invoke on an owned I/O worker. No SFU request holds a SQL lock. */
class SessionService(
    private val repository: SessionRepository,
    private val sfu: SfuLeaseClient,
    private val resolveRoom: (actor: String, destination: String) -> String?,
    private val issuer: SessionCredentialIssuer,
    private val now: () -> Long = System::currentTimeMillis,
    private val singletonOrigin: Boolean = false,
    private val resolveTypedRoom: ((actor: String, destination: String, kind: VoiceDestinationKind) -> String?)? = null,
) {
    /** Only the original SQL-prepared participant may continue admission; this never begins a call. */
    internal fun resumeCall(actor: String, operation: String, call: String): SessionAction {
        val original = repository.get(actor, operation)
            ?: return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        if (original.operation.destinationKind != VoiceDestinationKind.DIRECT_CALL || original.operation.destinationId != call)
            return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        val current = checkNotNull(repository.expire(actor, operation, now).state)
        if (current.operation.terminal) {cleanup(current); return result(checkNotNull(repository.get(actor, operation)), false)}
        // Earlier implementation skipped room resolution on ACTIVE replay, unlike channel join.
        if (roomFor(current) != current.sfuEpoch!!.roomEpoch) return endMedia(actor, operation)
        return result(advancePending(current), credentials = true)
    }

    fun join(actor: String, operation: String, destination: String, replace: Boolean = false): SessionAction {
        val existing = repository.get(actor, operation)
        if (existing != null && existing.operation.terminal) return SessionAction.Ended(existing)
        if (repository.isDirectCallOperation(actor, operation))
            return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        if (existing != null && existing.operation.destinationId != destination) return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        val room = resolveRoom(actor, destination)
        if (room == null) {
            val invalidated = repository.invalidateMedia(actor, operation, now)
            if (invalidated.rejection != null) return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
            invalidated.state?.let(::cleanup)
            return SessionAction.Denied(SessionDenial.MEMBERSHIP)
        }
        val observed = sfu.inspect(room)
        if (observed is SfuReply.Rejected) {
            if (existing == null) repository.cancel(actor, operation, now)
            return SessionAction.Unavailable(observed.reason)
        }
        val status = (observed as SfuReply.Accepted).value
        if (!status.singleNode || !status.ready || (existing == null && !status.canRegisterNew)) {
            if (existing == null) repository.cancel(actor, operation, now)
            return SessionAction.Unavailable(SfuFailure.UNAVAILABLE)
        }
        val epoch = SfuEpoch(room, status.nodeEpoch)
        if (existing != null && existing.sfuEpoch != epoch) return endMedia(actor, operation)
        val begun = repository.begin(actor, operation, destination, UUID.randomUUID().toString(), epoch, now, replace)
        if (begun.rejection == OperationRejection.ACCESS_DENIED) {
            begun.state?.let(::cleanup)
            return SessionAction.Denied(SessionDenial.MEMBERSHIP)
        }
        if (begun.rejection == OperationRejection.CLAIM_BUSY) return SessionAction.Denied(SessionDenial.BUSY)
        if (begun.rejection != null && begun.state == null) return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        val row = checkNotNull(begun.state)
        if (row.operation.terminal) return SessionAction.Ended(row)
        if (begun.rejection != null) return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        return result(advancePending(row), credentials = true)
    }

    fun status(actor: String, operation: String): SessionAction? {
        val row = repository.expire(actor, operation, now).state ?: return null
        if (row.operation.terminal) cleanup(row)
        return result(checkNotNull(repository.get(actor, operation)), credentials = false)
    }

    fun stop(actor: String, operation: String): SessionAction.Ended {
        val row = checkNotNull(repository.cancel(actor, operation, now).state)
        cleanup(row)
        return SessionAction.Ended(checkNotNull(repository.get(actor, operation)))
    }

    // Earlier revisions used explicit user stop for autonomous failures, falsely attributing
    // accepted-call media loss to the caller/callee. Keep intent and internal loss distinct.
    private fun endMedia(actor: String, operation: String): SessionAction.Ended {
        val row = checkNotNull(repository.invalidateMedia(actor, operation, now).state)
        cleanup(row)
        return SessionAction.Ended(checkNotNull(repository.get(actor, operation)))
    }

    fun heartbeat(actor: String, operation: String): SessionAction {
        val row = repository.expire(actor, operation, now).state
            ?: return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        if (row.operation.terminal) { cleanup(row); return result(checkNotNull(repository.get(actor, operation)), false) }
        if (row.operation.phase != OperationPhase.ACTIVE) return SessionAction.Pending(row)
        if (roomFor(row) != row.sfuEpoch!!.roomEpoch) return endMedia(actor, operation)
        val observed = observeCurrent(row)
        if (observed is SfuReply.Rejected) return SessionAction.Unavailable(observed.reason)
        val current = (observed as SfuReply.Accepted).value
        if (current.operation.terminal) return result(current, false)
        // Registration/signalling alone never earns another thirty seconds.
        if (current.media.state != SfuParticipantState.ACTIVE) return result(current, false)
        val mediaClient = sfu as? SfuConnectedLeaseClient ?: return SessionAction.Unavailable(SfuFailure.UNSUPPORTED)
        val response = mediaClient.renewConnected(current.sfuEpoch!!.roomEpoch, current.operation.sessionId!!,
            current.sfuEpoch.nodeEpoch, current.media.participantSid!!)
        if (response is SfuReply.Rejected) {
            // A different/new JOINING SID may have replaced the observation; don't cancel it.
            val latest = checkNotNull(repository.expire(actor, operation, now).state)
            if (latest.operation.terminal) { cleanup(latest); return result(checkNotNull(repository.get(actor, operation)), false) }
            return SessionAction.Unavailable(response.reason)
        }
        val receipt = (response as SfuReply.Accepted).value
        val recorded = repository.heartbeatConnected(actor, operation, current.operation.sessionId,
            current.sfuEpoch, current.media.observationVersion, current.media.participantSid, now, receipt.conservativeDeadlineEpochMillis)
        val updated = checkNotNull(recorded.state)
        if (updated.operation.terminal) cleanup(updated)
        else if (recorded.rejection != null) return SessionAction.Unavailable(SfuFailure.UNAVAILABLE)
        return result(checkNotNull(repository.get(actor, operation)), false)
    }

    /** Reconcile signed notification intent without renewing admission or minting credentials. */
    fun refreshMedia(actor: String, operation: String): SessionAction {
        val row = repository.expire(actor, operation, now).state
            ?: return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        if (row.operation.terminal) { cleanup(row); return result(checkNotNull(repository.get(actor, operation)), false) }
        if (row.operation.phase != OperationPhase.ACTIVE) return SessionAction.Pending(row)
        if (roomFor(row) != row.sfuEpoch!!.roomEpoch) return endMedia(actor, operation)
        return when (val observed = observeCurrent(row)) {
            is SfuReply.Accepted -> result(observed.value, false)
            is SfuReply.Rejected -> SessionAction.Unavailable(observed.reason)
        }
    }

    /** Current live lookup is bounded and outside the actor transaction; no stale-cache fallback. */
    private fun observeCurrent(row: SessionJournalEntry): SfuReply<SessionJournalEntry> {
        val mediaClient = sfu as? SfuConnectedLeaseClient ?: return SfuReply.Rejected(SfuFailure.UNSUPPORTED)
        val epoch = checkNotNull(row.sfuEpoch)
        val observed = mediaClient.observeParticipant(epoch.roomEpoch, row.operation.sessionId!!, epoch.nodeEpoch)
        if (observed is SfuReply.Rejected) {
            // Unlike a conditional renewal SID mismatch, gate observation rejection means no valid admission.
            if (observed.reason == SfuFailure.REJECTED) return SfuReply.Accepted(endMedia(row.operation.actorId, row.operation.operationId).state)
            return observed
        }
        val recorded = repository.observeMedia(row.operation.actorId, row.operation.operationId, row.operation.sessionId,
            epoch, row.media.observationVersion, (observed as SfuReply.Accepted).value, now)
        val current = checkNotNull(recorded.state)
        if (current.operation.terminal) {
            cleanup(current)
            return SfuReply.Accepted(checkNotNull(repository.get(row.operation.actorId, row.operation.operationId)))
        }
        if (recorded.rejection != null) return SfuReply.Rejected(SfuFailure.UNAVAILABLE)
        return SfuReply.Accepted(current)
    }

    /** One bounded work item; the application owns scheduling/cancellation. No credentials minted. */
    fun reconcileNext(actor: String? = null): Boolean {
        val due = (if (actor == null) repository.dueOperations(now(), 1) else repository.dueOperations(actor, now(), 1)).firstOrNull()
        if (due != null) {
            val expired = checkNotNull(repository.expire(due.operation.actorId, due.operation.operationId, now).state)
            cleanup(expired)
            return true
        }
        val work = (if (actor == null) repository.pendingWork(1) else repository.pendingWork(actor, 1)).firstOrNull() ?: return false
        if (work.cleanupPending) cleanup(work) else advancePending(work)
        return true
    }

    private fun advancePending(seed: SessionJournalEntry): SessionJournalEntry {
        val actor = seed.operation.actorId
        val operation = seed.operation.operationId
        var row = checkNotNull(repository.expire(actor, operation, now).state)
        if (row.operation.terminal) { cleanup(row); return checkNotNull(repository.get(actor, operation)) }
        if (row.operation.phase == OperationPhase.ACTIVE) return row
        if (roomFor(row) != row.sfuEpoch!!.roomEpoch) return endMedia(actor, operation).state
        repository.pendingCleanup(actor, 1).firstOrNull()?.let { cleanup(it) }
        if (repository.pendingCleanup(actor, 1).isNotEmpty()) return checkNotNull(repository.get(actor, operation))
        row = checkNotNull(repository.expire(actor, operation, now).state)
        if (row.operation.terminal) { cleanup(row); return checkNotNull(repository.get(actor, operation)) }
        val expected = SessionClaim(operation, row.operation.sessionId!!, row.sfuEpoch!!)
        if (repository.currentClaim(actor) != expected) return endMedia(actor, operation).state
        val registered = sfu.register(row.sfuEpoch.roomEpoch, row.operation.sessionId, row.sfuEpoch.nodeEpoch)
        if (registered is SfuReply.Rejected) {
            if (registered.reason == SfuFailure.REJECTED) return endMedia(actor, operation).state
            return checkNotNull(repository.get(actor, operation))
        }
        val receipt = (registered as SfuReply.Accepted).value
        val activated = checkNotNull(repository.admit(actor, operation, row.operation.sessionId, row.sfuEpoch,
            now, receipt.conservativeDeadlineEpochMillis).state)
        if (activated.operation.terminal) cleanup(activated)
        return checkNotNull(repository.get(actor, operation))
    }

    private fun roomFor(row: SessionJournalEntry): String? {
        val kind = checkNotNull(row.operation.destinationKind)
        val destination = checkNotNull(row.operation.destinationId)
        return if (resolveTypedRoom != null) resolveTypedRoom.invoke(row.operation.actorId, destination, kind)
        else if (kind == VoiceDestinationKind.CHANNEL) resolveRoom(row.operation.actorId, destination)
        else null
    }

    private fun cleanup(row: SessionJournalEntry) {
        if (!row.operation.terminal || row.operation.sessionId == null || row.sfuEpoch == null) return
        val response = sfu.revoke(row.sfuEpoch.roomEpoch, row.operation.sessionId, row.sfuEpoch.nodeEpoch)
        // This fallback requires an operator-owned singleton origin: no replica or load balancer.
        // A readiness flag alone cannot prove an old process has stopped in a distributed deployment.
        val replacedSingleton = singletonOrigin && response is SfuReply.Rejected && response.reason == SfuFailure.REJECTED &&
            sfu.inspect(row.sfuEpoch.roomEpoch).let { observed ->
                observed is SfuReply.Accepted && observed.value.singleNode && observed.value.nodeEpoch != row.sfuEpoch.nodeEpoch
            }
        if (response is SfuReply.Accepted || replacedSingleton) repository.completeCleanup(row.operation.actorId,
            row.operation.operationId, row.operation.sessionId, row.sfuEpoch)
    }

    private fun result(row: SessionJournalEntry, credentials: Boolean): SessionAction {
        if (row.operation.terminal) return SessionAction.Ended(row)
        if (row.operation.phase != OperationPhase.ACTIVE) return SessionAction.Pending(row)
        val authorized = try {
            repository.withActiveSession(row.operation.actorId, row.operation.operationId, now) { current ->
                // CPU-only issuance and the final credit calculation share current-claim authorization.
                // Earlier revisions returned a stale row for status and gave no remaining credit.
                val issued = if (credentials) issuer.issue(current) else null
                val deadline = checkNotNull(current.operation.deadlineEpochMillis)
                val authorizedAt = now()
                if (authorizedAt >= deadline) SessionAction.Unavailable(SfuFailure.UNAVAILABLE)
                else {
                    val remaining = minOf(30_000L, deadline - authorizedAt)
                    if (issued != null) SessionAction.Ready(issued, remaining)
                    else SessionAction.Confirmed(current.operation.sessionId!!, deadline, remaining, current.media)
                }
            }
        } catch (_: Exception) {
            endMedia(row.operation.actorId, row.operation.operationId)
            return SessionAction.Unavailable(SfuFailure.UNAVAILABLE)
        }
        if (authorized is SessionAction.Unavailable) {
            endMedia(row.operation.actorId, row.operation.operationId)
            return authorized
        }
        if (authorized != null) return authorized
        return endMedia(row.operation.actorId, row.operation.operationId)
    }
}

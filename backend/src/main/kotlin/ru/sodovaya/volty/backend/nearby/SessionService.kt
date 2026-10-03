package ru.sodovaya.volty.backend.nearby

import java.util.UUID

enum class SessionDenial { MEMBERSHIP, OPERATION_CONFLICT, BUSY }
sealed interface SessionAction {
    data class Ready(val credentials: SessionCredentials) : SessionAction
    data class Confirmed(val sessionId: String, val deadlineEpochMillis: Long) : SessionAction
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
) {
    fun join(actor: String, operation: String, destination: String, replace: Boolean = false): SessionAction {
        val existing = repository.get(actor, operation)
        if (existing != null && existing.operation.terminal) return SessionAction.Ended(existing)
        if (existing != null && existing.operation.destinationId != destination) return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        val room = resolveRoom(actor, destination)
        if (room == null) { stop(actor, operation); return SessionAction.Denied(SessionDenial.MEMBERSHIP) }
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
        if (existing != null && existing.sfuEpoch != epoch) return stop(actor, operation)
        val begun = repository.begin(actor, operation, destination, UUID.randomUUID().toString(), epoch, now, replace)
        if (begun.rejection == OperationRejection.CLAIM_BUSY) return SessionAction.Denied(SessionDenial.BUSY)
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

    fun heartbeat(actor: String, operation: String): SessionAction {
        val row = repository.expire(actor, operation, now).state
            ?: return SessionAction.Denied(SessionDenial.OPERATION_CONFLICT)
        if (row.operation.terminal) { cleanup(row); return result(checkNotNull(repository.get(actor, operation)), false) }
        if (row.operation.phase != OperationPhase.ACTIVE) return SessionAction.Pending(row)
        if (resolveRoom(actor, checkNotNull(row.operation.destinationId)) != row.sfuEpoch!!.roomEpoch) return stop(actor, operation)
        val response = sfu.renew(row.sfuEpoch.roomEpoch, row.operation.sessionId!!, row.sfuEpoch.nodeEpoch)
        if (response is SfuReply.Rejected) {
            if (response.reason == SfuFailure.REJECTED) return stop(actor, operation)
            return SessionAction.Unavailable(response.reason)
        }
        val receipt = (response as SfuReply.Accepted).value
        val updated = checkNotNull(repository.heartbeat(actor, operation, row.operation.sessionId,
            row.sfuEpoch, now, receipt.conservativeDeadlineEpochMillis).state)
        if (updated.operation.terminal) cleanup(updated)
        return result(checkNotNull(repository.get(actor, operation)), false)
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
        if (resolveRoom(actor, row.operation.destinationId!!) != row.sfuEpoch!!.roomEpoch) return stop(actor, operation).state
        repository.pendingCleanup(actor, 1).firstOrNull()?.let { cleanup(it) }
        if (repository.pendingCleanup(actor, 1).isNotEmpty()) return checkNotNull(repository.get(actor, operation))
        row = checkNotNull(repository.expire(actor, operation, now).state)
        if (row.operation.terminal) { cleanup(row); return checkNotNull(repository.get(actor, operation)) }
        val expected = SessionClaim(operation, row.operation.sessionId!!, row.sfuEpoch!!)
        if (repository.currentClaim(actor) != expected) return stop(actor, operation).state
        val registered = sfu.register(row.sfuEpoch.roomEpoch, row.operation.sessionId, row.sfuEpoch.nodeEpoch)
        if (registered is SfuReply.Rejected) {
            if (registered.reason == SfuFailure.REJECTED) return stop(actor, operation).state
            return checkNotNull(repository.get(actor, operation))
        }
        val receipt = (registered as SfuReply.Accepted).value
        val activated = checkNotNull(repository.admit(actor, operation, row.operation.sessionId, row.sfuEpoch,
            now, receipt.conservativeDeadlineEpochMillis).state)
        if (activated.operation.terminal) cleanup(activated)
        return checkNotNull(repository.get(actor, operation))
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
        if (!credentials) return SessionAction.Confirmed(row.operation.sessionId!!, row.operation.deadlineEpochMillis!!)
        val authorized = try {
            repository.withActiveSession(row.operation.actorId, row.operation.operationId, now) { issuer.issue(it) }
        } catch (_: Exception) {
            stop(row.operation.actorId, row.operation.operationId)
            return SessionAction.Unavailable(SfuFailure.UNAVAILABLE)
        }
        if (authorized != null) return SessionAction.Ready(authorized)
        return stop(row.operation.actorId, row.operation.operationId)
    }
}

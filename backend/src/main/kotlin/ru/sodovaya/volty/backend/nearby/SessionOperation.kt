package ru.sodovaya.volty.backend.nearby

enum class OperationPhase { PENDING, ACTIVE, CANCELLED, EXPIRED }
enum class VoiceDestinationKind { CHANNEL, DIRECT_CALL }
enum class OperationRejection { IDENTITY_MISMATCH, DESTINATION_MISMATCH, SESSION_MISMATCH, TERMINAL, NOT_ACTIVE, NOT_FOUND, CLAIM_MISMATCH, EPOCH_MISMATCH, CLAIM_BUSY, OBSERVATION_CHANGED, ACCESS_DENIED }

/** Stored under an actor/operation lock. Terminal rows retain session ownership for cleanup. */
data class SessionOperation(
    val actorId: String,
    val operationId: String,
    val destinationId: String?,
    val sessionId: String?,
    val phase: OperationPhase,
    val deadlineEpochMillis: Long?,
    val destinationKind: VoiceDestinationKind? = if (destinationId == null) null else VoiceDestinationKind.CHANNEL,
) {
    init {
        require(actorId.isNotBlank() && operationId.isNotBlank())
        require((destinationId == null) == (sessionId == null))
        require((destinationId == null) == (destinationKind == null))
        require(destinationId == null || destinationId.isNotBlank())
        require(sessionId == null || sessionId.isNotBlank())
        if (phase == OperationPhase.PENDING || phase == OperationPhase.ACTIVE) {
            require(sessionId != null && deadlineEpochMillis != null)
        }
    }

    val terminal: Boolean get() = phase == OperationPhase.CANCELLED || phase == OperationPhase.EXPIRED
}

/** Rejections can carry an expired next row; callers must persist it, not discard it. */
data class OperationDecision(val operation: SessionOperation?, val rejection: OperationRejection? = null)

/** Pure policy only. It neither persists rows, registers SFU leases nor authorizes membership. */
class SessionOperationPolicy {
    fun begin(
        existing: SessionOperation?, actorId: String, operationId: String,
        destinationId: String, sessionId: String, nowEpochMillis: Long,
        destinationKind: VoiceDestinationKind = VoiceDestinationKind.CHANNEL,
    ): OperationDecision {
        validateTime(nowEpochMillis)
        require(actorId.isNotBlank() && operationId.isNotBlank() && destinationId.isNotBlank() && sessionId.isNotBlank())
        if (existing != null) {
            if (!existing.matches(actorId, operationId)) return rejected(existing, OperationRejection.IDENTITY_MISMATCH)
            val current = expiredAt(existing, nowEpochMillis)
            if (current.terminal) return rejected(current, OperationRejection.TERMINAL)
            if (current.destinationId != destinationId || current.destinationKind != destinationKind)
                return rejected(current, OperationRejection.DESTINATION_MISMATCH)
            return OperationDecision(current)
        }
        return OperationDecision(SessionOperation(actorId, operationId, destinationId, sessionId,
            OperationPhase.PENDING, Math.addExact(nowEpochMillis, PENDING_TTL_MILLIS), destinationKind))
    }

    fun cancel(existing: SessionOperation?, actorId: String, operationId: String, nowEpochMillis: Long): OperationDecision {
        validateTime(nowEpochMillis)
        if (existing == null) return OperationDecision(SessionOperation(actorId, operationId, null, null, OperationPhase.CANCELLED, null))
        if (!existing.matches(actorId, operationId)) return rejected(existing, OperationRejection.IDENTITY_MISMATCH)
        val current = expiredAt(existing, nowEpochMillis)
        return OperationDecision(if (current.terminal) current else current.copy(phase = OperationPhase.CANCELLED))
    }

    fun admit(existing: SessionOperation, sessionId: String, nowEpochMillis: Long, confirmedLeaseDeadlineEpochMillis: Long): OperationDecision {
        validateTime(nowEpochMillis)
        if (existing.sessionId != sessionId) return rejected(existing, OperationRejection.SESSION_MISMATCH)
        val current = expiredAt(existing, nowEpochMillis)
        if (current.terminal) return rejected(current, OperationRejection.TERMINAL)
        if (current.phase == OperationPhase.ACTIVE) return OperationDecision(current)
        if (confirmedLeaseDeadlineEpochMillis <= nowEpochMillis) return rejected(current.copy(phase = OperationPhase.EXPIRED), OperationRejection.TERMINAL)
        return OperationDecision(current.copy(phase = OperationPhase.ACTIVE,
            deadlineEpochMillis = confirmedDeadline(nowEpochMillis, confirmedLeaseDeadlineEpochMillis)))
    }

    fun heartbeat(existing: SessionOperation, sessionId: String, nowEpochMillis: Long, confirmedLeaseDeadlineEpochMillis: Long): OperationDecision {
        validateTime(nowEpochMillis)
        if (existing.sessionId != sessionId) return rejected(existing, OperationRejection.SESSION_MISMATCH)
        val current = expiredAt(existing, nowEpochMillis)
        if (current.terminal) return rejected(current, OperationRejection.TERMINAL)
        if (current.phase != OperationPhase.ACTIVE) return rejected(current, OperationRejection.NOT_ACTIVE)
        if (confirmedLeaseDeadlineEpochMillis <= nowEpochMillis) return rejected(current.copy(phase = OperationPhase.EXPIRED), OperationRejection.TERMINAL)
        return OperationDecision(current.copy(deadlineEpochMillis = confirmedDeadline(nowEpochMillis, confirmedLeaseDeadlineEpochMillis)))
    }

    fun expire(existing: SessionOperation, nowEpochMillis: Long): OperationDecision {
        validateTime(nowEpochMillis)
        return OperationDecision(expiredAt(existing, nowEpochMillis))
    }

    private fun expiredAt(existing: SessionOperation, now: Long): SessionOperation =
        if (!existing.terminal && now >= checkNotNull(existing.deadlineEpochMillis)) existing.copy(phase = OperationPhase.EXPIRED) else existing

    private fun SessionOperation.matches(actor: String, operation: String) = actorId == actor && operationId == operation
    private fun rejected(existing: SessionOperation, reason: OperationRejection) = OperationDecision(existing, reason)
    private fun validateTime(now: Long) { require(now >= 0) }
    // Coordinator derives this from request-start server time, never ACK arrival time.
    private fun confirmedDeadline(now: Long, confirmed: Long) = minOf(Math.addExact(now, ACTIVE_TTL_MILLIS), confirmed)

    companion object {
        const val PENDING_TTL_MILLIS = 15_000L
        const val ACTIVE_TTL_MILLIS = 30_000L
    }
}

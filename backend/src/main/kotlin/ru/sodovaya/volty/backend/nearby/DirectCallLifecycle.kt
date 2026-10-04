package ru.sodovaya.volty.backend.nearby

enum class DirectCallPhase { RINGING, ACCEPTED, DECLINED, CANCELLED, MISSED, ENDED }
enum class DirectCallOutcome { CALLEE_DECLINED, CALLER_CANCELLED, CALLEE_CANCELLED, RING_TIMEOUT, CALLER_ENDED, CALLEE_ENDED, FRIENDSHIP_REVOKED, MEDIA_TIMEOUT }
enum class DirectCallRejection { NOT_FRIENDS, IDENTITY_MISMATCH, ACCESS_DENIED, REVISION_MISMATCH, TERMINAL, NOT_RINGING, CLAIM_BUSY }

data class DirectCallRecord(
    val callId: String, val callerId: String, val calleeId: String,
    val idempotencyKey: String, val callerOperationId: String,
    val phase: DirectCallPhase, val ringDeadlineMillis: Long, val revision: Long,
    val outcome: DirectCallOutcome? = null,
) {
    init {
        require(listOf(callId, callerId, calleeId, idempotencyKey, callerOperationId).all {it.isNotBlank()})
        require(callerId != calleeId && ringDeadlineMillis >= 0 && revision >= 0)
        require(when (phase) {
            DirectCallPhase.RINGING, DirectCallPhase.ACCEPTED -> outcome == null
            DirectCallPhase.DECLINED -> outcome == DirectCallOutcome.CALLEE_DECLINED
            DirectCallPhase.CANCELLED -> outcome in setOf(DirectCallOutcome.CALLER_CANCELLED, DirectCallOutcome.CALLEE_CANCELLED, DirectCallOutcome.FRIENDSHIP_REVOKED)
            DirectCallPhase.MISSED -> outcome == DirectCallOutcome.RING_TIMEOUT
            DirectCallPhase.ENDED -> outcome in setOf(DirectCallOutcome.CALLER_ENDED, DirectCallOutcome.CALLEE_ENDED, DirectCallOutcome.FRIENDSHIP_REVOKED, DirectCallOutcome.MEDIA_TIMEOUT)
        })
    }
    val terminal get() = phase != DirectCallPhase.RINGING && phase != DirectCallPhase.ACCEPTED
}
data class DirectCallDecision(val call: DirectCallRecord?, val rejection: DirectCallRejection? = null)

/** Pure decisions under the future repository's call/claim locks. ACCEPTED is not a media
 * credential or SFU permission; original friendship and both claims require SQL validation. */
class DirectCallLifecyclePolicy {
    fun create(existing: DirectCallRecord?, callId: String, caller: String, callee: String,
               key: String, callerOperation: String, now: Long, acceptedFriendship: Boolean): DirectCallDecision {
        time(now)
        require(listOf(callId, caller, callee, key, callerOperation).all {it.isNotBlank()})
        require(caller != callee)
        if (existing != null && (existing.callerId != caller || existing.calleeId != callee ||
                existing.idempotencyKey != key || existing.callerOperationId != callerOperation))
            return rejected(existing, DirectCallRejection.IDENTITY_MISMATCH)
        if (!acceptedFriendship) return rejected(existing, DirectCallRejection.NOT_FRIENDS)
        // A retry can generate a candidate ID, but must never replace the originally committed ID.
        return DirectCallDecision(existing?.let {expired(it, now)} ?: DirectCallRecord(callId, caller, callee,
            key, callerOperation, DirectCallPhase.RINGING, Math.addExact(now, RING_TTL_MILLIS), 0))
    }

    fun accept(call: DirectCallRecord, actor: String, revision: Long, now: Long): DirectCallDecision {
        time(now); require(revision >= 0)
        if (actor != call.calleeId) return rejected(call, DirectCallRejection.ACCESS_DENIED)
        if (call.terminal) return rejected(call, DirectCallRejection.TERMINAL)
        if (call.phase == DirectCallPhase.ACCEPTED) return DirectCallDecision(call)
        val current = expired(call, now)
        if (current.terminal) return rejected(current, DirectCallRejection.TERMINAL)
        // Earlier revision checked optimistic revision before expiry. A stale authorized
        // command then returned elapsed RINGING; persist time expiry even when rejecting it.
        if (current.revision != revision) return rejected(current, DirectCallRejection.REVISION_MISMATCH)
        return DirectCallDecision(current.copy(phase = DirectCallPhase.ACCEPTED, revision = nextRevision(current)))
    }

    fun decline(call: DirectCallRecord, actor: String, now: Long): DirectCallDecision {
        time(now)
        if (actor != call.calleeId) return rejected(call, DirectCallRejection.ACCESS_DENIED)
        val current = expired(call, now)
        if (current.terminal) return DirectCallDecision(current)
        if (current.phase != DirectCallPhase.RINGING) return rejected(current, DirectCallRejection.NOT_RINGING)
        return finish(current, DirectCallPhase.DECLINED, DirectCallOutcome.CALLEE_DECLINED)
    }

    /** Intention to end the conversation, including one accepted before a delayed cancel. */
    fun stop(call: DirectCallRecord, actor: String, now: Long): DirectCallDecision {
        time(now)
        if (actor != call.callerId && actor != call.calleeId) return rejected(call, DirectCallRejection.ACCESS_DENIED)
        val current = expired(call, now)
        if (current.terminal) return DirectCallDecision(current)
        val caller = actor == call.callerId
        return if (current.phase == DirectCallPhase.RINGING)
            finish(current, DirectCallPhase.CANCELLED, if (caller) DirectCallOutcome.CALLER_CANCELLED else DirectCallOutcome.CALLEE_CANCELLED)
        else finish(current, DirectCallPhase.ENDED, if (caller) DirectCallOutcome.CALLER_ENDED else DirectCallOutcome.CALLEE_ENDED)
    }

    fun expire(call: DirectCallRecord, now: Long): DirectCallDecision {
        time(now)
        return DirectCallDecision(expired(call, now))
    }

    /** Caller must prove an original session's elapsed lease/terminal state under pair locks.
     * This policy is not a media observer and never infers expiry from ring deadline. */
    fun expireMedia(call: DirectCallRecord, now: Long): DirectCallDecision {
        time(now)
        require(call.phase == DirectCallPhase.ACCEPTED || call.terminal)
        return if (call.terminal) DirectCallDecision(call)
        else finish(call, DirectCallPhase.ENDED, DirectCallOutcome.MEDIA_TIMEOUT)
    }

    /** Trusted repository reconciliation, never an unguarded public endpoint. */
    fun revokeFriendship(call: DirectCallRecord, now: Long): DirectCallDecision {
        time(now)
        val current = expired(call, now)
        if (current.terminal) return DirectCallDecision(current)
        return finish(current, if (current.phase == DirectCallPhase.ACCEPTED) DirectCallPhase.ENDED else DirectCallPhase.CANCELLED,
            DirectCallOutcome.FRIENDSHIP_REVOKED)
    }

    private fun expired(call: DirectCallRecord, now: Long) =
        if (call.phase == DirectCallPhase.RINGING && now >= call.ringDeadlineMillis)
            call.copy(phase = DirectCallPhase.MISSED, outcome = DirectCallOutcome.RING_TIMEOUT, revision = nextRevision(call)) else call
    private fun finish(call: DirectCallRecord, phase: DirectCallPhase, outcome: DirectCallOutcome) =
        DirectCallDecision(call.copy(phase = phase, outcome = outcome, revision = nextRevision(call)))
    private fun rejected(call: DirectCallRecord?, reason: DirectCallRejection) = DirectCallDecision(call, reason)
    private fun nextRevision(call: DirectCallRecord) = Math.addExact(call.revision, 1L)
    private fun time(now: Long) {require(now >= 0)}

    companion object {const val RING_TTL_MILLIS = 45_000L}
}

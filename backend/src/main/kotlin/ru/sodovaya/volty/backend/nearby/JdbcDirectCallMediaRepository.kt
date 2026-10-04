package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

/** SQL-only accepted pair foundation. Not wired to DI, routes or SFU. */
class JdbcDirectCallMediaRepository(private val source: DataSource) {
    private val policy = DirectCallLifecyclePolicy()
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    fun accept(actor: String, call: String, calleeOperation: String, revision: Long, epoch: SfuEpoch, now: () -> Long): DirectCallDecision {
        listOf(actor, call, calleeOperation).forEach(::canonical)
        require(revision >= 0 && epoch.roomEpoch == "volty-call-$call")
        return owned(actor, call) {c, current ->
            if (actor != current.calleeId) return@owned DirectCallDecision(current, DirectCallRejection.ACCESS_DENIED)
            val friend = friends(c, current)
            val at = now()
            expireAccepted(c, current, at)?.let {return@owned it.copy(rejection = DirectCallRejection.TERMINAL)}
            retireLostClaims(c, current, at)?.let {return@owned it.copy(rejection = DirectCallRejection.TERMINAL)}
            if (!friend && !current.terminal)
                return@owned persist(c, policy.revokeFriendship(current, at), at).copy(rejection = DirectCallRejection.NOT_FRIENDS)
            val decision = policy.accept(current, actor, revision, at)
            if (decision.rejection != null) return@owned persist(c, decision, at)
            if (current.phase == DirectCallPhase.ACCEPTED) {
                val rows = pair(c, current)
                val callee = rows.single {it.operation.actorId == actor}
                if (callee.operation.operationId != calleeOperation || rows.any {it.sfuEpoch != epoch})
                    return@owned DirectCallDecision(current, DirectCallRejection.IDENTITY_MISMATCH)
                if (rows.any {sessions.claimInTransaction(c, it.operation.actorId) !=
                            SessionClaim(it.operation.operationId, it.operation.sessionId!!, it.sfuEpoch!!)})
                    return@owned DirectCallDecision(current, DirectCallRejection.CLAIM_BUSY)
                return@owned decision
            }
            if (reservation(c, current.callerId) != current.callId)
                return@owned persist(c, policy.stop(current, current.callerId, at), at)
            if (reservation(c, current.calleeId) != null)
                return@owned DirectCallDecision(current, DirectCallRejection.CLAIM_BUSY)
            val oldCaller = sessions.operationInTransaction(c, current.callerId, current.callerOperationId)
            if (oldCaller != null) {
                // A prior original-operation cancellation must dominate a late accepted request.
                if (oldCaller.operation.phase == OperationPhase.CANCELLED)
                    return@owned persist(c, policy.stop(current, current.callerId, at), at)
                return@owned DirectCallDecision(current, DirectCallRejection.IDENTITY_MISMATCH)
            }
            if (sessions.operationInTransaction(c, actor, calleeOperation) != null)
                return@owned DirectCallDecision(current, DirectCallRejection.IDENTITY_MISMATCH)
            val oldClaim = sessions.claimInTransaction(c, actor)
            if (oldClaim != null) {
                val row = checkNotNull(sessions.operationInTransaction(c, actor, oldClaim.operationId))
                if (row.operation.destinationKind != VoiceDestinationKind.CHANNEL)
                    return@owned DirectCallDecision(current, DirectCallRejection.CLAIM_BUSY)
                sessions.cancelCurrentAccountClaim(c, actor, at)
            }
            revokeSharing(c, actor, at)
            // Earlier acceptance only revoked callee sharing. Caller reservation does not
            // prove its old trip publication was stopped; close both original consents.
            revokeSharing(c, current.callerId, at)
            sessions.createCallPending(c, current.callerId, current.callerOperationId, call, epoch, at)
            sessions.createCallPending(c, actor, calleeOperation, call, epoch, at)
            persist(c, decision, at)
        }
    }

    /** Full conversation stop. A later cancellation ends an already accepted pair. */
    fun stop(actor: String, call: String, now: () -> Long): DirectCallDecision {
        canonical(actor); canonical(call)
        return owned(actor, call) {c, current ->
            val at = now()
            expireAccepted(c, current, at)?.let {return@owned it}
            retireLostClaims(c, current, at)?.let {return@owned it}
            persist(c, policy.stop(current, actor, at), at)
        }
    }

    private fun expireAccepted(c: Connection, call: DirectCallRecord, at: Long): DirectCallDecision? {
        if (call.phase != DirectCallPhase.ACCEPTED) return null
        val rows = pair(c, call)
        val expired = rows.any {at >= checkNotNull(it.operation.deadlineEpochMillis)}
        if (!expired && rows.none {it.operation.terminal}) return null
        // Earlier replay returned ACCEPTED solely from the call row; stop at the elapsed
        // media lease also invented a user cause. Original lease evidence ends both rows.
        return persist(c, if (expired) policy.expireMedia(call, at) else policy.loseMedia(call, at), at)
    }

    private fun retireLostClaims(c: Connection, call: DirectCallRecord, at: Long): DirectCallDecision? {
        if (call.phase != DirectCallPhase.ACCEPTED) return null
        val rows = pair(c, call)
        if (rows.all {sessions.claimInTransaction(c, it.operation.actorId) ==
                    SessionClaim(it.operation.operationId, it.operation.sessionId!!, it.sfuEpoch!!)}) return null
        return persist(c, policy.loseMedia(call, at), at)
    }

    /** Reconciliation is SQL-only. Fresh pending rows are not channel-access denied or audible. */
    fun reconcile(actor: String, call: String, now: () -> Long): DirectCallDecision {
        canonical(actor); canonical(call)
        return owned(actor, call) {c, current ->
            val friend = friends(c, current)
            val at = now()
            expireAccepted(c, current, at)?.let {return@owned it}
            if (!friend && !current.terminal) return@owned persist(c, policy.revokeFriendship(current, at), at)
            retireLostClaims(c, current, at)?.let {return@owned it}
            persist(c, policy.expire(current, at), at)
        }
    }

    internal fun stopSession(actor: String, operation: String, now: () -> Long): SessionJournalResult? =
        sessionAction(actor, operation) {call -> stop(actor, call, now)}

    internal fun reconcileSession(actor: String, operation: String, now: () -> Long): SessionJournalResult? =
        sessionAction(actor, operation) {call -> reconcile(actor, call, now)}

    private fun sessionAction(actor: String, operation: String, action: (String) -> DirectCallDecision): SessionJournalResult? {
        val seed = sessions.get(actor, operation) ?: return null
        if (seed.operation.destinationKind != VoiceDestinationKind.DIRECT_CALL) return null
        val call = checkNotNull(seed.operation.destinationId)
        val participant = source.connection.use {c -> c.prepareStatement("SELECT 1 FROM nearby_direct_calls WHERE call_id=?::uuid AND (caller_id=?::uuid OR (callee_id=?::uuid AND ever_rang))").use {
            it.setString(1, call); it.setString(2, actor); it.setString(3, actor); it.executeQuery().use {r -> r.next()}
        }}
        if (!participant) return null // Malformed isolated fixture has no pair authority.
        val decision = action(call) // Revalidates original participants/operations under pair locks.
        val row = sessions.get(actor, operation)
        return SessionJournalResult(row, if (decision.call == null) OperationRejection.ACCESS_DENIED else null)
    }

    /** Account writer holds UPDATE fence before collecting the complete peer actor set. */
    internal fun accountAcceptedActors(c: Connection, actor: String): List<String> {
        check(!c.autoCommit)
        return c.prepareStatement("SELECT caller_id,callee_id FROM nearby_direct_calls WHERE phase='ACCEPTED' AND (caller_id=?::uuid OR callee_id=?::uuid) ORDER BY call_id").use {
            it.setString(1, actor); it.setString(2, actor)
            it.executeQuery().use {r -> buildList {while (r.next()) {add(r.getString(1)); add(r.getString(2))}}}
        }.distinct().sorted()
    }

    /** Same account transaction, all affected actors already locked before any revisions. */
    internal fun cancelAccountCalls(c: Connection, actor: String, at: Long) {
        check(!c.autoCommit)
        val calls = c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE phase='ACCEPTED' AND (caller_id=?::uuid OR callee_id=?::uuid) ORDER BY call_id FOR UPDATE").use {
            it.setString(1, actor); it.setString(2, actor)
            it.executeQuery().use {r -> buildList {while (r.next()) add(r.call())}}
        }
        for (call in calls) {
            if (expireAccepted(c, call, at) == null) persist(c, policy.revokeFriendship(call, at), at)
        }
    }

    private fun pair(c: Connection, call: DirectCallRecord): List<SessionJournalEntry> {
        val rows = sessions.callMediaInTransaction(c, call.callId)
        check(rows.size == 2 && rows.map {it.operation.actorId}.toSet() == setOf(call.callerId, call.calleeId))
        check(rows.single {it.operation.actorId == call.callerId}.operation.operationId == call.callerOperationId)
        check(rows.all {it.sfuEpoch?.roomEpoch == "volty-call-${call.callId}"} && rows[0].sfuEpoch == rows[1].sfuEpoch)
        return rows
    }

    private fun persist(c: Connection, decision: DirectCallDecision, at: Long): DirectCallDecision {
        val call = checkNotNull(decision.call)
        c.prepareStatement("UPDATE nearby_direct_calls SET phase=?,revision=?,outcome=? WHERE call_id=?::uuid").use {
            it.setString(1, call.phase.name); it.setLong(2, call.revision); it.setString(3, call.outcome?.name)
            it.setString(4, call.callId); check(it.executeUpdate() == 1)
        }
        if (call.terminal) {
            sessions.cancelCallMedia(c, call, at)
            c.prepareStatement("UPDATE nearby_actor_claims SET reserved_call_id=NULL WHERE reserved_call_id=?::uuid AND actor_id IN (?::uuid,?::uuid)").use {
                it.setString(1, call.callId); it.setString(2, call.callerId); it.setString(3, call.calleeId); it.executeUpdate()
            }
        }
        return decision
    }

    private fun reservation(c: Connection, actor: String): String? = c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
        it.setString(1, actor); it.executeQuery().use {r -> check(r.next()); r.getString(1)}
    }

    private fun revokeSharing(c: Connection, actor: String, at: Long) {
        c.prepareStatement("UPDATE sharing_sessions SET revoked_at=? WHERE user_id=?::uuid AND revoked_at IS NULL").use {
            it.setLong(1, at); it.setString(2, actor); it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM live_updates WHERE user_id=?::uuid").use {it.setString(1, actor); it.executeUpdate()}
    }

    private fun friends(c: Connection, call: DirectCallRecord) = c.prepareStatement("""
        SELECT f.id FROM friendships f JOIN users a ON a.id=?::uuid JOIN users b ON b.id=?::uuid
        WHERE LEAST(f.requester_id,f.addressee_id)=LEAST(a.id,b.id) AND GREATEST(f.requester_id,f.addressee_id)=GREATEST(a.id,b.id)
        AND f.status='ACCEPTED' AND a.deleted_at IS NULL AND b.deleted_at IS NULL FOR SHARE OF f
    """.trimIndent()).use {
        it.setString(1, call.callerId); it.setString(2, call.calleeId); it.executeQuery().use {r -> r.next()}
    }

    private fun owned(actor: String, id: String, block: (Connection, DirectCallRecord) -> DirectCallDecision): DirectCallDecision =
        communityTransaction(source, write = true) {c ->
            // UPDATE fence excludes call/session/resource writers while selecting the immutable pair.
            val lookup = c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE call_id=?::uuid AND (caller_id=?::uuid OR (callee_id=?::uuid AND ever_rang))").use {
                it.setString(1, id); it.setString(2, actor); it.setString(3, actor)
                it.executeQuery().use {r -> if (r.next()) r.call() else null}
            } ?: return@communityTransaction DirectCallDecision(null, DirectCallRejection.ACCESS_DENIED)
            sessions.lockCommunityActors(c, listOf(lookup.callerId, lookup.calleeId))
            val current = c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE call_id=?::uuid FOR UPDATE").use {
                it.setString(1, id); it.executeQuery().use {r -> check(r.next()); r.call()}
            }
            check(current.callerId == lookup.callerId && current.calleeId == lookup.calleeId)
            block(c, current)
        }

    private fun ResultSet.call() = DirectCallRecord(getString("call_id"), getString("caller_id"), getString("callee_id"),
        getString("idempotency_key"), getString("caller_operation_id"), DirectCallPhase.valueOf(getString("phase")),
        getLong("ring_deadline_millis"), getLong("revision"), getString("outcome")?.let(DirectCallOutcome::valueOf))
    private fun canonical(value: String) {require(UUID.fromString(value).toString() == value)}
}

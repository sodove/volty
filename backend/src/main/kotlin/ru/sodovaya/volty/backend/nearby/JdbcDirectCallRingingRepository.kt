package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/** Ringing only. Accepted media/claims and public call endpoints are not enabled here. */
class JdbcDirectCallRingingRepository(private val source: DataSource) {
    private val policy = DirectCallLifecyclePolicy()
    /** Original outgoing binding survives a stop before the creation request reaches SQL.
     * Never-rang tombstones are private to caller; no incoming notification/history is earned. */
    fun cancelOutgoingRinging(caller: String, callee: String, callId: String, key: String, operation: String, now: () -> Long): DirectCallDecision {
        listOf(caller, callee, callId, key, operation).forEach(::canonical)
        require(caller != callee)
        return locked(listOf(caller, callee)) {c ->
            val existing = byKey(c, caller, key)
            val at = now()
            val identity = policy.create(existing, callId, caller, callee, key, operation, at, true)
            if (identity.rejection != null) return@locked identity
            val row = identity.call!!
            if (row.phase == DirectCallPhase.ACCEPTED) return@locked DirectCallDecision(row, DirectCallRejection.NOT_RINGING)
            val cancelled = policy.stop(row, caller, at)
            if (existing != null) persist(c, cancelled)
            else if (insert(c, cancelled.call!!, false)) cancelled
            else DirectCallDecision(null, DirectCallRejection.IDENTITY_MISMATCH)
        }
    }

    fun create(caller: String, callee: String, callId: String, key: String, operation: String, now: () -> Long): DirectCallDecision {
        listOf(caller, callee, callId, key, operation).forEach(::canonical)
        require(caller != callee)
        return locked(listOf(caller, callee)) {c ->
            val friend = friends(c, caller, callee)
            val existing = byKey(c, caller, key)
            if (existing != null) {
                val at = now()
                val decision = policy.create(existing, callId, caller, callee, key, operation, at, friend)
                if (decision.rejection == DirectCallRejection.IDENTITY_MISMATCH) return@locked decision
                if (existing.phase == DirectCallPhase.ACCEPTED) return@locked DirectCallDecision(existing, DirectCallRejection.NOT_RINGING)
                if (!friend && existing.phase == DirectCallPhase.RINGING) {
                    val revoked = persist(c, policy.revokeFriendship(existing, at))
                    return@locked DirectCallDecision(revoked.call, DirectCallRejection.NOT_FRIENDS)
                }
                retireLostRing(c, existing, at)?.let {return@locked it}
                return@locked persist(c, decision)
            }
            if (!friend) return@locked DirectCallDecision(null, DirectCallRejection.NOT_FRIENDS)
            val slot = c.prepareStatement("SELECT session_id,reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
                it.setString(1, caller); it.executeQuery().use {r -> check(r.next()); r.getString(1) to r.getString(2)}
            }
            if (slot.first != null) return@locked DirectCallDecision(null, DirectCallRejection.CLAIM_BUSY)
            val older = slot.second?.let {read(c, it, true)}
            val at = now() // After all relevant call rows have been locked.
            if (slot.second != null) {
                if (older == null || older.callerId != caller || older.phase == DirectCallPhase.ACCEPTED)
                    return@locked DirectCallDecision(null, DirectCallRejection.CLAIM_BUSY)
                val expired = persist(c, policy.expire(older, at)).call!!
                if (!expired.terminal) return@locked DirectCallDecision(null, DirectCallRejection.CLAIM_BUSY)
            }
            val next = policy.create(null, callId, caller, callee, key, operation, at, true).call!!
            if (!insert(c, next, true)) return@locked DirectCallDecision(null, DirectCallRejection.IDENTITY_MISMATCH)
            c.prepareStatement("UPDATE nearby_actor_claims SET reserved_call_id=?::uuid WHERE actor_id=?::uuid AND session_id IS NULL AND reserved_call_id IS NULL").use {
                it.setString(1, callId); it.setString(2, caller); check(it.executeUpdate() == 1)
            }
            DirectCallDecision(next)
        }
    }

    fun status(actor: String, call: String, now: () -> Long) = change(actor, call, now, Mode.STATUS)
    fun cancelRinging(actor: String, call: String, now: () -> Long) = change(actor, call, now, Mode.CANCEL)
    fun decline(actor: String, call: String, now: () -> Long) = change(actor, call, now, Mode.DECLINE)

    private enum class Mode { STATUS, CANCEL, DECLINE }
    private fun change(actor: String, id: String, now: () -> Long, mode: Mode): DirectCallDecision {
        canonical(actor); canonical(id)
        val lookup = source.connection.use {c -> c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE call_id=?::uuid AND (caller_id=?::uuid OR (callee_id=?::uuid AND ever_rang))").use {
            it.setString(1, id); it.setString(2, actor); it.setString(3, actor)
            it.executeQuery().use {r -> if (r.next()) r.call() else null}
        }} ?: return DirectCallDecision(null, DirectCallRejection.ACCESS_DENIED)
        return locked(listOf(lookup.callerId, lookup.calleeId)) {c ->
            val friend = friends(c, lookup.callerId, lookup.calleeId)
            val current = read(c, id, true) ?: return@locked DirectCallDecision(null, DirectCallRejection.ACCESS_DENIED)
            if (actor != current.callerId && actor != current.calleeId)
                return@locked DirectCallDecision(null, DirectCallRejection.ACCESS_DENIED)
            if (mode == Mode.DECLINE && actor != current.calleeId)
                return@locked DirectCallDecision(current, DirectCallRejection.ACCESS_DENIED)
            // No partial accepted-stop writer: retiring both media/claims belongs to the next transaction.
            if (current.phase == DirectCallPhase.ACCEPTED) return@locked DirectCallDecision(current, DirectCallRejection.NOT_RINGING)
            val at = now()
            retireLostRing(c, current, at)?.let {return@locked it}
            val decision = if (!friend && !current.terminal) policy.revokeFriendship(current, at) else when (mode) {
                Mode.STATUS -> policy.expire(current, at)
                Mode.CANCEL -> policy.stop(current, actor, at)
                Mode.DECLINE -> policy.decline(current, actor, at)
            }
            persist(c, decision)
        }
    }

    private fun persist(c: Connection, decision: DirectCallDecision): DirectCallDecision {
        val row = decision.call ?: return decision
        c.prepareStatement("UPDATE nearby_direct_calls SET phase=?,revision=?,outcome=? WHERE call_id=?::uuid").use {
            it.setString(1, row.phase.name); it.setLong(2, row.revision); it.setString(3, row.outcome?.name); it.setString(4, row.callId)
            check(it.executeUpdate() == 1)
        }
        if (row.terminal) c.prepareStatement("UPDATE nearby_actor_claims SET reserved_call_id=NULL WHERE reserved_call_id=?::uuid AND actor_id IN (?::uuid,?::uuid)").use {
            it.setString(1, row.callId); it.setString(2, row.callerId); it.setString(3, row.calleeId); it.executeUpdate()
        }
        return decision
    }

    private fun retireLostRing(c: Connection, call: DirectCallRecord, at: Long): DirectCallDecision? {
        if (call.phase != DirectCallPhase.RINGING) return null
        val current = c.prepareStatement("SELECT reserved_call_id FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
            it.setString(1, call.callerId); it.executeQuery().use {r -> r.next() && r.getString(1) == call.callId}
        }
        // Earlier replay/status advertised RINGING solely from the row. A superseded caller
        // slot cannot authorize it; exact reservation release must preserve the newer session.
        return if (current) null else persist(c, policy.stop(call, call.callerId, at))
    }

    private fun insert(c: Connection, row: DirectCallRecord, everRang: Boolean): Boolean = c.prepareStatement("""
        INSERT INTO nearby_direct_calls(call_id,caller_id,callee_id,idempotency_key,caller_operation_id,phase,ring_deadline_millis,revision,outcome,ever_rang)
        VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?) ON CONFLICT DO NOTHING
    """.trimIndent()).use {
        it.setString(1, row.callId); it.setString(2, row.callerId); it.setString(3, row.calleeId); it.setString(4, row.idempotencyKey)
        it.setString(5, row.callerOperationId); it.setString(6, row.phase.name); it.setLong(7, row.ringDeadlineMillis)
        it.setLong(8, row.revision); it.setString(9, row.outcome?.name); it.setBoolean(10, everRang); it.executeUpdate() == 1
    }

    private fun byKey(c: Connection, caller: String, key: String): DirectCallRecord? =
        c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE caller_id=?::uuid AND idempotency_key=?::uuid FOR UPDATE").use {
            it.setString(1, caller); it.setString(2, key); it.executeQuery().use {r -> if (r.next()) r.call() else null}
        }

    private fun read(c: Connection, id: String, lock: Boolean): DirectCallRecord? =
        c.prepareStatement("SELECT * FROM nearby_direct_calls WHERE call_id=?::uuid" + if (lock) " FOR UPDATE" else "").use {
            it.setString(1, id); it.executeQuery().use {r -> if (r.next()) r.call() else null}
        }

    private fun ResultSet.call() = DirectCallRecord(getString("call_id"), getString("caller_id"), getString("callee_id"),
        getString("idempotency_key"), getString("caller_operation_id"), DirectCallPhase.valueOf(getString("phase")),
        getLong("ring_deadline_millis"), getLong("revision"), getString("outcome")?.let(DirectCallOutcome::valueOf))

    private fun friends(c: Connection, caller: String, callee: String): Boolean = c.prepareStatement("""
        SELECT f.id FROM friendships f JOIN users a ON a.id=?::uuid JOIN users b ON b.id=?::uuid
        WHERE LEAST(f.requester_id,f.addressee_id)=LEAST(a.id,b.id) AND GREATEST(f.requester_id,f.addressee_id)=GREATEST(a.id,b.id)
        AND f.status='ACCEPTED' AND a.deleted_at IS NULL AND b.deleted_at IS NULL FOR SHARE OF f
    """.trimIndent()).use {
        it.setString(1, caller); it.setString(2, callee); it.executeQuery().use {r -> r.next()}
    }

    private fun locked(actors: List<String>, block: (Connection) -> DirectCallDecision): DirectCallDecision = source.connection.use {c ->
        c.autoCommit = false
        try {
            c.createStatement().use {
                it.execute("SET LOCAL lock_timeout='5s'"); it.execute("SET LOCAL statement_timeout='10s'")
                it.execute("SET LOCAL idle_in_transaction_session_timeout='15s'")
            }
            c.prepareStatement("SELECT singleton FROM nearby_access_fence WHERE singleton=1 FOR SHARE").use {it.executeQuery().use {r -> check(r.next())}}
            for (actor in actors.distinct().sorted()) {
                val exists = c.prepareStatement("SELECT id FROM users WHERE id=?::uuid").use {
                    it.setString(1, actor); it.executeQuery().use {r -> r.next()}
                }
                if (!exists) {c.rollback(); return@use DirectCallDecision(null, DirectCallRejection.ACCESS_DENIED)}
                c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id) VALUES (?::uuid) ON CONFLICT DO NOTHING").use {it.setString(1, actor); it.executeUpdate()}
                c.prepareStatement("SELECT actor_id FROM nearby_actor_claims WHERE actor_id=?::uuid FOR UPDATE").use {
                    it.setString(1, actor); it.executeQuery().use {r -> check(r.next())}
                }
            }
            val result = block(c); c.commit(); result
        } catch (failure: Throwable) {
            try {c.rollback()} catch (rollback: Throwable) {failure.addSuppressed(rollback)}
            throw failure
        }
    }

    private fun canonical(value: String) {require(UUID.fromString(value).toString() == value)}
}

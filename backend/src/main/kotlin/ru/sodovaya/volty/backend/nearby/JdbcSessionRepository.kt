package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcSessionRepository(private val source: DataSource) : SessionRepository {
    private val policy = SessionOperationPolicy()

    override fun begin(actor: String, operation: String, destination: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, replace: Boolean): SessionJournalResult =
        locked(actor) { c ->
            val existing = read(c, actor, operation)
            val observedAt = now()
            val decision = policy.begin(existing?.operation, actor, operation, destination, session, observedAt)
            if (existing != null) {
                if (!existing.operation.terminal && existing.sfuEpoch != sfuEpoch) return@locked SessionJournalResult(existing, OperationRejection.EPOCH_MISMATCH)
                return@locked persist(c, existing, decision)
            }
            // Serializing through the actor row also covers cancel before any operation row exists.
            claim(c, actor)?.let { oldClaim ->
                val old = checkNotNull(read(c, actor, oldClaim.operationId))
                val expired = policy.expire(old.operation, observedAt)
                if (expired.operation!!.terminal) persist(c, old, expired)
                else {
                    if (!replace) return@locked SessionJournalResult(null, OperationRejection.CLAIM_BUSY)
                    persist(c, old, policy.cancel(old.operation, actor, oldClaim.operationId, observedAt))
                }
            }
            val row = SessionJournalEntry(checkNotNull(decision.operation), sfuEpoch, admissionPending = true, cleanupPending = false)
            save(c, row)
            appendRosterChange(c, row)
            c.prepareStatement("UPDATE nearby_actor_claims SET operation_id=?::uuid,session_id=?::uuid,room_epoch=?,node_epoch=? WHERE actor_id=?::uuid").use {
                it.setString(1, operation); it.setString(2, session); it.setString(3, sfuEpoch.roomEpoch); it.setString(4, sfuEpoch.nodeEpoch); it.setString(5, actor); it.executeUpdate()
            }
            SessionJournalResult(row)
        }

    override fun cancel(actor: String, operation: String, now: () -> Long): SessionJournalResult = locked(actor) { c ->
        val existing = read(c, actor, operation)
        persist(c, existing, policy.cancel(existing?.operation, actor, operation, now()))
    }

    override fun admit(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long) =
        mediaChange(actor, operation, session, sfuEpoch) { policy.admit(it, session, now(), confirmedDeadline) }

    override fun heartbeat(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch, now: () -> Long, confirmedDeadline: Long) =
        mediaChange(actor, operation, session, sfuEpoch) { policy.heartbeat(it, session, now(), confirmedDeadline) }

    override fun expire(actor: String, operation: String, now: () -> Long): SessionJournalResult = locked(actor) { c ->
        val row = read(c, actor, operation) ?: return@locked SessionJournalResult(null, OperationRejection.NOT_FOUND)
        persist(c, row, policy.expire(row.operation, now()))
    }

    override fun get(actor: String, operation: String) = source.connection.use { read(it, actor, operation) }
    override fun findSession(actor: String, session: String): SessionJournalEntry? = source.connection.use { c ->
        c.prepareStatement("SELECT * FROM nearby_session_operations WHERE actor_id=?::uuid AND session_id=?::uuid").use {
            it.setString(1, actor); it.setString(2, session)
            it.executeQuery().use { rows -> if (rows.next()) rows.entry() else null }
        }
    }
    override fun currentClaim(actor: String) = source.connection.use { claim(it, actor) }

    override fun pendingWork(limit: Int) = selectWork("admission_pending OR cleanup_pending", null, limit)
    override fun pendingWork(actor: String, limit: Int) = selectWork("admission_pending OR cleanup_pending", null, limit, actor)
    override fun pendingCleanup(actor: String, limit: Int) = selectWork("cleanup_pending", null, limit, actor)
    override fun dueOperations(now: Long, limit: Int) = selectWork("phase IN ('PENDING','ACTIVE') AND deadline_epoch_millis <= ?", now, limit)
    override fun dueOperations(actor: String, now: Long, limit: Int) = selectWork("phase IN ('PENDING','ACTIVE') AND deadline_epoch_millis <= ?", now, limit, actor)

    override fun <T : Any> withActiveSession(actor: String, operation: String, now: () -> Long, producer: (SessionJournalEntry) -> T): T? = locked(actor) { c ->
        val row = read(c, actor, operation) ?: return@locked null
        val current = checkNotNull(persist(c, row, policy.expire(row.operation, now())).state)
        if (current.operation.phase != OperationPhase.ACTIVE) return@locked null
        val expected = SessionClaim(operation, checkNotNull(current.operation.sessionId), checkNotNull(current.sfuEpoch))
        if (claim(c, actor) != expected) return@locked null
        producer(current)
    }

    override fun completeCleanup(actor: String, operation: String, session: String, sfuEpoch: SfuEpoch): Boolean = locked(actor) { c ->
        c.prepareStatement("UPDATE nearby_session_operations SET cleanup_pending=FALSE WHERE actor_id=?::uuid AND operation_id=?::uuid AND session_id=?::uuid AND room_epoch=? AND node_epoch=? AND phase IN ('CANCELLED','EXPIRED')").use {
            it.setString(1, actor); it.setString(2, operation); it.setString(3, session); it.setString(4, sfuEpoch.roomEpoch); it.setString(5, sfuEpoch.nodeEpoch); it.executeUpdate() == 1
        }
    }

    private fun mediaChange(actor: String, operation: String, session: String, epoch: SfuEpoch, change: (SessionOperation) -> OperationDecision): SessionJournalResult = locked(actor) { c ->
        val row = read(c, actor, operation) ?: return@locked SessionJournalResult(null, OperationRejection.NOT_FOUND)
        if (row.operation.sessionId != session) return@locked SessionJournalResult(row, OperationRejection.SESSION_MISMATCH)
        if (row.sfuEpoch != epoch) return@locked SessionJournalResult(row, OperationRejection.EPOCH_MISMATCH)
        if (!row.operation.terminal && claim(c, actor) != SessionClaim(operation, session, epoch)) return@locked SessionJournalResult(row, OperationRejection.CLAIM_MISMATCH)
        persist(c, row, change(row.operation))
    }

    private fun persist(c: Connection, existing: SessionJournalEntry?, decision: OperationDecision): SessionJournalResult {
        val next = checkNotNull(decision.operation)
        val newlyTerminal = next.terminal && existing?.operation?.terminal != true
        val row = SessionJournalEntry(next, existing?.sfuEpoch,
            admissionPending = next.phase == OperationPhase.PENDING,
            cleanupPending = existing?.cleanupPending == true || (newlyTerminal && next.sessionId != null))
        save(c, row)
        if (existing?.operation != row.operation) appendRosterChange(c, row)
        if (next.terminal && next.sessionId != null) {
            c.prepareStatement("UPDATE nearby_actor_claims SET operation_id=NULL,session_id=NULL,room_epoch=NULL,node_epoch=NULL WHERE actor_id=?::uuid AND operation_id=?::uuid AND session_id=?::uuid").use {
                it.setString(1, next.actorId); it.setString(2, next.operationId); it.setString(3, next.sessionId); it.executeUpdate()
            }
        }
        return SessionJournalResult(row, decision.rejection)
    }

    private fun save(c: Connection, row: SessionJournalEntry) {
        c.prepareStatement("""
            INSERT INTO nearby_session_operations(actor_id,operation_id,destination_id,session_id,room_epoch,node_epoch,phase,deadline_epoch_millis,admission_pending,cleanup_pending)
            VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?)
            ON CONFLICT(actor_id,operation_id) DO UPDATE SET phase=EXCLUDED.phase,
            deadline_epoch_millis=EXCLUDED.deadline_epoch_millis,admission_pending=EXCLUDED.admission_pending,cleanup_pending=EXCLUDED.cleanup_pending
        """.trimIndent()).use {
            val op = row.operation
            it.setString(1, op.actorId); it.setString(2, op.operationId); it.setString(3, op.destinationId)
            it.setString(4, op.sessionId); it.setString(5, row.sfuEpoch?.roomEpoch); it.setString(6, row.sfuEpoch?.nodeEpoch); it.setString(7, op.phase.name)
            it.setObject(8, op.deadlineEpochMillis); it.setBoolean(9, row.admissionPending); it.setBoolean(10, row.cleanupPending)
            it.executeUpdate()
        }
    }

    private fun read(c: Connection, actor: String, operation: String): SessionJournalEntry? =
        c.prepareStatement("SELECT * FROM nearby_session_operations WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
            it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows -> if (rows.next()) rows.entry() else null }
        }

    private fun claim(c: Connection, actor: String): SessionClaim? =
        c.prepareStatement("SELECT operation_id,session_id,room_epoch,node_epoch FROM nearby_actor_claims WHERE actor_id=?::uuid").use {
            it.setString(1, actor); it.executeQuery().use { rows ->
                if (!rows.next() || rows.getString("session_id") == null) null
                else SessionClaim(rows.getString("operation_id"), rows.getString("session_id"), SfuEpoch(rows.getString("room_epoch"), rows.getString("node_epoch")))
            }
        }

    private fun selectWork(predicate: String, now: Long?, limit: Int, actor: String? = null): List<SessionJournalEntry> {
        require(limit in 1..1000)
        return source.connection.use { c ->
            val actorFilter = if (actor == null) "" else " AND actor_id=?::uuid"
            c.prepareStatement("SELECT * FROM nearby_session_operations WHERE ($predicate)$actorFilter ORDER BY actor_id,operation_id LIMIT ?").use {
                var index = 1
                if (now != null) it.setLong(index++, now)
                if (actor != null) it.setString(index++, actor)
                it.setInt(index, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.entry()) } }
            }
        }
    }

    private fun ResultSet.entry() = SessionJournalEntry(
        SessionOperation(getString("actor_id"), getString("operation_id"), getString("destination_id"), getString("session_id"),
            OperationPhase.valueOf(getString("phase")), getLong("deadline_epoch_millis").takeUnless { wasNull() }),
        getString("room_epoch")?.let { SfuEpoch(it, getString("node_epoch")) }, getBoolean("admission_pending"), getBoolean("cleanup_pending"))

    private fun <T> locked(actor: String, block: (Connection) -> T): T = source.connection.use { c ->
        UUID.fromString(actor)
        c.autoCommit = false
        try {
            c.createStatement().use {
                it.execute("SET LOCAL lock_timeout = '5s'")
                it.execute("SET LOCAL statement_timeout = '10s'")
                it.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
            }
            c.prepareStatement("INSERT INTO nearby_actor_claims(actor_id) VALUES (?::uuid) ON CONFLICT DO NOTHING").use { it.setString(1, actor); it.executeUpdate() }
            c.prepareStatement("SELECT actor_id FROM nearby_actor_claims WHERE actor_id=?::uuid FOR UPDATE").use {
                it.setString(1, actor); it.executeQuery().use { rows -> check(rows.next()) }
            }
            val result = block(c)
            c.commit()
            result
        } catch (failure: Throwable) {
            try { c.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
}

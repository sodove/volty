package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.sql.ResultSet

internal const val PUSH_HINT_LIFETIME_MILLIS = 30000L
internal const val PUSH_ATTEMPT_LEASE_MILLIS = 10000L
internal const val MAXIMUM_PUSH_ATTEMPTS = 6
internal const val PUSH_MATERIALIZATION_BATCH = 100
internal fun pushSaturatingAdd(at: Long, delay: Long): Long = if (delay > Long.MAX_VALUE - at) Long.MAX_VALUE else at + delay

internal class PushQueueRow(val id: Long, val event: Long, val installation: String, val expires: Long,
                          val attempts: Int, val done: Boolean, val ticket: String?, val leaseUntil: Long?,
                          val generation: Long?, val binding: String?, val tokenHash: String?)
internal class PushQueueEvent(val id: Long, val actor: String, val call: String, val revision: Long,
                            val phase: String, val created: Long?, val ringDeadline: Long, val cursor: String?) {
    fun expires(): Long? = created?.let { minOf(pushSaturatingAdd(it, PUSH_HINT_LIFETIME_MILLIS), if (phase == "RINGING") ringDeadline else Long.MAX_VALUE) }
}

internal fun pushQueueRow(c: Connection, id: Long): PushQueueRow? = c.prepareStatement("SELECT * FROM nearby_push_deliveries WHERE delivery_id=? FOR UPDATE").use {
    it.setLong(1, id); it.executeQuery().use { r -> if (r.next()) r.pushQueueRow() else null }
}
internal fun ResultSet.pushQueueRow() = PushQueueRow(getLong("delivery_id"), getLong("event_id"), getString("installation_id"),
    getLong("expires_at_millis"), getInt("attempts"), getBoolean("done"), getString("claim_ticket"), nullableLong("lease_until_millis"),
    nullableLong("claimed_generation"), getString("claimed_binding_id"), getString("claimed_token_hash"))
internal fun pushQueueEvent(c: Connection, id: Long): PushQueueEvent? = c.prepareStatement("SELECT * FROM nearby_direct_call_push_outbox WHERE event_id=?").use {
    it.setLong(1, id); it.executeQuery().use { r -> if (r.next()) r.pushQueueEvent() else null }
}
internal fun ResultSet.pushQueueEvent() = PushQueueEvent(getLong("event_id"), getString("actor_id"), getString("call_id"), getLong("call_revision"),
    getString("phase"), nullableLong("created_at_millis"), getLong("ring_deadline_millis"), getString("dispatch_cursor"))
private fun ResultSet.nullableLong(name: String) = getLong(name).let { if (wasNull()) null else it }

internal fun pushEventAvailable(c: Connection, event: PushQueueEvent, at: Long): Boolean {
    if (event.created == null || event.created > at || (event.expires() ?: 0) <= at) return false
    return c.prepareStatement("""
        SELECT 1 FROM nearby_direct_calls d JOIN users callee ON callee.id=d.callee_id
        JOIN users caller ON caller.id=d.caller_id
        WHERE d.call_id=?::uuid AND d.callee_id=?::uuid AND d.revision=? AND d.phase=? AND d.ever_rang
            AND callee.deleted_at IS NULL
            AND (d.phase NOT IN ('RINGING','ACCEPTED') OR (caller.deleted_at IS NULL AND EXISTS (
                SELECT 1 FROM friendships f WHERE f.status='ACCEPTED' AND
                ((f.requester_id=d.caller_id AND f.addressee_id=d.callee_id) OR
                 (f.addressee_id=d.caller_id AND f.requester_id=d.callee_id)))))
    """.trimIndent()).use {
        it.setString(1, event.call); it.setString(2, event.actor); it.setLong(3, event.revision); it.setString(4, event.phase)
        it.executeQuery().use { r -> r.next() }
    }
}

internal fun pushCurrentTarget(c: Connection, installation: String, actor: String): PushDeviceTarget? = c.prepareStatement("""
    SELECT d.* FROM nearby_push_devices d JOIN users u ON u.id=d.actor_id
    WHERE d.installation_id=?::uuid AND d.actor_id=?::uuid AND d.token IS NOT NULL
        AND u.deleted_at IS NULL AND u.tokens_revoked_at<=d.auth_issued_at_seconds FOR SHARE OF d,u
""".trimIndent()).use {
    it.setString(1, installation); it.setString(2, actor); it.executeQuery().use { r ->
        if (r.next()) PushDeviceTarget(r.getString("actor_id"), r.getString("installation_id"), r.getLong("generation"),
            r.getString("binding_id"), r.getString("token"), r.getString("token_hash")) else null
    }
}
internal fun pushSameTarget(a: PushDeviceTarget, b: PushDeviceTarget) = a.actorId == b.actorId && a.installationId == b.installationId &&
    a.generation == b.generation && a.bindingId == b.bindingId && a.tokenHash == b.tokenHash
internal fun pushDone(c: Connection, id: Long) = c.prepareStatement("UPDATE nearby_push_deliveries SET done=TRUE,lease_until_millis=NULL WHERE delivery_id=?").use {
    it.setLong(1, id); it.executeUpdate()
}
internal fun pushRelease(c: Connection, id: Long, due: Long, resetAttempts: Boolean) = c.prepareStatement("""
    UPDATE nearby_push_deliveries SET lease_until_millis=NULL,next_attempt_at_millis=?,
        attempts=CASE WHEN ? THEN 0 ELSE attempts END WHERE delivery_id=?
""".trimIndent()).use { it.setLong(1, due); it.setBoolean(2, resetAttempts); it.setLong(3, id); it.executeUpdate() }

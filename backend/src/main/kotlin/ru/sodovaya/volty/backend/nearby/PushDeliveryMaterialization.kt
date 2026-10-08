package ru.sodovaya.volty.backend.nearby

import java.sql.Connection

/** Bounded snapshot pagination; a new binding refreshes live calls on registration. */
internal fun materializePushEvent(c: Connection, at: Long) {
    val event = c.prepareStatement("""
        SELECT * FROM nearby_direct_call_push_outbox WHERE NOT materialized AND created_at_millis IS NOT NULL
            AND created_at_millis<=? ORDER BY event_id LIMIT 1 FOR UPDATE
    """.trimIndent()).use { it.setLong(1, at); it.executeQuery().use { r -> if (r.next()) r.pushQueueEvent() else null } } ?: return
    if (!pushEventAvailable(c, event, at)) {
        c.prepareStatement("UPDATE nearby_direct_call_push_outbox SET materialized=TRUE WHERE event_id=?").use { it.setLong(1, event.id); it.executeUpdate() }
        return
    }
    val ids = c.prepareStatement("""
        SELECT d.installation_id::text FROM nearby_push_devices d JOIN users u ON u.id=d.actor_id
        WHERE d.actor_id=?::uuid AND d.token IS NOT NULL AND u.deleted_at IS NULL AND u.tokens_revoked_at<=d.auth_issued_at_seconds
            AND (?::uuid IS NULL OR d.installation_id>?::uuid) ORDER BY d.installation_id LIMIT ?
    """.trimIndent()).use {
        it.setString(1, event.actor); it.setString(2, event.cursor); it.setString(3, event.cursor); it.setInt(4, PUSH_MATERIALIZATION_BATCH)
        it.executeQuery().use { r -> buildList { while (r.next()) add(r.getString(1)) } }
    }
    c.prepareStatement("""
        INSERT INTO nearby_push_deliveries(event_id,installation_id,expires_at_millis,next_attempt_at_millis)
        VALUES (?,?::uuid,?,?) ON CONFLICT (event_id,installation_id) DO NOTHING
    """.trimIndent()).use { statement ->
        for (installation in ids) {
            statement.setLong(1, event.id); statement.setString(2, installation); statement.setLong(3, checkNotNull(event.expires()))
            statement.setLong(4, at); statement.addBatch()
        }
        if (ids.isNotEmpty()) statement.executeBatch()
    }
    c.prepareStatement("UPDATE nearby_direct_call_push_outbox SET materialized=?,dispatch_cursor=?::uuid WHERE event_id=?").use {
        it.setBoolean(1, ids.size < PUSH_MATERIALIZATION_BATCH); it.setString(2, ids.lastOrNull() ?: event.cursor); it.setLong(3, event.id); it.executeUpdate()
    }
}

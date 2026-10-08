package ru.sodovaya.volty.backend.nearby

import java.sql.Connection

/** A durable hint, never a media grant or a claim of FCM/notification delivery. */
internal fun journalDirectCallPush(c: Connection, callId: String) {
    check(!c.autoCommit)
    c.prepareStatement("""
        INSERT INTO nearby_direct_call_push_outbox(call_id,actor_id,call_revision,phase,ring_deadline_millis)
        SELECT call_id,callee_id,revision,phase,ring_deadline_millis FROM nearby_direct_calls
        WHERE call_id=?::uuid AND ever_rang
        ON CONFLICT (call_id,call_revision) DO NOTHING
    """.trimIndent()).use {it.setString(1, callId); it.executeUpdate()}
}

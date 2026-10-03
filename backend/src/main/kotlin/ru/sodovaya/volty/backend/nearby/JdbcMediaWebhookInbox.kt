package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import javax.sql.DataSource

data class PendingMediaWebhook(val sequence: Long, val event: VerifiedMediaWebhook, val targetSessionId: String)

interface MediaWebhookInbox : MediaWebhookEventPort {
    fun pending(limit: Int): List<PendingMediaWebhook>
    fun complete(eventId: String, bodySha256: String, targetSessionId: String): Boolean
}

/** Durable reconciliation intent only: receipt changes neither claims nor roster/media state. */
class JdbcMediaWebhookInbox(private val source: DataSource) : MediaWebhookInbox {
    override fun accept(event: VerifiedMediaWebhook): MediaWebhookDisposition = transaction { c ->
        require(event.eventId.isNotBlank() && event.eventId.length <= 256 && event.bodySha256.matches(Regex("[a-f0-9]{64}")))
        val inserted = c.prepareStatement("""
            INSERT INTO nearby_media_webhook_inbox(event_id,body_sha256,room_epoch,session_id,participant_sid,kind)
            VALUES (?,?,?,?::uuid,?,?) ON CONFLICT(event_id) DO NOTHING
        """.trimIndent()).use {
            it.setString(1, event.eventId); it.setString(2, event.bodySha256); it.setString(3, event.roomEpoch)
            it.setString(4, event.sessionId); it.setString(5, event.participantSid); it.setString(6, event.kind.name)
            it.executeUpdate()
        }
        val parent = c.prepareStatement("SELECT sequence,body_sha256 FROM nearby_media_webhook_inbox WHERE event_id=?").use {
            it.setString(1, event.eventId)
            it.executeQuery().use { rows ->
                check(rows.next())
                rows.getLong(1) to rows.getString(2)
            }
        }
        if (parent.second != event.bodySha256) return@transaction MediaWebhookDisposition.CONFLICT
        // Only first receipt captures targets. Replayed room events cannot acquire later joins.
        if (inserted == 1) {
            if (event.kind == MediaWebhookKind.ROOM_FINISHED) {
                c.prepareStatement("""
                    INSERT INTO nearby_media_webhook_targets(event_sequence,session_id)
                    SELECT ?,o.session_id FROM nearby_session_operations o
                    JOIN nearby_actor_claims a ON a.actor_id=o.actor_id AND a.operation_id=o.operation_id
                        AND a.session_id=o.session_id AND a.room_epoch=o.room_epoch AND a.node_epoch=o.node_epoch
                    WHERE o.room_epoch=? AND o.phase IN ('PENDING','ACTIVE')
                """.trimIndent()).use { it.setLong(1, parent.first); it.setString(2, event.roomEpoch); it.executeUpdate() }
            } else {
                c.prepareStatement("INSERT INTO nearby_media_webhook_targets(event_sequence,session_id) VALUES (?,?::uuid)").use {
                    it.setLong(1, parent.first); it.setString(2, event.sessionId); it.executeUpdate()
                }
            }
            finishIfComplete(c, parent.first)
        }
        MediaWebhookDisposition.ACCEPTED
    }

    override fun pending(limit: Int): List<PendingMediaWebhook> {
        require(limit in 1..1000)
        return transaction { c ->
            c.prepareStatement("""
                SELECT i.*,t.session_id AS target_session_id FROM nearby_media_webhook_inbox i
                JOIN nearby_media_webhook_targets t ON t.event_sequence=i.sequence
                WHERE NOT i.processed AND NOT t.processed ORDER BY i.sequence,t.session_id LIMIT ?
            """.trimIndent()).use {
                it.setInt(1, limit)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PendingMediaWebhook(rows.getLong("sequence"), VerifiedMediaWebhook(
                        rows.getString("event_id"), rows.getString("body_sha256"), rows.getString("room_epoch"),
                        rows.getString("session_id"), rows.getString("participant_sid"), MediaWebhookKind.valueOf(rows.getString("kind")),
                    ), rows.getString("target_session_id")))
                } }
            }
        }
    }

    /** Call after a durable reconciliation outcome; retries and old hashes cannot consume new intent. */
    override fun complete(eventId: String, bodySha256: String, targetSessionId: String): Boolean = transaction { c ->
        // Serialize child ACKs on their parent. Checking all children without this lock can strand it.
        val sequence = c.prepareStatement("SELECT sequence FROM nearby_media_webhook_inbox WHERE event_id=? AND body_sha256=? FOR UPDATE").use {
            it.setString(1, eventId); it.setString(2, bodySha256)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        } ?: return@transaction false
        val completed = c.prepareStatement("UPDATE nearby_media_webhook_targets SET processed=TRUE WHERE event_sequence=? AND session_id=?::uuid").use {
            it.setLong(1, sequence); it.setString(2, targetSessionId); it.executeUpdate() == 1
        }
        if (completed) finishIfComplete(c, sequence)
        completed
    }

    private fun finishIfComplete(c: Connection, sequence: Long) {
        c.prepareStatement("""
            UPDATE nearby_media_webhook_inbox SET processed=TRUE WHERE sequence=?
            AND NOT EXISTS (SELECT 1 FROM nearby_media_webhook_targets WHERE event_sequence=? AND NOT processed)
        """.trimIndent()).use {
            it.setLong(1, sequence); it.setLong(2, sequence); it.executeUpdate()
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = source.connection.use { c ->
        c.autoCommit = false
        try {
            c.createStatement().use {
                it.execute("SET LOCAL lock_timeout = '5s'")
                it.execute("SET LOCAL statement_timeout = '10s'")
                it.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
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

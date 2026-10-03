package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import javax.sql.DataSource

data class PendingMediaWebhook(val sequence: Long, val event: VerifiedMediaWebhook)

/** Durable reconciliation intent only: receipt changes neither claims nor roster/media state. */
class JdbcMediaWebhookInbox(private val source: DataSource) : MediaWebhookEventPort {
    override fun accept(event: VerifiedMediaWebhook): MediaWebhookDisposition = transaction { c ->
        require(event.eventId.isNotBlank() && event.eventId.length <= 256 && event.bodySha256.matches(Regex("[a-f0-9]{64}")))
        c.prepareStatement("""
            INSERT INTO nearby_media_webhook_inbox(event_id,body_sha256,room_epoch,session_id,participant_sid,kind)
            VALUES (?,?,?,?::uuid,?,?) ON CONFLICT(event_id) DO NOTHING
        """.trimIndent()).use {
            it.setString(1, event.eventId); it.setString(2, event.bodySha256); it.setString(3, event.roomEpoch)
            it.setString(4, event.sessionId); it.setString(5, event.participantSid); it.setString(6, event.kind.name)
            it.executeUpdate()
        }
        c.prepareStatement("SELECT body_sha256 FROM nearby_media_webhook_inbox WHERE event_id=?").use {
            it.setString(1, event.eventId)
            it.executeQuery().use { rows ->
                check(rows.next())
                if (rows.getString(1) == event.bodySha256) MediaWebhookDisposition.ACCEPTED else MediaWebhookDisposition.CONFLICT
            }
        }
    }

    fun pending(limit: Int): List<PendingMediaWebhook> {
        require(limit in 1..1000)
        return transaction { c ->
            c.prepareStatement("SELECT * FROM nearby_media_webhook_inbox WHERE NOT processed ORDER BY sequence LIMIT ?").use {
                it.setInt(1, limit)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(PendingMediaWebhook(rows.getLong("sequence"), VerifiedMediaWebhook(
                        rows.getString("event_id"), rows.getString("body_sha256"), rows.getString("room_epoch"),
                        rows.getString("session_id"), rows.getString("participant_sid"), MediaWebhookKind.valueOf(rows.getString("kind")),
                    )))
                } }
            }
        }
    }

    /** Call after a durable reconciliation outcome; retries and old hashes cannot consume new intent. */
    fun complete(eventId: String, bodySha256: String): Boolean = transaction { c ->
        c.prepareStatement("UPDATE nearby_media_webhook_inbox SET processed=TRUE WHERE event_id=? AND body_sha256=?").use {
            it.setString(1, eventId); it.setString(2, bodySha256); it.executeUpdate() == 1
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

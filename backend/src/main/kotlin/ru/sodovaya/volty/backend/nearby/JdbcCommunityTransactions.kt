package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import javax.sql.DataSource

/** Access fence must precede actor/resource/revision locks. No network in block. */
internal fun <T> communityTransaction(source: DataSource, write: Boolean, block: (Connection) -> T): T = source.connection.use { c ->
    c.autoCommit = false
    if (!write) { c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ; c.isReadOnly = true }
    try {
        c.createStatement().use {
            it.execute("SET LOCAL lock_timeout = '5s'")
            it.execute("SET LOCAL statement_timeout = '10s'")
            it.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
        }
        if (write) c.prepareStatement("SELECT singleton FROM nearby_access_fence WHERE singleton=1 FOR UPDATE").use {
            it.executeQuery().use { rows -> check(rows.next()) }
        }
        val result = block(c)
        c.commit()
        result
    } catch (failure: Throwable) {
        try { c.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
        throw failure
    }
}

internal fun communityRevision(c: Connection): Long = c.createStatement().use {
    it.executeQuery("SELECT revision FROM nearby_roster_clock WHERE singleton=1").use { rows -> check(rows.next()); rows.getLong(1) }
}

internal fun appendCommunityChange(c: Connection, community: String, actor: String, kind: String, channel: String? = null, subject: String? = null) {
    val revision = c.createStatement().use {
        it.executeQuery("UPDATE nearby_roster_clock SET revision=revision+1 WHERE singleton=1 RETURNING revision").use { rows -> check(rows.next()); rows.getLong(1) }
    }
    c.prepareStatement("INSERT INTO nearby_community_outbox(revision,community_id,actor_id,kind,channel_id,subject_id) VALUES (?,?::uuid,?::uuid,?,?::uuid,?::uuid)").use {
        it.setLong(1, revision); it.setString(2, community); it.setString(3, actor); it.setString(4, kind)
        it.setString(5, channel); it.setString(6, subject); it.executeUpdate()
    }
}

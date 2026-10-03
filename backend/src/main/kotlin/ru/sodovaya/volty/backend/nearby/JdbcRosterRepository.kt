package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Internal reader; caller must authorize the channel. No membership preflight enables production. */
class JdbcRosterRepository(private val source: DataSource, private val now: () -> Long = System::currentTimeMillis) {
    fun snapshot(destination: String): ChannelRosterSnapshot = source.connection.use { c ->
        val channel = UUID.fromString(destination).toString()
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        c.isReadOnly = true
        try {
            c.createStatement().use {
                it.execute("SET LOCAL statement_timeout = '5s'")
                it.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'")
            }
            val revision = c.prepareStatement("SELECT revision FROM nearby_roster_clock WHERE singleton=1").use {
                it.executeQuery().use { rows -> check(rows.next()); rows.getLong(1) }
            }
            val peers = c.prepareStatement("""
                SELECT o.actor_id,o.operation_id,o.session_id,o.phase,o.deadline_epoch_millis
                FROM nearby_session_operations o JOIN nearby_actor_claims c
                  ON c.actor_id=o.actor_id AND c.operation_id=o.operation_id AND c.session_id=o.session_id
                 AND c.room_epoch=o.room_epoch AND c.node_epoch=o.node_epoch
                WHERE o.destination_id=?::uuid AND o.phase IN ('PENDING','ACTIVE')
                ORDER BY o.actor_id
            """.trimIndent()).use {
                it.setString(1, channel)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(ChannelRosterPeer(rows.getString("actor_id"), rows.getString("session_id"),
                        rows.getString("operation_id"), if (rows.getString("phase") == "PENDING") RosterConnectionPhase.JOINING else RosterConnectionPhase.CONNECTING,
                        rows.getLong("deadline_epoch_millis")))
                } }
            }
            // Do not filter using this clock: expiry changes rows/revision through the journal.
            val observedAt = now().also { require(it >= 0) }
            c.commit()
            ChannelRosterSnapshot(channel, revision, observedAt, peers)
        } catch (failure: Throwable) {
            try { c.rollback() } catch (rollback: Throwable) { failure.addSuppressed(rollback) }
            throw failure
        }
    }
}

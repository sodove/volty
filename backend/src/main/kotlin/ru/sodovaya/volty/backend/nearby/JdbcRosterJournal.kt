package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import javax.sql.DataSource

/** Internal delivery intent. It grants no reader access and contains no credentials. */
data class RosterJournalEvent(
    val revision: Long, val actorId: String, val operationId: String, val destinationId: String,
    val sessionId: String, val phase: OperationPhase, val deadlineEpochMillis: Long,
    val participantSid: String? = null, val mediaState: SfuParticipantState? = null,
)

class JdbcRosterJournal(private val source: DataSource) {
    fun revision(): Long = source.connection.use { c ->
        c.createStatement().use { it.executeQuery("SELECT revision FROM nearby_roster_clock WHERE singleton=1").use { rows ->
            check(rows.next()); rows.getLong(1)
        } }
    }

    /** Repeated cursor reads are intentional; consumer acknowledgement belongs to delivery. */
    fun eventsAfter(revision: Long, limit: Int): List<RosterJournalEvent> {
        require(revision >= 0 && limit in 1..1000)
        return source.connection.use { c ->
            c.prepareStatement("SELECT * FROM nearby_roster_outbox WHERE revision>? ORDER BY revision LIMIT ?").use {
                it.setLong(1, revision); it.setInt(2, limit)
                it.executeQuery().use { rows -> buildList {
                    while (rows.next()) add(RosterJournalEvent(rows.getLong("revision"), rows.getString("actor_id"),
                        rows.getString("operation_id"), rows.getString("destination_id"), rows.getString("session_id"),
                        OperationPhase.valueOf(rows.getString("phase")), rows.getLong("deadline_epoch_millis"),
                        rows.getString("participant_sid"), rows.getString("media_state")?.let(SfuParticipantState::valueOf)))
                } }
            }
        }
    }
}

/** Call only within the journal mutation transaction, after its actor lock. No network. */
internal fun appendRosterChange(c: Connection, row: SessionJournalEntry) {
    val operation = row.operation
    if (operation.destinationId == null) return // cancel-before-join has no roster entry
    val revision = c.createStatement().use {
        it.executeQuery("UPDATE nearby_roster_clock SET revision=revision+1 WHERE singleton=1 RETURNING revision").use { rows ->
            check(rows.next()); rows.getLong(1)
        }
    }
    c.prepareStatement("INSERT INTO nearby_roster_outbox(revision,actor_id,operation_id,destination_id,session_id,phase,deadline_epoch_millis,participant_sid,media_state) VALUES (?,?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?)").use {
        it.setLong(1, revision); it.setString(2, operation.actorId); it.setString(3, operation.operationId)
        it.setString(4, operation.destinationId); it.setString(5, checkNotNull(operation.sessionId))
        it.setString(6, operation.phase.name); it.setLong(7, checkNotNull(operation.deadlineEpochMillis))
        it.setString(8, row.media.participantSid); it.setString(9, row.media.state?.name); it.executeUpdate()
    }
}

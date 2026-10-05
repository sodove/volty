package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal class JdbcChannelSharingRepository(private val sessions: JdbcSessionRepository) {
    fun start(actor: String, session: String, generation: String, mode: ChannelSharingMode, ttl: ChannelSharingTtl, now: () -> Long): ChannelSharingConsent? {
        validate(actor, session, generation)
        return sessions.withChannelSharingActor(actor) {c ->
            val at = time(now)
            if (sessions.activeChannelSharingSession(c, actor, session, at) == null) return@withChannelSharingActor null
            expire(c, actor, session, at)
            val previous = read(c, actor, session, generation)
            if (previous != null) return@withChannelSharingActor previous.takeIf {it.phase == ChannelSharingPhase.ACTIVE}
            val occupied = c.prepareStatement("SELECT 1 FROM nearby_channel_sharing_consent WHERE actor_id=?::uuid AND session_id=?::uuid AND phase='ACTIVE'").use {
                it.setString(1, actor); it.setString(2, session); it.executeQuery().use {rows -> rows.next()}
            }
            if (occupied) return@withChannelSharingActor null
            val deadline = Math.addExact(at, ttl.millis)
            c.prepareStatement("INSERT INTO nearby_channel_sharing_consent VALUES (?::uuid,?::uuid,?::uuid,'ACTIVE',?,?)").use {
                it.setString(1, actor); it.setString(2, session); it.setString(3, generation); it.setString(4, mode.name); it.setLong(5, deadline); it.executeUpdate()
            }
            checkNotNull(read(c, actor, session, generation))
        }
    }

    /** Negative capability requires no active voice claim or current membership. */
    fun revoke(actor: String, session: String, generation: String, now: () -> Long): ChannelSharingConsent? {
        validate(actor, session, generation)
        return sessions.withChannelSharingActor(actor) {c ->
            time(now)
            c.prepareStatement("""
                INSERT INTO nearby_channel_sharing_consent(actor_id,session_id,generation_id,phase)
                VALUES (?::uuid,?::uuid,?::uuid,'REVOKED')
                ON CONFLICT(actor_id,session_id,generation_id) DO UPDATE SET phase='REVOKED'
                WHERE nearby_channel_sharing_consent.phase='ACTIVE'
            """.trimIndent()).use {
                it.setString(1, actor); it.setString(2, session); it.setString(3, generation); it.executeUpdate()
            }
            read(c, actor, session, generation)
        }
    }

    fun active(actor: String, session: String, generation: String, now: () -> Long): ChannelSharingConsent? {
        validate(actor, session, generation)
        return sessions.withChannelSharingActor(actor) {c ->
            val at = time(now)
            if (sessions.activeChannelSharingSession(c, actor, session, at) == null) return@withChannelSharingActor null
            expire(c, actor, session, at)
            read(c, actor, session, generation)?.takeIf {it.phase == ChannelSharingPhase.ACTIVE}
        }
    }

    private fun expire(c: Connection, actor: String, session: String, at: Long) {
        c.prepareStatement("UPDATE nearby_channel_sharing_consent SET phase='EXPIRED' WHERE actor_id=?::uuid AND session_id=?::uuid AND phase='ACTIVE' AND deadline_epoch_millis<=?").use {
            it.setString(1, actor); it.setString(2, session); it.setLong(3, at); it.executeUpdate()
        }
    }
    private fun read(c: Connection, actor: String, session: String, generation: String): ChannelSharingConsent? =
        c.prepareStatement("SELECT * FROM nearby_channel_sharing_consent WHERE actor_id=?::uuid AND session_id=?::uuid AND generation_id=?::uuid").use {
            it.setString(1, actor); it.setString(2, session); it.setString(3, generation)
            it.executeQuery().use {rows -> if (rows.next()) rows.consent() else null}
        }
    private fun ResultSet.consent() = ChannelSharingConsent(getString("actor_id"), getString("session_id"), getString("generation_id"),
        ChannelSharingPhase.valueOf(getString("phase")), getString("mode")?.let(ChannelSharingMode::valueOf),
        getLong("deadline_epoch_millis").takeUnless {wasNull()})
    private fun validate(vararg ids: String) {ids.forEach {require(UUID.fromString(it).toString() == it)}}
    private fun time(now: () -> Long) = now().also {require(it >= 0)}
}

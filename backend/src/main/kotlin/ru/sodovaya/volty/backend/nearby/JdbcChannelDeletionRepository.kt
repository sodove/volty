package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Channel-only durable deletion. Membership/default replacement are never side effects. */
class JdbcChannelDeletionRepository(private val source: DataSource) {
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())
    fun deleteChannel(actor: String, operation: String, community: String, channel: String, now: () -> Long): ChannelDeletionResult {
        listOf(actor, operation, community, channel).forEach { require(UUID.fromString(it).toString() == it) }
        return communityTransaction(source, write = true) { c ->
            val active = c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL").use {
                it.setString(1, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!active) return@communityTransaction ChannelDeletionResult.Denied
            val old = c.prepareStatement("SELECT community_id,channel_id,revision FROM nearby_resource_deletions WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
                it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getLong(3)) else null
                }
            }
            if (old != null) {
                if (old.first != community || old.second != channel) return@communityTransaction ChannelDeletionResult.Conflict
                return@communityTransaction ChannelDeletionResult.Deleted(community, channel, old.third, targets(c, actor, operation))
            }
            val owned = c.prepareStatement("""
                SELECT 1 FROM nearby_voice_channels v JOIN nearby_communities g ON g.id=v.community_id
                WHERE v.id=?::uuid AND v.community_id=?::uuid AND v.state='ACTIVE' AND g.state='ACTIVE' AND g.owner_id=?::uuid
            """.trimIndent()).use {
                it.setString(1, channel); it.setString(2, community); it.setString(3, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!owned) return@communityTransaction ChannelDeletionResult.Denied
            val actors = c.prepareStatement("""
                SELECT a.actor_id FROM nearby_actor_claims a LEFT JOIN nearby_session_operations o
                ON o.actor_id=a.actor_id AND o.operation_id=a.operation_id
                WHERE (o.destination_kind='CHANNEL' AND o.destination_id=?::uuid) OR a.room_epoch=? ORDER BY a.actor_id
            """.trimIndent()).use {
                it.setString(1, channel); it.setString(2, "volty-channel-$channel")
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            sessions.lockCommunityActors(c, actors)
            val at = now().also { require(it >= 0) }
            val cleanup = actors.flatMap { sessions.cancelCurrentChannelClaim(c, it, channel, at) }
            c.prepareStatement("UPDATE nearby_voice_channels SET state='DELETED' WHERE id=?::uuid AND community_id=?::uuid").use {
                it.setString(1, channel); it.setString(2, community); check(it.executeUpdate() == 1)
            }
            appendCommunityChange(c, community, actor, "CHANNEL_DELETED", channel)
            val revision = communityRevision(c)
            c.prepareStatement("INSERT INTO nearby_resource_deletions(actor_id,operation_id,community_id,channel_id,revision) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, community); it.setString(4, channel); it.setLong(5, revision); it.executeUpdate()
            }
            for (target in cleanup) c.prepareStatement("INSERT INTO nearby_resource_deletion_targets(actor_id,operation_id,session_id) VALUES (?::uuid,?::uuid,?::uuid)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, target.operation.sessionId); it.executeUpdate()
            }
            ChannelDeletionResult.Deleted(community, channel, revision, cleanup)
        }
    }
    private fun targets(c: Connection, actor: String, operation: String): List<SessionJournalEntry> =
        c.prepareStatement("SELECT session_id FROM nearby_resource_deletion_targets WHERE actor_id=?::uuid AND operation_id=?::uuid ORDER BY session_id").use {
            it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(checkNotNull(sessions.readSession(c, rows.getString(1)))) }
            }
        }
}

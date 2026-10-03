package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Owner deletion foundation. No network under SQL locks, no public route yet. */
class JdbcCommunityDeletionRepository(private val source: DataSource) {
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    fun deleteCommunity(actor: String, operation: String, community: String, now: () -> Long): CommunityDeletionResult {
        listOf(actor, operation, community).forEach { require(UUID.fromString(it).toString() == it) }
        return communityTransaction(source, write = true) { c ->
            if (!activeUser(c, actor)) return@communityTransaction CommunityDeletionResult.Denied
            val old = c.prepareStatement("SELECT community_id,channel_id,revision FROM nearby_resource_deletions WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
                it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getLong(3)) else null
                }
            }
            if (old != null) {
                if (old.first != community || old.second != null) return@communityTransaction CommunityDeletionResult.Conflict
                return@communityTransaction CommunityDeletionResult.Deleted(community, old.third, targets(c, actor, operation))
            }
            val owned = c.prepareStatement("SELECT 1 FROM nearby_communities WHERE id=?::uuid AND owner_id=?::uuid AND state='ACTIVE'").use {
                it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!owned) return@communityTransaction CommunityDeletionResult.Denied
            val actors = c.prepareStatement("""
                SELECT user_id::text AS actor FROM nearby_community_members WHERE community_id=?::uuid
                UNION
                SELECT a.actor_id::text AS actor FROM nearby_actor_claims a
                JOIN nearby_session_operations o ON o.actor_id=a.actor_id AND o.operation_id=a.operation_id
                JOIN nearby_voice_channels v ON v.id=o.destination_id WHERE v.community_id=?::uuid
                ORDER BY actor
            """.trimIndent()).use {
                it.setString(1, community); it.setString(2, community)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
            sessions.lockCommunityActors(c, actors)
            val at = now().also { require(it >= 0) }
            val cleanup = actors.flatMap { sessions.cancelCurrentCommunityClaim(c, it, community, at) }
            c.prepareStatement("UPDATE nearby_voice_channels SET state='DELETED' WHERE community_id=?::uuid").use {
                it.setString(1, community); it.executeUpdate()
            }
            c.prepareStatement("UPDATE nearby_communities SET state='DELETED' WHERE id=?::uuid").use {
                it.setString(1, community); check(it.executeUpdate() == 1)
            }
            // Retain historical membership rows: V7's deferred owner/member FK
            // still applies to tombstones. All active reads/admissions exclude them.
            c.prepareStatement("DELETE FROM groups WHERE id=?::uuid").use { it.setString(1, community); it.executeUpdate() }
            appendCommunityChange(c, community, actor, "DELETED")
            val revision = communityRevision(c)
            c.prepareStatement("INSERT INTO nearby_resource_deletions(actor_id,operation_id,community_id,revision) VALUES (?::uuid,?::uuid,?::uuid,?)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, community); it.setLong(4, revision); it.executeUpdate()
            }
            for (target in cleanup) c.prepareStatement("INSERT INTO nearby_resource_deletion_targets(actor_id,operation_id,session_id) VALUES (?::uuid,?::uuid,?::uuid)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, target.operation.sessionId); it.executeUpdate()
            }
            CommunityDeletionResult.Deleted(community, revision, cleanup)
        }
    }

    private fun targets(c: Connection, actor: String, operation: String): List<SessionJournalEntry> =
        c.prepareStatement("SELECT session_id FROM nearby_resource_deletion_targets WHERE actor_id=?::uuid AND operation_id=?::uuid ORDER BY session_id").use {
            it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(checkNotNull(sessions.readSession(c, rows.getString(1)))) }
            }
        }

    private fun activeUser(c: Connection, actor: String) = c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL").use {
        it.setString(1, actor); it.executeQuery().use { rows -> rows.next() }
    }
}

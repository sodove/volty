package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Account tombstone, membership/resources and durable cleanup share the access fence. */
class JdbcAccountDeletionRepository(private val source: DataSource) {
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    fun deleteAccount(actor: String, now: () -> Long): Int {
        require(UUID.fromString(actor).toString() == actor)
        return communityTransaction(source, write = true) { c ->
            if (ids(c, "SELECT id::text FROM users WHERE id=?::uuid AND deleted_at IS NULL", actor).isEmpty())
                return@communityTransaction 0
            val owned = ids(c, "SELECT id::text FROM nearby_communities WHERE owner_id=?::uuid AND state='ACTIVE' ORDER BY id", actor)
            val memberships = ids(c, """
                SELECT g.id::text FROM nearby_communities g JOIN nearby_community_members m ON m.community_id=g.id
                WHERE m.user_id=?::uuid AND g.owner_id<>?::uuid AND g.state='ACTIVE' ORDER BY g.id
            """.trimIndent(), actor, actor)
            val affected = ids(c, """
                SELECT m.user_id::text FROM nearby_community_members m JOIN nearby_communities g ON g.id=m.community_id
                WHERE g.owner_id=?::uuid AND g.state='ACTIVE'
                UNION
                SELECT a.actor_id::text FROM nearby_actor_claims a
                JOIN nearby_session_operations o ON o.actor_id=a.actor_id AND o.operation_id=a.operation_id
                JOIN nearby_voice_channels v ON v.id=o.destination_id AND o.destination_kind='CHANNEL' JOIN nearby_communities g ON g.id=v.community_id
                WHERE g.owner_id=?::uuid AND g.state='ACTIVE'
            """.trimIndent(), actor, actor) + actor
            // Acquire ALL actor locks before any cancellation increments the roster revision.
            sessions.lockCommunityActors(c, affected)
            val at = now().also { require(it >= 0) }
            sessions.cancelCurrentAccountClaim(c, actor, at)
            for (peer in affected.distinct().sorted().filter { it != actor })
                for (community in owned) sessions.cancelCurrentCommunityClaim(c, peer, community, at)

            update(c, "UPDATE users SET deleted_at=?,tokens_revoked_at=? WHERE id=?::uuid AND deleted_at IS NULL", at, at / 1000, actor).also { check(it == 1) }
            update(c, "UPDATE refresh_tokens SET revoked_at=? WHERE user_id=?::uuid AND revoked_at IS NULL", at / 1000, actor)
            update(c, "DELETE FROM live_updates WHERE user_id=?::uuid", actor)
            update(c, "DELETE FROM sharing_sessions WHERE user_id=?::uuid", actor)
            // Historical owner membership is required by V7's deferred owner/member FK.
            update(c, """
                DELETE FROM nearby_community_members m USING nearby_communities g
                WHERE m.community_id=g.id AND m.user_id=?::uuid AND g.owner_id<>?::uuid
            """.trimIndent(), actor, actor)
            for (community in owned) {
                update(c, "UPDATE nearby_voice_channels SET state='DELETED' WHERE community_id=?::uuid", community)
                check(update(c, "UPDATE nearby_communities SET state='DELETED' WHERE id=?::uuid", community) == 1)
                update(c, "DELETE FROM groups WHERE id=?::uuid", community)
                appendCommunityChange(c, community, actor, "DELETED")
            }
            // Also close legacy-only groups created before the final cutover backfill.
            update(c, "DELETE FROM groups WHERE owner_id=?::uuid", actor)
            update(c, "DELETE FROM group_members WHERE user_id=?::uuid", actor)
            for (community in memberships) appendCommunityChange(c, community, actor, "MEMBER_LEFT", subject = actor)
            1
        }
    }

    private fun ids(c: Connection, sql: String, vararg args: String): List<String> = c.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }
    private fun update(c: Connection, sql: String, vararg args: Any): Int = c.prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> when (value) {
            is String -> statement.setString(index + 1, value)
            is Long -> statement.setLong(index + 1, value)
            else -> error("Unsupported SQL argument")
        } }
        statement.executeUpdate()
    }
}

package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.util.UUID
import javax.sql.DataSource

/** Atomic departure foundation, no public route or SFU calls under SQL locks. */
class JdbcCommunityMembershipRepository(private val source: DataSource) {
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    fun leave(actor: String, operation: String, community: String, successor: String? = null, now: () -> Long): CommunityDepartureResult {
        listOfNotNull(actor, operation, community, successor).forEach { require(UUID.fromString(it).toString() == it) }
        return communityTransaction(source, write = true) { c ->
            if (!activeUser(c, actor)) return@communityTransaction CommunityDepartureResult.Denied
            val existing = c.prepareStatement("SELECT community_id,successor_id,revision FROM nearby_community_departures WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
                it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getLong(3)) else null
                }
            }
            if (existing != null) {
                if (existing.first != community || existing.second != successor) return@communityTransaction CommunityDepartureResult.Conflict
                return@communityTransaction receipt(c, actor, operation, community, existing.third)
            }
            val owner = c.prepareStatement("""
                SELECT g.owner_id FROM nearby_communities g JOIN nearby_community_members m ON m.community_id=g.id
                WHERE g.id=?::uuid AND m.user_id=?::uuid AND g.state='ACTIVE'
            """.trimIndent()).use {
                it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            } ?: return@communityTransaction CommunityDepartureResult.Denied
            if (owner != actor && successor != null) return@communityTransaction CommunityDepartureResult.InvalidSuccessor
            if (owner == actor) {
                val hasOthers = c.prepareStatement("SELECT 1 FROM nearby_community_members WHERE community_id=?::uuid AND user_id<>?::uuid LIMIT 1").use {
                    it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
                }
                if (!hasOthers) return@communityTransaction CommunityDepartureResult.DeleteRequired
                if (successor == null) return@communityTransaction CommunityDepartureResult.SuccessorRequired
                if (successor == actor || !eligibleSuccessor(c, community, successor)) return@communityTransaction CommunityDepartureResult.InvalidSuccessor
            }
            val at = now().also { require(it >= 0) }
            // The UPDATE fence excludes normal guarded session/ACL writers before actor locking.
            val targets = sessions.cancelCurrentCommunityClaim(c, actor, community, at)
            if (owner == actor) {
                c.prepareStatement("UPDATE nearby_communities SET owner_id=?::uuid WHERE id=?::uuid").use {
                    it.setString(1, successor); it.setString(2, community); check(it.executeUpdate() == 1)
                }
                transferLegacy(c, community, checkNotNull(successor))
                appendCommunityChange(c, community, actor, "OWNER_TRANSFERRED", subject = successor)
            }
            c.prepareStatement("DELETE FROM nearby_community_members WHERE community_id=?::uuid AND user_id=?::uuid").use {
                it.setString(1, community); it.setString(2, actor); check(it.executeUpdate() == 1)
            }
            c.prepareStatement("DELETE FROM group_members WHERE group_id=?::uuid AND user_id=?::uuid").use {
                it.setString(1, community); it.setString(2, actor); it.executeUpdate()
            }
            c.prepareStatement("UPDATE sharing_sessions SET revoked_at=? WHERE user_id=?::uuid AND group_id=?::uuid AND revoked_at IS NULL").use {
                it.setLong(1, at); it.setString(2, actor); it.setString(3, community); it.executeUpdate()
            }
            c.prepareStatement("DELETE FROM live_updates WHERE user_id=?::uuid AND group_id=?::uuid").use {
                it.setString(1, actor); it.setString(2, community); it.executeUpdate()
            }
            appendCommunityChange(c, community, actor, "MEMBER_LEFT", subject = actor)
            val revision = communityRevision(c)
            c.prepareStatement("INSERT INTO nearby_community_departures(actor_id,operation_id,community_id,successor_id,revision) VALUES (?::uuid,?::uuid,?::uuid,?::uuid,?)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, community); it.setString(4, successor); it.setLong(5, revision); it.executeUpdate()
            }
            for (target in targets) c.prepareStatement("INSERT INTO nearby_departure_targets(actor_id,operation_id,session_id) VALUES (?::uuid,?::uuid,?::uuid)").use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, target.operation.sessionId); it.executeUpdate()
            }
            CommunityDepartureResult.Departed(community, revision, targets)
        }
    }

    private fun receipt(c: Connection, actor: String, operation: String, community: String, revision: Long): CommunityDepartureResult.Departed {
        val targets = c.prepareStatement("SELECT session_id FROM nearby_departure_targets WHERE actor_id=?::uuid AND operation_id=?::uuid ORDER BY session_id").use {
            it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        return CommunityDepartureResult.Departed(community, revision, targets.map { checkNotNull(sessions.readSession(c, it)) })
    }
    private fun eligibleSuccessor(c: Connection, community: String, successor: String): Boolean = c.prepareStatement("""
        SELECT 1 FROM nearby_community_members m JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL
        WHERE m.community_id=?::uuid AND m.user_id=?::uuid
        AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=m.community_id AND b.user_id=m.user_id)
    """.trimIndent()).use {
        it.setString(1, community); it.setString(2, successor); it.executeQuery().use { rows -> rows.next() }
    }
    private fun activeUser(c: Connection, actor: String) = c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL").use {
        it.setString(1, actor); it.executeQuery().use { rows -> rows.next() }
    }
    private fun transferLegacy(c: Connection, community: String, successor: String) {
        c.prepareStatement("UPDATE groups SET owner_id=?::uuid WHERE id=?::uuid").use {
            it.setString(1, successor); it.setString(2, community); it.executeUpdate()
        }
        c.prepareStatement("""
            INSERT INTO group_members(group_id,user_id,role,joined_at)
            SELECT g.id,m.user_id,'OWNER',m.joined_at FROM groups g JOIN nearby_community_members m ON m.community_id=g.id
            WHERE g.id=?::uuid AND m.user_id=?::uuid ON CONFLICT(group_id,user_id) DO UPDATE SET role='OWNER'
        """.trimIndent()).use { it.setString(1, community); it.setString(2, successor); it.executeUpdate() }
        c.prepareStatement("UPDATE group_members SET role='MEMBER' WHERE group_id=?::uuid AND user_id<>?::uuid").use {
            it.setString(1, community); it.setString(2, successor); it.executeUpdate()
        }
    }
}

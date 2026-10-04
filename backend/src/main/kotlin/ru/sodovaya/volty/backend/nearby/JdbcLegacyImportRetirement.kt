package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

/**
 * Scope changed 2026-10-04: V7's group transfer is retracted. Preserve historical
 * migration SQL and legacy data, but never serve its imports as new servers.
 * Native create always persists an immutable request hash; historical imports
 * alone have null hashes. Run after migration and before HTTP dependencies.
 */
class JdbcLegacyImportRetirement(private val source: DataSource) {
    private val sessions = JdbcSessionRepository(source, JdbcChannelAccess())

    fun retire(now: () -> Long): Int = communityTransaction(source, write = true) { c ->
        val imports = c.createStatement().use { statement ->
            statement.executeQuery("SELECT id,owner_id FROM nearby_communities WHERE create_request_hash IS NULL AND state='ACTIVE' ORDER BY id").use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
            }
        }
        if (imports.isEmpty()) return@communityTransaction 0
        val actors = c.createStatement().use { statement ->
            statement.executeQuery("""
                SELECT m.user_id::text FROM nearby_community_members m JOIN nearby_communities g ON g.id=m.community_id
                WHERE g.create_request_hash IS NULL AND g.state='ACTIVE'
                UNION
                SELECT a.actor_id::text FROM nearby_actor_claims a
                JOIN nearby_session_operations o ON o.actor_id=a.actor_id AND o.operation_id=a.operation_id
                JOIN nearby_voice_channels v ON v.id=o.destination_id AND o.destination_kind='CHANNEL' JOIN nearby_communities g ON g.id=v.community_id
                WHERE g.create_request_hash IS NULL AND g.state='ACTIVE'
            """.trimIndent()).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
        }
        sessions.lockCommunityActors(c, actors)
        val at = now().also { require(it >= 0) }
        actors.distinct().sorted().forEach { sessions.cancelCurrentImportedClaim(c, it, at) }
        for ((community, owner) in imports) {
            c.prepareStatement("UPDATE nearby_voice_channels SET state='DELETED' WHERE community_id=?::uuid").use {
                it.setString(1, community); it.executeUpdate()
            }
            c.prepareStatement("UPDATE nearby_communities SET state='DELETED' WHERE id=?::uuid").use {
                it.setString(1, community); check(it.executeUpdate() == 1)
            }
            // Keep historical owner/member rows for V7's deferred FK. Active
            // reads and admission exclude the community/channel tombstones.
            appendCommunityChange(c, community, owner, "DELETED")
        }
        imports.size
    }
}

package ru.sodovaya.volty.backend.nearby

import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

internal fun invitationIds(vararg ids: String) { ids.forEach { require(UUID.fromString(it).toString() == it) } }
internal fun invitationOwner(c: Connection, actor: String, community: String): Boolean = c.prepareStatement("""
    SELECT 1 FROM nearby_communities g JOIN users u ON u.id=g.owner_id AND u.deleted_at IS NULL
    WHERE g.id=?::uuid AND g.owner_id=?::uuid AND g.state='ACTIVE'
""".trimIndent()).use {
    it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
}
internal fun ResultSet.invitationSummary() = InvitationSummary(
    getString("id"), getString("community_id"), getLong("created_at"), getLong("expires_at"),
    getInt("max_uses"), getInt("uses"), getLong("revoked_at").takeUnless { wasNull() },
    getLong("creation_revision"), getLong("revocation_revision").takeUnless { wasNull() },
)

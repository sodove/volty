package ru.sodovaya.volty.backend.nearby

import java.sql.Connection

/** SQL-only permission query on the caller's transaction; no preflight connection. */
interface SessionAccessPolicy {
    fun allows(connection: Connection, actor: String, destination: String, roomEpoch: String?): Boolean
    fun allows(connection: Connection, actor: String, destination: String, roomEpoch: String?, kind: VoiceDestinationKind): Boolean =
        kind == VoiceDestinationKind.CHANNEL && allows(connection, actor, destination, roomEpoch)
}

class JdbcChannelAccess : SessionAccessPolicy {
    override fun allows(connection: Connection, actor: String, destination: String, roomEpoch: String?): Boolean {
        if (roomEpoch != null && roomEpoch != "volty-channel-$destination") return false
        return connection.prepareStatement("""
            SELECT 1 FROM nearby_voice_channels v JOIN nearby_communities g ON g.id=v.community_id
            JOIN nearby_community_members m ON m.community_id=g.id AND m.user_id=?::uuid
            JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL
            WHERE v.id=?::uuid AND v.state='ACTIVE' AND g.state='ACTIVE'
            AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=m.user_id)
        """.trimIndent()).use {
            it.setString(1, actor); it.setString(2, destination); it.executeQuery().use { rows -> rows.next() }
        }
    }
}

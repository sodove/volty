package ru.sodovaya.volty.backend.nearby

import java.security.MessageDigest
import java.util.UUID
import javax.sql.DataSource

/** Owner-only channel creation; no microphone, claim, membership or sharing side effects. */
class JdbcChannelCreationRepository(private val source: DataSource) {
    fun create(actor: String, community: String, channel: String, name: String, now: () -> Long): ChannelCreationResult {
        listOf(actor, community, channel).forEach { require(UUID.fromString(it).toString() == it) }
        val title = name.trim()
        require(title.length in 1..80 && title.none(Char::isISOControl))
        val hash = MessageDigest.getInstance("SHA-256").digest(listOf(actor, community, channel, title)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return communityTransaction(source, write = true) { c ->
            val owned = c.prepareStatement("""
                SELECT 1 FROM nearby_communities g JOIN users u ON u.id=g.owner_id AND u.deleted_at IS NULL
                WHERE g.id=?::uuid AND g.owner_id=?::uuid AND g.state='ACTIVE'
            """.trimIndent()).use {
                it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!owned) return@communityTransaction ChannelCreationResult.Denied
            val existing = c.prepareStatement("""
                SELECT v.community_id,v.name,v.is_default,v.state,r.actor_id,r.community_id AS receipt_community,r.payload_hash,r.revision
                FROM nearby_voice_channels v LEFT JOIN nearby_channel_creations r ON r.channel_id=v.id WHERE v.id=?::uuid
            """.trimIndent()).use {
                it.setString(1, channel); it.executeQuery().use { rows ->
                    if (!rows.next()) null else if (rows.getString("community_id") == community &&
                        rows.getString("receipt_community") == community && rows.getString("actor_id") == actor &&
                        rows.getString("payload_hash") == hash && rows.getString("state") == "ACTIVE" && !rows.getBoolean("is_default"))
                        ChannelCreationResult.Created(CommunityChannel(channel, rows.getString("name"), rows.getBoolean("is_default")), rows.getLong("revision"))
                    else ChannelCreationResult.Conflict
                }
            }
            if (existing != null) return@communityTransaction existing
            val count = c.prepareStatement("SELECT count(*) FROM nearby_voice_channels WHERE community_id=?::uuid AND state='ACTIVE'").use {
                it.setString(1, community); it.executeQuery().use { rows -> check(rows.next()); rows.getInt(1) }
            }
            if (count >= MAX_ACTIVE_CHANNELS) return@communityTransaction ChannelCreationResult.LimitReached
            val at = now().also { require(it >= 0) }
            c.prepareStatement("INSERT INTO nearby_voice_channels(id,community_id,name,created_at) VALUES (?::uuid,?::uuid,?,?)").use {
                it.setString(1, channel); it.setString(2, community); it.setString(3, title); it.setLong(4, at); it.executeUpdate()
            }
            appendCommunityChange(c, community, actor, "CHANNEL_CREATED", channel)
            val revision = communityRevision(c)
            c.prepareStatement("INSERT INTO nearby_channel_creations(channel_id,actor_id,community_id,payload_hash,revision) VALUES (?::uuid,?::uuid,?::uuid,?,?)").use {
                it.setString(1, channel); it.setString(2, actor); it.setString(3, community); it.setString(4, hash); it.setLong(5, revision); it.executeUpdate()
            }
            ChannelCreationResult.Created(CommunityChannel(channel, title, false), revision)
        }
    }
    private companion object { const val MAX_ACTIVE_CHANNELS = 128 }
}

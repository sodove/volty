package ru.sodovaya.volty.backend.nearby

import java.security.MessageDigest
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

/** Foundation only. No session ACL adapter or membership-removal writes yet. */
class JdbcCommunityRepository(private val source: DataSource) {
    fun create(actor: String, community: String, name: String, description: String = "",
               visibility: CommunityVisibility = CommunityVisibility.PRIVATE, now: () -> Long): CommunityWriteResult {
        canonicalId(actor); canonicalId(community)
        val title = name.trim(); val about = description.trim()
        require(title.length in 1..80 && title.none(Char::isISOControl))
        require(about.length <= 1000 && '\u0000' !in about)
        val hash = digest("SHA-256", listOf(actor, community, title, about, visibility.name).joinToString("\u0000"))
        return communityTransaction(source, write = true) { c ->
            if (!activeUser(c, actor)) return@communityTransaction CommunityWriteResult.Denied
            val existing = c.prepareStatement("SELECT created_by,create_request_hash,state FROM nearby_communities WHERE id=?::uuid").use {
                it.setString(1, community); it.executeQuery().use { rows ->
                    if (rows.next()) Triple(rows.getString(1), rows.getString(2), rows.getString(3)) else null
                }
            }
            if (existing != null) {
                if (existing.first != actor) return@communityTransaction CommunityWriteResult.Denied
                if (existing.second != hash || existing.third != "ACTIVE") return@communityTransaction CommunityWriteResult.Conflict
                val snapshot = authorizedSnapshot(c, actor, community) ?: return@communityTransaction CommunityWriteResult.Denied
                return@communityTransaction CommunityWriteResult.Accepted(snapshot)
            }
            val createdAt = now().also { require(it >= 0) }
            c.prepareStatement("INSERT INTO nearby_communities(id,name,description,owner_id,created_by,create_request_hash,visibility,created_at) VALUES (?::uuid,?,?,?::uuid,?::uuid,?,?,?)").use {
                it.setString(1, community); it.setString(2, title); it.setString(3, about); it.setString(4, actor)
                it.setString(5, actor); it.setString(6, hash); it.setString(7, visibility.name); it.setLong(8, createdAt); it.executeUpdate()
            }
            insertMember(c, actor, community, createdAt)
            val hex = digest("MD5", "volty-default-channel:$community") // Stable ID only, never an access token.
            val channel = UUID.fromString("${hex.take(8)}-${hex.substring(8,12)}-${hex.substring(12,16)}-${hex.substring(16,20)}-${hex.substring(20)}").toString()
            c.prepareStatement("INSERT INTO nearby_voice_channels(id,community_id,name,is_default,created_at) VALUES (?::uuid,?::uuid,'Общий',TRUE,?)").use {
                it.setString(1, channel); it.setString(2, community); it.setLong(3, createdAt); it.executeUpdate()
            }
            appendCommunityChange(c, community, actor, "CREATED", channel)
            CommunityWriteResult.Accepted(checkNotNull(authorizedSnapshot(c, actor, community)))
        }
    }

    fun joinPublic(actor: String, community: String, now: () -> Long): CommunityWriteResult {
        canonicalId(actor); canonicalId(community)
        return communityTransaction(source, write = true) { c ->
            if (!activeUser(c, actor)) return@communityTransaction CommunityWriteResult.Denied
            val allowed = c.prepareStatement("""
                SELECT 1 FROM nearby_communities g WHERE g.id=?::uuid AND g.visibility='PUBLIC' AND g.state='ACTIVE'
                AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=?::uuid)
            """.trimIndent()).use {
                it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!allowed) return@communityTransaction CommunityWriteResult.Denied
            if (insertMember(c, actor, community, now().also { require(it >= 0) })) appendCommunityChange(c, community, actor, "MEMBER_JOINED", subject = actor)
            CommunityWriteResult.Accepted(checkNotNull(authorizedSnapshot(c, actor, community)))
        }
    }

    fun detail(actor: String, community: String): CommunitySnapshot? {
        canonicalId(actor); canonicalId(community)
        return communityTransaction(source, write = false) { authorizedSnapshot(it, actor, community) }
    }

    fun memberships(actor: String, after: String? = null, limit: Int = 50): List<CommunitySummary> {
        canonicalId(actor); after?.let(::canonicalId)
        require(limit in 1..100)
        return communityTransaction(source, write = false) { c ->
            c.prepareStatement("""
                SELECT g.* FROM nearby_communities g JOIN nearby_community_members m ON m.community_id=g.id
                JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL
                WHERE m.user_id=?::uuid AND g.state='ACTIVE'
                AND (?::uuid IS NULL OR g.id>?::uuid)
                AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=m.user_id)
                ORDER BY g.id LIMIT ?
            """.trimIndent()).use {
                it.setString(1, actor); it.setString(2, after); it.setString(3, after); it.setInt(4, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.summary()) } }
            }
        }
    }

    fun catalog(actor: String, query: String, after: String? = null, limit: Int = 20): List<CommunitySummary> {
        canonicalId(actor); after?.let(::canonicalId)
        require(query.length <= 80 && limit in 1..50)
        return communityTransaction(source, write = false) { c ->
            if (!activeUser(c, actor)) return@communityTransaction emptyList()
            c.prepareStatement("""
                SELECT g.* FROM nearby_communities g WHERE g.state='ACTIVE' AND g.visibility='PUBLIC'
                AND position(lower(?) in lower(g.name))>0 AND (?::uuid IS NULL OR g.id>?::uuid)
                AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=?::uuid)
                ORDER BY g.id LIMIT ?
            """.trimIndent()).use {
                it.setString(1, query.trim()); it.setString(2, after); it.setString(3, after); it.setString(4, actor); it.setInt(5, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.summary()) } }
            }
        }
    }

    private fun authorizedSnapshot(c: Connection, actor: String, community: String): CommunitySnapshot? {
        val revision = communityRevision(c) // Pins the repeatable read before ACL/rows.
        val summary = c.prepareStatement("""
            SELECT g.* FROM nearby_communities g JOIN nearby_community_members m ON m.community_id=g.id
            JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL
            WHERE g.id=?::uuid AND m.user_id=?::uuid AND g.state='ACTIVE'
            AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=m.user_id)
        """.trimIndent()).use {
            it.setString(1, community); it.setString(2, actor); it.executeQuery().use { rows -> if (rows.next()) rows.summary() else null }
        } ?: return null
        val channels = c.prepareStatement("SELECT id,name,is_default FROM nearby_voice_channels WHERE community_id=?::uuid AND state='ACTIVE' ORDER BY is_default DESC,id").use {
            it.setString(1, community); it.executeQuery().use { rows -> buildList {
                while (rows.next()) add(CommunityChannel(rows.getString(1), rows.getString(2), rows.getBoolean(3)))
            } }
        }
        return CommunitySnapshot(revision, summary, channels)
    }

    private fun insertMember(c: Connection, actor: String, community: String, at: Long): Boolean =
        c.prepareStatement("INSERT INTO nearby_community_members(community_id,user_id,joined_at) VALUES (?::uuid,?::uuid,?) ON CONFLICT DO NOTHING").use {
            it.setString(1, community); it.setString(2, actor); it.setLong(3, at); it.executeUpdate() == 1
        }
    private fun activeUser(c: Connection, actor: String) = c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL").use {
        it.setString(1, actor); it.executeQuery().use { rows -> rows.next() }
    }
    private fun ResultSet.summary() = CommunitySummary(getString("id"), getString("name"), getString("description"),
        CommunityVisibility.valueOf(getString("visibility")), getString("owner_id"), getLong("created_at"))
    private fun canonicalId(value: String) { require(UUID.fromString(value).toString() == value) }
    private fun digest(algorithm: String, text: String) = MessageDigest.getInstance(algorithm).digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

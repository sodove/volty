package ru.sodovaya.volty.backend.nearby

import java.util.UUID
import javax.sql.DataSource

/** Owner-visible eligible membership; departure still rechecks the chosen member atomically. */
class JdbcCommunityMembersRepository(private val source:DataSource):CommunityMembersEndpointPort {
    override fun list(actor:String,community:String,after:String?,limit:Int):CommunityMembersResult {
        listOfNotNull(actor,community,after).forEach {require(UUID.fromString(it).toString()==it)}
        require(limit in 1..50)
        return communityTransaction(source,write=false) {c ->
            val allowed=c.prepareStatement("""
                SELECT 1 FROM nearby_communities g
                JOIN users u ON u.id=g.owner_id AND u.deleted_at IS NULL
                JOIN nearby_community_members m ON m.community_id=g.id AND m.user_id=g.owner_id
                WHERE g.id=?::uuid AND g.owner_id=?::uuid AND g.state='ACTIVE'
                AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=g.id AND b.user_id=g.owner_id)
            """.trimIndent()).use {
                it.setString(1,community);it.setString(2,actor);it.executeQuery().use {rows -> rows.next()}
            }
            if(!allowed) return@communityTransaction CommunityMembersResult.Denied
            val items=c.prepareStatement("""
                SELECT m.user_id,u.display_name,m.joined_at FROM nearby_community_members m
                JOIN users u ON u.id=m.user_id AND u.deleted_at IS NULL
                WHERE m.community_id=?::uuid AND (?::uuid IS NULL OR m.user_id>?::uuid)
                AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=m.community_id AND b.user_id=m.user_id)
                ORDER BY m.user_id LIMIT ?
            """.trimIndent()).use {
                it.setString(1,community);it.setString(2,after);it.setString(3,after);it.setInt(4,limit)
                it.executeQuery().use {rows -> buildList {
                    while(rows.next()) add(CommunityMember(rows.getString("user_id"),rows.getString("display_name"),rows.getLong("joined_at")))
                }}
            }
            CommunityMembersResult.Listed(community,actor,items)
        }
    }
}

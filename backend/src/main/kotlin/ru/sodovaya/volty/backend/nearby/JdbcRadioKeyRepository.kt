package ru.sodovaya.volty.backend.nearby

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.sql.DataSource

/**
 * Per-server radio key. The epoch counts departures (left, removed, banned) of the community, so a
 * former member's cached key stops matching once the server's membership changes and a refresh is made.
 */
class JdbcRadioKeyRepository(private val source:DataSource,private val secret:ByteArray):RadioKeyEndpointPort {
    init {require(secret.size>=MIN_SECRET_BYTES) {"Radio secret is too short"}}
    override fun issue(actor:String,community:String):RadioKeyResult {
        listOf(actor,community).forEach {require(UUID.fromString(it).toString()==it)}
        return communityTransaction(source,write=false) {c ->
            val allowed=c.prepareStatement("""
                SELECT 1 FROM nearby_communities g
                JOIN nearby_community_members m ON m.community_id=g.id AND m.user_id=?::uuid
                WHERE g.id=?::uuid AND g.state='ACTIVE'
            """.trimIndent()).use {
                it.setString(1,actor);it.setString(2,community);it.executeQuery().use {rows -> rows.next()}
            }
            if(!allowed) return@communityTransaction RadioKeyResult.Denied
            val epoch=c.prepareStatement("""
                SELECT count(*) FROM nearby_community_outbox
                WHERE community_id=?::uuid AND kind IN ('MEMBER_LEFT','MEMBER_REMOVED','MEMBER_BANNED')
            """.trimIndent()).use {
                it.setString(1,community);it.executeQuery().use {rows -> check(rows.next());rows.getLong(1)}
            }
            RadioKeyResult.Issued(community,epoch,derive(community,epoch))
        }
    }
    private fun derive(community:String,epoch:Long):ByteArray=Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret,"HmacSHA256"))
        doFinal("volty-radio|$community|$epoch".toByteArray(Charsets.UTF_8))
    }
    companion object {const val MIN_SECRET_BYTES=32}
}

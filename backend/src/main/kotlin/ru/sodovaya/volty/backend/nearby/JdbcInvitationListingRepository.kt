package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

sealed interface InvitationListingResult {
    data class Listed(val items: List<InvitationSummary>) : InvitationListingResult
    data object Denied : InvitationListingResult
}
/** Current owner's metadata, including expired/revoked links. Lost secrets are unrecoverable. */
class JdbcInvitationListingRepository(private val source: DataSource) {
    fun list(actor: String, community: String, after: String? = null, limit: Int = 20): InvitationListingResult {
        invitationIds(actor, community); after?.let { invitationIds(it) }; require(limit in 1..50)
        return communityTransaction(source, write = false) { c ->
            if (!invitationOwner(c, actor, community)) return@communityTransaction InvitationListingResult.Denied
            val items = c.prepareStatement("""
                SELECT * FROM nearby_invitations WHERE community_id=?::uuid
                AND (?::uuid IS NULL OR id>?::uuid) ORDER BY id LIMIT ?
            """.trimIndent()).use {
                it.setString(1, community); it.setString(2, after); it.setString(3, after); it.setInt(4, limit)
                it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.invitationSummary()) } }
            }
            InvitationListingResult.Listed(items)
        }
    }
}

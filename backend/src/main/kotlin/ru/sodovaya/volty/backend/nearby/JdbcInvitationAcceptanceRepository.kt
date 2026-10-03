package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

/** Explicit membership only. Replaying a successful operation never rejoins after leave. */
class JdbcInvitationAcceptanceRepository(private val source: DataSource) {
    fun accept(actor: String, operation: String, rawToken: String, now: () -> Long): InvitationAcceptanceResult {
        invitationIds(actor, operation)
        val tokenHash = InvitationTokens.hash(rawToken)
        return communityTransaction(source, write = true) { c ->
            val active = c.prepareStatement("SELECT 1 FROM users WHERE id=?::uuid AND deleted_at IS NULL").use {
                it.setString(1, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!active) return@communityTransaction InvitationAcceptanceResult.Denied
            val prior = c.prepareStatement("SELECT * FROM nearby_invitation_acceptances WHERE actor_id=?::uuid AND operation_id=?::uuid").use {
                it.setString(1, actor); it.setString(2, operation); it.executeQuery().use { rows ->
                    if (!rows.next()) null else if (rows.getString("token_hash") != tokenHash) InvitationAcceptanceResult.Conflict
                    else InvitationAcceptanceResult.Accepted(InvitationAcceptanceReceipt(operation, rows.getString("invitation_id"), rows.getString("community_id"), rows.getLong("revision"), rows.getBoolean("membership_changed")))
                }
            }
            if (prior != null) return@communityTransaction prior
            val invitation = c.prepareStatement("""
                SELECT i.* FROM nearby_invitations i JOIN nearby_communities g ON g.id=i.community_id AND g.state='ACTIVE'
                WHERE i.token_hash=? AND i.revoked_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM nearby_community_bans b WHERE b.community_id=i.community_id AND b.user_id=?::uuid)
            """.trimIndent()).use {
                it.setString(1, tokenHash); it.setString(2, actor); it.executeQuery().use { rows -> if (rows.next()) rows.invitationSummary() else null }
            } ?: return@communityTransaction InvitationAcceptanceResult.Denied
            val at = now().also { require(it >= 0) }
            if (at >= invitation.expiresAtMillis) return@communityTransaction InvitationAcceptanceResult.Denied
            val member = c.prepareStatement("SELECT 1 FROM nearby_community_members WHERE community_id=?::uuid AND user_id=?::uuid").use {
                it.setString(1, invitation.communityId); it.setString(2, actor); it.executeQuery().use { rows -> rows.next() }
            }
            if (!member) {
                if (invitation.uses >= invitation.maxUses) return@communityTransaction InvitationAcceptanceResult.Denied
                c.prepareStatement("UPDATE nearby_invitations SET uses=uses+1 WHERE id=?::uuid").use {
                    it.setString(1, invitation.id); check(it.executeUpdate() == 1)
                }
                c.prepareStatement("INSERT INTO nearby_community_members(community_id,user_id,joined_at) VALUES (?::uuid,?::uuid,?)").use {
                    it.setString(1, invitation.communityId); it.setString(2, actor); it.setLong(3, at); it.executeUpdate()
                }
                appendCommunityChange(c, invitation.communityId, actor, "MEMBER_JOINED", subject = actor)
            }
            val revision = communityRevision(c)
            c.prepareStatement("""
                INSERT INTO nearby_invitation_acceptances(actor_id,operation_id,invitation_id,token_hash,community_id,revision,membership_changed)
                VALUES (?::uuid,?::uuid,?::uuid,?,?::uuid,?,?)
            """.trimIndent()).use {
                it.setString(1, actor); it.setString(2, operation); it.setString(3, invitation.id); it.setString(4, tokenHash)
                it.setString(5, invitation.communityId); it.setLong(6, revision); it.setBoolean(7, !member); it.executeUpdate()
            }
            InvitationAcceptanceResult.Accepted(InvitationAcceptanceReceipt(operation, invitation.id, invitation.communityId, revision, !member))
        }
    }
}

package ru.sodovaya.volty.backend.nearby

import javax.sql.DataSource

/** Owner mutations serialized with join/leave/ban/deletion by the access fence. */
class JdbcInvitationRepository(private val source: DataSource) {
    fun create(actor: String, community: String, invitation: String, rawToken: String, ttlMillis: Long, maxUses: Int, now: () -> Long): InvitationCreationResult {
        invitationIds(actor, community, invitation)
        require(ttlMillis in 900_000L..604_800_000L && maxUses in 1..1000)
        val tokenHash = InvitationTokens.hash(rawToken)
        val payloadHash = InvitationTokens.payloadHash(actor, community, invitation, tokenHash, ttlMillis.toString(), maxUses.toString())
        return communityTransaction(source, write = true) { c ->
            if (!invitationOwner(c, actor, community)) return@communityTransaction InvitationCreationResult.Denied
            val prior = c.prepareStatement("SELECT * FROM nearby_invitations WHERE id=?::uuid OR token_hash=?").use {
                it.setString(1, invitation); it.setString(2, tokenHash); it.executeQuery().use { rows ->
                    if (!rows.next()) null else {
                        val summary = rows.invitationSummary()
                        val matches = summary.id == invitation && summary.communityId == community && rows.getString("created_by") == actor &&
                            rows.getString("token_hash") == tokenHash && rows.getString("payload_hash") == payloadHash && summary.revokedAtMillis == null
                        if (matches && !rows.next()) InvitationCreationResult.Created(summary) else InvitationCreationResult.Conflict
                    }
                }
            }
            if (prior != null) return@communityTransaction prior
            val at = now().also { require(it >= 0) }; val expires = Math.addExact(at, ttlMillis)
            appendCommunityChange(c, community, actor, "INVITE_CHANGED")
            val revision = communityRevision(c)
            c.prepareStatement("""
                INSERT INTO nearby_invitations(id,community_id,created_by,token_hash,payload_hash,created_at,expires_at,max_uses,creation_revision)
                VALUES (?::uuid,?::uuid,?::uuid,?,?,?,?,?,?)
            """.trimIndent()).use {
                it.setString(1, invitation); it.setString(2, community); it.setString(3, actor); it.setString(4, tokenHash); it.setString(5, payloadHash)
                it.setLong(6, at); it.setLong(7, expires); it.setInt(8, maxUses); it.setLong(9, revision); it.executeUpdate()
            }
            InvitationCreationResult.Created(InvitationSummary(invitation, community, at, expires, maxUses, 0, null, revision, null))
        }
    }

    fun revoke(actor: String, community: String, invitation: String, now: () -> Long): InvitationRevocationResult {
        invitationIds(actor, community, invitation)
        return communityTransaction(source, write = true) { c ->
            if (!invitationOwner(c, actor, community)) return@communityTransaction InvitationRevocationResult.Denied
            val summary = c.prepareStatement("SELECT * FROM nearby_invitations WHERE id=?::uuid AND community_id=?::uuid").use {
                it.setString(1, invitation); it.setString(2, community); it.executeQuery().use { rows -> if (rows.next()) rows.invitationSummary() else null }
            } ?: return@communityTransaction InvitationRevocationResult.Denied
            if (summary.revokedAtMillis != null) return@communityTransaction InvitationRevocationResult.Revoked(invitation, community, checkNotNull(summary.revocationRevision))
            val at = now().also { require(it >= 0) }
            appendCommunityChange(c, community, actor, "INVITE_CHANGED")
            val revision = communityRevision(c)
            c.prepareStatement("UPDATE nearby_invitations SET revoked_at=?,revocation_revision=? WHERE id=?::uuid").use {
                it.setLong(1, at); it.setLong(2, revision); it.setString(3, invitation); check(it.executeUpdate() == 1)
            }
            InvitationRevocationResult.Revoked(invitation, community, revision)
        }
    }
}

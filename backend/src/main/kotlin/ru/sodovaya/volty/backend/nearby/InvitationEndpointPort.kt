package ru.sodovaya.volty.backend.nearby

interface InvitationEndpointPort {
    fun list(actor: String, community: String, after: String?, limit: Int): InvitationListingResult
    fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationResult
    fun revoke(actor: String, community: String, invitation: String): InvitationRevocationResult
    fun accept(actor: String, operation: String, token: String): InvitationAcceptanceResult
}
class JdbcInvitationEndpointPort(
    private val invitations: JdbcInvitationRepository,
    private val acceptance: JdbcInvitationAcceptanceRepository,
    private val listing: JdbcInvitationListingRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : InvitationEndpointPort {
    override fun list(actor: String, community: String, after: String?, limit: Int) = listing.list(actor, community, after, limit)
    override fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int) =
        invitations.create(actor, community, invitation, token, ttlMillis, maxUses, now)
    override fun revoke(actor: String, community: String, invitation: String) = invitations.revoke(actor, community, invitation, now)
    override fun accept(actor: String, operation: String, token: String) = acceptance.accept(actor, operation, token, now)
}

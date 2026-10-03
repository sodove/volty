package ru.sodovaya.volty.backend.nearby

interface InvitationEndpointPort {
    fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationResult
    fun revoke(actor: String, community: String, invitation: String): InvitationRevocationResult
    fun accept(actor: String, operation: String, token: String): InvitationAcceptanceResult
}
class JdbcInvitationEndpointPort(
    private val invitations: JdbcInvitationRepository,
    private val acceptance: JdbcInvitationAcceptanceRepository,
    private val now: () -> Long = System::currentTimeMillis,
) : InvitationEndpointPort {
    override fun create(actor: String, community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int) =
        invitations.create(actor, community, invitation, token, ttlMillis, maxUses, now)
    override fun revoke(actor: String, community: String, invitation: String) = invitations.revoke(actor, community, invitation, now)
    override fun accept(actor: String, operation: String, token: String) = acceptance.accept(actor, operation, token, now)
}

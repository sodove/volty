package ru.sodovaya.volty.domain.nearby.community

data class InvitationMetadata(val invitationId: String, val communityId: String, val createdAtMillis: Long,
    val expiresAtMillis: Long, val maxUses: Int, val uses: Int, val revokedAtMillis: Long?,
    val creationRevision: Long, val revocationRevision: Long?)
sealed interface InvitationListingCommandResult {
    data class Listed(val items: List<InvitationMetadata>) : InvitationListingCommandResult
    data class Failed(val reason: InvitationCommandFailure) : InvitationListingCommandResult
}
/** Current owner metadata only. Never recovers/regenerates a secret or grants membership. */
interface InvitationListingPort {
    suspend fun list(community: String, after: String? = null, limit: Int = 20): InvitationListingCommandResult
}

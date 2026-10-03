package ru.sodovaya.volty.backend.nearby

data class InvitationSummary(
    val id: String, val communityId: String, val createdAtMillis: Long, val expiresAtMillis: Long,
    val maxUses: Int, val uses: Int, val revokedAtMillis: Long?, val creationRevision: Long, val revocationRevision: Long?,
)
sealed interface InvitationCreationResult {
    data class Created(val invitation: InvitationSummary) : InvitationCreationResult
    data object Denied : InvitationCreationResult
    data object Conflict : InvitationCreationResult
}
sealed interface InvitationRevocationResult {
    data class Revoked(val invitationId: String, val communityId: String, val revision: Long) : InvitationRevocationResult
    data object Denied : InvitationRevocationResult
}
/** Historical operation receipt; does not assert that the actor is still a member. */
data class InvitationAcceptanceReceipt(
    val operationId: String, val invitationId: String, val communityId: String, val revision: Long, val membershipChanged: Boolean,
)
sealed interface InvitationAcceptanceResult {
    data class Accepted(val receipt: InvitationAcceptanceReceipt) : InvitationAcceptanceResult
    data object Denied : InvitationAcceptanceResult
    data object Conflict : InvitationAcceptanceResult
}

package ru.sodovaya.volty.domain.nearby.community

data class InvitationCreationReceipt(val invitationId: String, val communityId: String, val createdAtMillis: Long,
    val expiresAtMillis: Long, val maxUses: Int, val uses: Int, val creationRevision: Long)
data class InvitationRevocationReceipt(val invitationId: String, val communityId: String, val revision: Long)
/** Historical request result; requires current membership refresh before rendering joined state. */
data class InvitationAcceptanceReceipt(val operationId: String, val invitationId: String, val communityId: String,
    val revision: Long, val membershipChanged: Boolean)
enum class InvitationCommandFailure { UNAUTHORIZED, ACCESS_DENIED, CONFLICT, UNAVAILABLE, INVALID_RESPONSE }
sealed interface InvitationCreationCommandResult {
    data class Created(val receipt: InvitationCreationReceipt) : InvitationCreationCommandResult
    data class Failed(val reason: InvitationCommandFailure) : InvitationCreationCommandResult
}
sealed interface InvitationRevocationCommandResult {
    data class Revoked(val receipt: InvitationRevocationReceipt) : InvitationRevocationCommandResult
    data class Failed(val reason: InvitationCommandFailure) : InvitationRevocationCommandResult
}
sealed interface InvitationAcceptanceCommandResult {
    data class Accepted(val receipt: InvitationAcceptanceReceipt) : InvitationAcceptanceCommandResult
    data class Failed(val reason: InvitationCommandFailure) : InvitationAcceptanceCommandResult
}
/** Caller retains secret and stable request IDs/payload; never implicit voice or sharing consent. */
interface InvitationPort {
    suspend fun create(community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationCommandResult
    suspend fun revoke(community: String, invitation: String): InvitationRevocationCommandResult
    suspend fun accept(operation: String, token: String): InvitationAcceptanceCommandResult
}

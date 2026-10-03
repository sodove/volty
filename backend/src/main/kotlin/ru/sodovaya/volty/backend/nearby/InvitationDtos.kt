package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable

@Serializable data class InvitationCreateRequest(val token: String, val ttlMillis: Long, val maxUses: Int)
@Serializable data class InvitationAcceptRequest(val operationId: String, val token: String)
@Serializable data class InvitationMetadataResponse(
    val invitationId: String, val communityId: String, val createdAtMillis: Long, val expiresAtMillis: Long,
    val maxUses: Int, val uses: Int, val revokedAtMillis: Long?, val creationRevision: Long, val revocationRevision: Long?,
)
@Serializable data class InvitationRevocationResponse(val invitationId: String, val communityId: String, val revision: Long)
@Serializable data class InvitationListResponse(val items: List<InvitationMetadataResponse>)
/** Historical receipt, not a current membership/voice/location grant. */
@Serializable data class InvitationAcceptanceResponse(val operationId: String, val invitationId: String, val communityId: String,
                                                      val revision: Long, val membershipChanged: Boolean)
internal fun InvitationSummary.dto() = InvitationMetadataResponse(id, communityId, createdAtMillis, expiresAtMillis,
    maxUses, uses, revokedAtMillis, creationRevision, revocationRevision)

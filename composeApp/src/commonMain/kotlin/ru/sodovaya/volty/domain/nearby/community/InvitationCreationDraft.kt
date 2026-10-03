package ru.sodovaya.volty.domain.nearby.community

data class InvitationCreationDraft(
    val actorId: String, val invitationId: String, val communityId: String,
    val ttlMillis: Long, val maxUses: Int, val secret: InvitationSecret,
) {
    init { invitationDraftId(actorId); invitationDraftInput(invitationId, communityId, ttlMillis, maxUses) }
}

/**
 * Immutable account binding. Encrypt secret at rest. Retain is atomic insert-or-read;
 * never overwrites existing payload, and returns only after its durable commit succeeds.
 */
interface InvitationDraftStore {
    val actorId: String
    suspend fun read(invitationId: String): InvitationCreationDraft?
    suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft
}

enum class InvitationDraftFailure {
    STORAGE_UNAVAILABLE, SECRET_UNAVAILABLE, MISSING_DRAFT, PAYLOAD_CONFLICT,
    UNAUTHORIZED, ACCESS_DENIED, CONFLICT, UNAVAILABLE, INVALID_RESPONSE,
}
sealed interface InvitationDraftPreparation {
    data class Prepared(val draft: InvitationCreationDraft) : InvitationDraftPreparation
    data class Failed(val reason: InvitationDraftFailure) : InvitationDraftPreparation
}
sealed interface InvitationDraftExecution {
    /** Request receipt only. No current membership, voice or sharing permission follows. */
    data class Created(val draft: InvitationCreationDraft, val receipt: InvitationCreationReceipt) : InvitationDraftExecution
    data class Failed(val reason: InvitationDraftFailure) : InvitationDraftExecution
}

internal fun invitationDraftInput(invitation: String, community: String, ttl: Long, uses: Int) {
    invitationDraftId(invitation); invitationDraftId(community)
    require(ttl in 900_000L..604_800_000L && uses in 1..1_000)
}
internal fun invitationDraftId(value: String) {
    require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value))
}

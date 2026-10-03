package ru.sodovaya.volty.domain.nearby.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable caller-owned creation payload. Port principal must be bound to this same actor. */
class InvitationCreationDraftOwner(
    private val actor: String,
    private val store: InvitationDraftStore,
    private val generator: InvitationSecretGenerator,
    private val port: InvitationPort,
) {
    init { invitationDraftId(actor); require(store.actorId == actor) }
    private val preparation = Mutex()

    suspend fun prepare(invitation: String, community: String, ttlMillis: Long, maxUses: Int): InvitationDraftPreparation {
        invitationDraftInput(invitation, community, ttlMillis, maxUses)
        return preparation.withLock {
            try {
                store.read(invitation)?.let { return@withLock prepared(it, invitation, community, ttlMillis, maxUses) }
                val secret = try { generator.generate() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@withLock InvitationDraftPreparation.Failed(InvitationDraftFailure.SECRET_UNAVAILABLE) }
                val candidate = InvitationCreationDraft(actor, invitation, community, ttlMillis, maxUses, secret)
                // Another owner may have won the store's atomic retain; always use its payload.
                prepared(store.retain(candidate), invitation, community, ttlMillis, maxUses)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { InvitationDraftPreparation.Failed(InvitationDraftFailure.STORAGE_UNAVAILABLE) }
        }
    }

    suspend fun execute(invitation: String): InvitationDraftExecution {
        invitationDraftId(invitation)
        val draft = try { preparation.withLock { store.read(invitation) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return InvitationDraftExecution.Failed(InvitationDraftFailure.STORAGE_UNAVAILABLE) }
            ?: return InvitationDraftExecution.Failed(InvitationDraftFailure.MISSING_DRAFT)
        if (draft.actorId != actor || draft.invitationId != invitation)
            return InvitationDraftExecution.Failed(InvitationDraftFailure.INVALID_RESPONSE)
        val result = try { port.create(draft.communityId, draft.invitationId, draft.secret.reveal(), draft.ttlMillis, draft.maxUses) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return InvitationDraftExecution.Failed(InvitationDraftFailure.UNAVAILABLE) }
        return when (result) {
            is InvitationCreationCommandResult.Created -> if (matches(result.receipt, draft))
                InvitationDraftExecution.Created(draft, result.receipt)
            else InvitationDraftExecution.Failed(InvitationDraftFailure.INVALID_RESPONSE)
            is InvitationCreationCommandResult.Failed -> InvitationDraftExecution.Failed(when (result.reason) {
                InvitationCommandFailure.UNAUTHORIZED -> InvitationDraftFailure.UNAUTHORIZED
                InvitationCommandFailure.ACCESS_DENIED -> InvitationDraftFailure.ACCESS_DENIED
                InvitationCommandFailure.CONFLICT -> InvitationDraftFailure.CONFLICT
                InvitationCommandFailure.UNAVAILABLE -> InvitationDraftFailure.UNAVAILABLE
                InvitationCommandFailure.INVALID_RESPONSE -> InvitationDraftFailure.INVALID_RESPONSE
            })
        }
    }

    private fun prepared(draft: InvitationCreationDraft, invitation: String, community: String, ttl: Long, uses: Int): InvitationDraftPreparation = when {
        draft.actorId != actor || draft.invitationId != invitation -> InvitationDraftPreparation.Failed(InvitationDraftFailure.INVALID_RESPONSE)
        draft.communityId != community || draft.ttlMillis != ttl || draft.maxUses != uses -> InvitationDraftPreparation.Failed(InvitationDraftFailure.PAYLOAD_CONFLICT)
        else -> InvitationDraftPreparation.Prepared(draft)
    }

    private fun matches(receipt: InvitationCreationReceipt, draft: InvitationCreationDraft): Boolean =
        receipt.invitationId == draft.invitationId && receipt.communityId == draft.communityId &&
            receipt.createdAtMillis >= 0 && receipt.expiresAtMillis >= receipt.createdAtMillis &&
            receipt.expiresAtMillis - receipt.createdAtMillis == draft.ttlMillis &&
            receipt.maxUses == draft.maxUses && receipt.uses in 0..receipt.maxUses && receipt.creationRevision > 0
}

package ru.sodovaya.volty.domain.nearby.community

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable caller-owned creation payload. Port principal must be bound to this same actor. */
class InvitationCreationDraftOwner(
    private val actor: String,
    private val store: InvitationDraftStore,
    private val generator: InvitationSecretGenerator,
    private val port: InvitationPort,
    private val accountJob: Job? = null,
) {
    init { invitationDraftId(actor); require(store.actorId == actor) }
    private val preparation = Mutex()
    private val retired = MutableStateFlow(false)

    /** Ends this owner's lifetime immediately; already retained drafts remain available to a new owner. */
    fun retire() {
        retired.value = true
    }

    suspend fun prepare(invitation: String, community: String, ttlMillis: Long, maxUses: Int): InvitationDraftPreparation {
        invitationDraftInput(invitation, community, ttlMillis, maxUses)
        ensureOwnerActive()
        return preparation.withLock {
            ensureOwnerActive()
            try {
                val existing = store.read(invitation)
                ensureOwnerActive()
                existing?.let {
                    return@withLock publishPreparation(prepared(it, invitation, community, ttlMillis, maxUses))
                }
                ensureOwnerActive()
                val secret = try { generator.generate() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    return@withLock publishPreparation(
                        InvitationDraftPreparation.Failed(InvitationDraftFailure.SECRET_UNAVAILABLE),
                    )
                }
                ensureOwnerActive()
                val candidate = InvitationCreationDraft(actor, invitation, community, ttlMillis, maxUses, secret)
                // Another owner may have won the store's atomic retain; always use its payload.
                ensureOwnerActive()
                val retained = store.retain(candidate)
                ensureOwnerActive()
                publishPreparation(prepared(retained, invitation, community, ttlMillis, maxUses))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publishPreparation(InvitationDraftPreparation.Failed(InvitationDraftFailure.STORAGE_UNAVAILABLE))
            }
        }
    }

    suspend fun execute(invitation: String): InvitationDraftExecution {
        invitationDraftId(invitation)
        ensureOwnerActive()
        val draft = try {
            preparation.withLock {
                ensureOwnerActive()
                val stored = store.read(invitation)
                ensureOwnerActive()
                stored
            }
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return publishExecution(InvitationDraftExecution.Failed(InvitationDraftFailure.STORAGE_UNAVAILABLE))
        } ?: return publishExecution(InvitationDraftExecution.Failed(InvitationDraftFailure.MISSING_DRAFT))
        if (draft.actorId != actor || draft.invitationId != invitation)
            return publishExecution(InvitationDraftExecution.Failed(InvitationDraftFailure.INVALID_RESPONSE))
        ensureOwnerActive()
        val result = try { port.create(draft.communityId, draft.invitationId, draft.secret.reveal(), draft.ttlMillis, draft.maxUses) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return publishExecution(InvitationDraftExecution.Failed(InvitationDraftFailure.UNAVAILABLE))
        }
        ensureOwnerActive()
        return publishExecution(when (result) {
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
        })
    }

    private suspend fun ensureOwnerActive() {
        currentCoroutineContext().ensureActive()
        if (accountJob?.isActive == false) retired.value = true
        if (retired.value) throw CancellationException("Invitation creation owner retired")
    }

    private suspend fun publishPreparation(result: InvitationDraftPreparation): InvitationDraftPreparation {
        ensureOwnerActive()
        return result
    }

    private suspend fun publishExecution(result: InvitationDraftExecution): InvitationDraftExecution {
        ensureOwnerActive()
        return result
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

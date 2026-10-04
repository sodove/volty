package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.CancellationException
import ru.sodovaya.volty.domain.nearby.community.*

interface InvitationDraftCipher {
    fun encrypt(draft: InvitationCreationDraft): String
    fun decrypt(actor: String, invitation: String, encrypted: String): InvitationCreationDraft
}

interface InvitationDraftRecordTransaction {
    fun peek(invitation: String): String?
    /** Acknowledged persistence only; failure may still have changed a memory cache. */
    fun write(invitation: String, encrypted: String): Boolean
    /** Force and acknowledge persistence of ALL cached records before returning an existing one. */
    fun confirm(): Boolean
}

/** Same lock across instances for the bound account; block and transaction never escape it. */
interface InvitationDraftRecords {
    val actorId: String
    fun <T> locked(block: (InvitationDraftRecordTransaction) -> T): T
}

/** Native adapter supplies persistence acknowledgement, not merely cached read-your-writes. */
class EncryptedInvitationDraftStore(
    override val actorId: String,
    private val records: InvitationDraftRecords,
    private val cipher: InvitationDraftCipher,
) : InvitationDraftStore {
    init { invitationDraftId(actorId); require(records.actorId == actorId) }

    override suspend fun read(invitationId: String): InvitationCreationDraft? {
        invitationDraftId(invitationId)
        return safely {
            records.locked { transaction ->
                transaction.peek(invitationId)?.let { encrypted ->
                    val draft = decode(invitationId, encrypted)
                    check(transaction.confirm())
                    draft
                }
            }
        }
    }

    override suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft {
        require(draft.actorId == actorId)
        return safely {
            records.locked { transaction ->
                val existing = transaction.peek(draft.invitationId)
                if (existing != null) {
                    val winner = decode(draft.invitationId, existing)
                    check(transaction.confirm())
                    winner
                } else {
                    val encrypted = cipher.encrypt(draft)
                    // Incoherent encryption providers cannot acknowledge a different payload.
                    val restored = decode(draft.invitationId, encrypted)
                    check(restored.communityId == draft.communityId && restored.ttlMillis == draft.ttlMillis &&
                        restored.maxUses == draft.maxUses && restored.secret.reveal() == draft.secret.reveal())
                    check(transaction.write(draft.invitationId, encrypted))
                    draft
                }
            }
        }
    }

    private fun decode(invitation: String, encrypted: String): InvitationCreationDraft {
        require(encrypted.isNotEmpty() && encrypted.length <= 4_096)
        return cipher.decrypt(actorId, invitation, encrypted).also {
            require(it.actorId == actorId && it.invitationId == invitation)
        }
    }

    private inline fun <T> safely(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw IllegalStateException("Invitation draft unavailable") }
}

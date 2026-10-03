package ru.sodovaya.volty.domain.nearby.community

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class InvitationCreationDraftOwnerTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val community = "20000000-0000-0000-0000-000000000002"
    private val invitation = "30000000-0000-0000-0000-000000000003"
    private val token = "A".repeat(43)
    private val ttl = 900_000L
    private val receipt = InvitationCreationReceipt(invitation, community, 100, 900_100, 4, 0, 1)

    private inner class Store : InvitationDraftStore {
        override val actorId = actor
        var saved: InvitationCreationDraft? = null
        var writes = 0
        var failWrite = false
        var winner: InvitationCreationDraft? = null
        override suspend fun read(invitationId: String) = saved
        override suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft {
            writes++
            if (failWrite) error("private-token")
            return saved ?: (winner ?: draft).also { saved = it }
        }
    }
    private inner class Port(private val store: Store) : InvitationPort {
        val issued = mutableListOf<List<Any>>()
        var reply: InvitationCreationCommandResult = InvitationCreationCommandResult.Created(receipt)
        override suspend fun create(community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationCommandResult {
            assertNotNull(store.saved, "Persist before issuing command")
            issued += listOf(community, invitation, token, ttlMillis, maxUses)
            return reply
        }
        override suspend fun revoke(community: String, invitation: String): InvitationRevocationCommandResult = error("No revoke")
        override suspend fun accept(operation: String, token: String): InvitationAcceptanceCommandResult = error("No accept")
    }
    private fun draft(owner: String = actor, secret: String = token) = InvitationCreationDraft(owner, invitation, community,
        ttl, 4, InvitationSecret.fromToken(secret))

    @Test fun storedDraftPrecedesCommandAndAmbiguousRetryReusesActualPayload() = runTest {
        val store = Store(); val port = Port(store)
        var generated = 0
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator {
            generated++; InvitationSecret.fromToken(token)
        }, port)
        assertIs<InvitationDraftPreparation.Prepared>(owner.prepare(invitation, community, ttl, 4))
        assertTrue(port.issued.isEmpty())
        port.reply = InvitationCreationCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE)
        assertIs<InvitationDraftExecution.Failed>(owner.execute(invitation))
        port.reply = InvitationCreationCommandResult.Created(receipt)
        val result = assertIs<InvitationDraftExecution.Created>(owner.execute(invitation))
        assertEquals(receipt, result.receipt)
        assertEquals(2, port.issued.size)
        assertEquals(port.issued[0], port.issued[1])
        assertEquals(token, result.draft.secret.reveal())
        assertEquals(1, generated)
        assertEquals(1, store.writes)
        assertFalse(result.toString().contains(token))
    }

    @Test fun storageFailureNeverIssuesHttpAndDoesNotEchoFailureText() = runTest {
        val store = Store().also { it.failWrite = true }; val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { InvitationSecret.fromToken(token) }, port)
        val result = assertIs<InvitationDraftPreparation.Failed>(owner.prepare(invitation, community, ttl, 4))
        assertEquals(InvitationDraftFailure.STORAGE_UNAVAILABLE, result.reason)
        assertFalse(result.toString().contains("private-token"))
        assertEquals(InvitationDraftFailure.MISSING_DRAFT, assertIs<InvitationDraftExecution.Failed>(owner.execute(invitation)).reason)
        assertTrue(port.issued.isEmpty())
    }

    @Test fun recreatedOwnerReadsRetainedSecretAndChangedPayloadCannotRegenerateIt() = runTest {
        val store = Store().also { it.saved = draft() }; val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { error("No new secret") }, port)
        val ready = assertIs<InvitationDraftPreparation.Prepared>(owner.prepare(invitation, community, ttl, 4))
        assertEquals(token, ready.draft.secret.reveal())
        assertEquals(InvitationDraftFailure.PAYLOAD_CONFLICT,
            assertIs<InvitationDraftPreparation.Failed>(owner.prepare(invitation, community, ttl, 5)).reason)
        assertEquals(0, store.writes)
        assertTrue(port.issued.isEmpty())
    }

    @Test fun foreignStoredRecordCannotLeakSecretOrIssueACommand() = runTest {
        val store = Store().also { it.saved = draft("40000000-0000-0000-0000-000000000004") }; val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { error("No secret") }, port)
        assertEquals(InvitationDraftFailure.INVALID_RESPONSE,
            assertIs<InvitationDraftPreparation.Failed>(owner.prepare(invitation, community, ttl, 4)).reason)
        assertEquals(InvitationDraftFailure.INVALID_RESPONSE,
            assertIs<InvitationDraftExecution.Failed>(owner.execute(invitation)).reason)
        assertTrue(port.issued.isEmpty())
    }

    @Test fun contradictoryReceiptCannotBePresentedAsCreatedAndDraftRemainsRetryable() = runTest {
        val store = Store().also { it.saved = draft() }; val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { error("No secret") }, port)
        for (wrong in listOf(receipt.copy(communityId = actor), receipt.copy(invitationId = actor),
            receipt.copy(maxUses = 5), receipt.copy(expiresAtMillis = 900_101), receipt.copy(uses = 5),
            receipt.copy(creationRevision = 0), receipt.copy(createdAtMillis = -1))) {
            port.reply = InvitationCreationCommandResult.Created(wrong)
            assertEquals(InvitationDraftFailure.INVALID_RESPONSE,
                assertIs<InvitationDraftExecution.Failed>(owner.execute(invitation)).reason)
        }
        assertEquals(7, port.issued.size)
        assertNotNull(store.saved)
    }

    @Test fun atomicRetainWinnerOwnsSecretInsteadOfTheDiscardedNewCandidate() = runTest {
        val winnerToken = "A".repeat(42) + "E"
        val store = Store().also { it.winner = draft(secret = winnerToken) }; val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { InvitationSecret.fromToken(token) }, port)
        val result = assertIs<InvitationDraftPreparation.Prepared>(owner.prepare(invitation, community, ttl, 4))
        assertEquals(winnerToken, result.draft.secret.reveal())
        owner.execute(invitation)
        assertEquals(winnerToken, port.issued.single()[2])
    }

    @Test fun invalidInputsAndForeignStoreBindingAreRejectedBeforeAnySecretOrCommand() = runTest {
        val store = Store(); val port = Port(store)
        val generator = InvitationSecretGenerator { error("No secret") }
        assertFailsWith<IllegalArgumentException> { InvitationCreationDraftOwner(community, store, generator, port) }
        val owner = InvitationCreationDraftOwner(actor, store, generator, port)
        assertFailsWith<IllegalArgumentException> { owner.prepare("bad", community, ttl, 4) }
        assertFailsWith<IllegalArgumentException> { owner.prepare(invitation, community, ttl - 1, 4) }
        assertFailsWith<IllegalArgumentException> { owner.prepare(invitation, community, ttl, 0) }
        assertTrue(port.issued.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test fun cancellationAfterRetainCommitKeepsTheOriginalDraftForExplicitRetry() = runTest {
        val saved = Store(); val port = Port(saved)
        var cancelOnce = true
        var generated = 0
        val store = object : InvitationDraftStore {
            override val actorId = actor
            override suspend fun read(invitationId: String) = saved.read(invitationId)
            override suspend fun retain(draft: InvitationCreationDraft): InvitationCreationDraft {
                val result = saved.retain(draft)
                if (cancelOnce) { cancelOnce = false; throw CancellationException("cancelled after commit") }
                return result
            }
        }
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator {
            generated++; InvitationSecret.fromToken(token)
        }, port)
        assertFailsWith<CancellationException> { owner.prepare(invitation, community, ttl, 4) }
        assertNotNull(saved.saved)
        assertTrue(port.issued.isEmpty())
        assertIs<InvitationDraftPreparation.Prepared>(owner.prepare(invitation, community, ttl, 4))
        assertIs<InvitationDraftExecution.Created>(owner.execute(invitation))
        assertEquals(1, generated)
        assertEquals(1, saved.writes)
        assertEquals(token, port.issued.single()[2])
    }

    @Test fun failedEntropyCannotPersistOrIssueAnInvitationAndHasSafeTypedResult() = runTest {
        val store = Store(); val port = Port(store)
        val owner = InvitationCreationDraftOwner(actor, store,
            InvitationSecretGenerator { error("private-entropy-details") }, port)
        val result = assertIs<InvitationDraftPreparation.Failed>(owner.prepare(invitation, community, ttl, 4))
        assertEquals(InvitationDraftFailure.SECRET_UNAVAILABLE, result.reason)
        assertFalse(result.toString().contains("private-entropy-details"))
        assertEquals(0, store.writes)
        assertNull(store.saved)
        assertTrue(port.issued.isEmpty())
    }
}

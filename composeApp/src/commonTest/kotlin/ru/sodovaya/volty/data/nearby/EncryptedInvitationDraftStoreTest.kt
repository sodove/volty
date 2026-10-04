package ru.sodovaya.volty.data.nearby

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

class EncryptedInvitationDraftStoreTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val invitation = "20000000-0000-0000-0000-000000000002"
    private val community = "30000000-0000-0000-0000-000000000003"
    private val token = "A".repeat(43)
    private fun draft(owner: String = actor, secret: String = token) = InvitationCreationDraft(owner, invitation, community,
        900_000, 4, InvitationSecret.fromToken(secret))
    private inner class Cipher : InvitationDraftCipher {
        val values = mutableMapOf<String, InvitationCreationDraft>()
        var encrypted = 0
        var wrong: InvitationCreationDraft? = null
        override fun encrypt(draft: InvitationCreationDraft): String = "cipher-${++encrypted}".also { values[it] = wrong ?: draft }
        // Deliberately unbound provider fixture: the store must validate returned identity itself.
        override fun decrypt(actor: String, invitation: String, encrypted: String) = values[encrypted] ?: error("private-value")
    }
    private inner class Records(override val actorId: String = actor) : InvitationDraftRecords, InvitationDraftRecordTransaction {
        val cache = mutableMapOf<String, String>()
        val disk = mutableMapOf<String, String>()
        var writeOk = true
        var confirmOk = true
        var throwAfterWrite = false
        var writes = 0
        var confirmations = 0
        override fun <T> locked(block: (InvitationDraftRecordTransaction) -> T): T = synchronized(this) { block(this) }
        override fun peek(invitation: String): String? = cache[invitation]
        override fun write(invitation: String, encrypted: String): Boolean {
            writes++; cache[invitation] = encrypted
            if (writeOk) { disk.clear(); disk.putAll(cache) }
            if (throwAfterWrite) { throwAfterWrite = false; throw CancellationException("after commit") }
            return writeOk
        }
        override fun confirm(): Boolean {
            confirmations++
            if (confirmOk) { disk.clear(); disk.putAll(cache) }
            return confirmOk
        }
    }

    @Test fun encryptedRetainCommitsBeforeReturnAndExistingWinnerIsNeverOverwritten() = runTest {
        val records = Records(); val cipher = Cipher()
        val first = EncryptedInvitationDraftStore(actor, records, cipher)
        first.retain(draft())
        assertEquals("cipher-1", records.disk[invitation])
        assertFalse(records.disk.toString().contains(token))
        val second = EncryptedInvitationDraftStore(actor, records, cipher)
        val winner = second.retain(draft(secret = "A".repeat(42) + "E"))
        assertEquals(token, winner.secret.reveal())
        assertEquals(1, records.writes)
        assertEquals(1, cipher.encrypted)
        assertEquals(1, records.confirmations)
    }

    @Test fun failedWriteCacheCannotIssueHttpUntilIndependentDurableConfirmationSucceeds() = runTest {
        val records = Records().also { it.writeOk = false; it.confirmOk = false }; val cipher = Cipher()
        val store = EncryptedInvitationDraftStore(actor, records, cipher)
        assertFailsWith<IllegalStateException> { store.retain(draft()) }
        assertNotNull(records.cache[invitation])
        assertNull(records.disk[invitation])
        var issued = 0
        val port = object : InvitationPort {
            override suspend fun create(community: String, invitation: String, token: String, ttlMillis: Long, maxUses: Int): InvitationCreationCommandResult {
                issued++
                assertEquals(records.cache, records.disk, "HTTP requires a confirmed retained record")
                assertEquals(this@EncryptedInvitationDraftStoreTest.token, token)
                return InvitationCreationCommandResult.Created(InvitationCreationReceipt(invitation, community, 0, ttlMillis, maxUses, 0, 1))
            }
            override suspend fun accept(operation: String, token: String): InvitationAcceptanceCommandResult = error("No accept")
            override suspend fun revoke(community: String, invitation: String): InvitationRevocationCommandResult = error("No revoke")
        }
        val owner = InvitationCreationDraftOwner(actor, store, InvitationSecretGenerator { error("No regeneration") }, port)
        assertEquals(InvitationDraftFailure.STORAGE_UNAVAILABLE, assertIs<InvitationDraftExecution.Failed>(owner.execute(invitation)).reason)
        assertEquals(0, issued)
        records.confirmOk = true
        assertIs<InvitationDraftExecution.Created>(owner.execute(invitation))
        assertEquals(1, issued)
        assertEquals(2, records.confirmations)
        assertEquals(1, records.writes)
    }

    @Test fun eachReadRequiresConfirmationEvenWhenTheCachedCiphertextHasNotChanged() = runTest {
        val records = Records(); val cipher = Cipher(); val store = EncryptedInvitationDraftStore(actor, records, cipher)
        store.retain(draft())
        assertNotNull(store.read(invitation))
        records.confirmOk = false
        assertFailsWith<IllegalStateException> { store.read(invitation) }
        assertEquals(2, records.confirmations)
        assertEquals(1, records.writes)
        assertNotNull(records.cache[invitation])
    }

    @Test fun foreignProviderIdentityAndIncoherentNewRoundtripCannotEarnRetention() = runTest {
        val records = Records(); val cipher = Cipher().also { it.wrong = draft(community) }
        val store = EncryptedInvitationDraftStore(actor, records, cipher)
        assertFailsWith<IllegalStateException> { store.retain(draft()) }
        assertTrue(records.cache.isEmpty())
        assertEquals(0, records.writes)
        records.cache[invitation] = "cipher-1"
        assertFailsWith<IllegalStateException> { store.read(invitation) }
        assertEquals(0, records.confirmations)
        assertEquals("cipher-1", records.cache[invitation])
    }

    @Test fun corruptRecordIsNotAbsenceAndErrorsNeverEchoProviderTextOrSecret() = runTest {
        val records = Records().also { it.cache[invitation] = "broken" }
        val store = EncryptedInvitationDraftStore(actor, records, Cipher())
        val failure = assertFailsWith<IllegalStateException> { store.read(invitation) }
        assertFalse(failure.toString().contains("private-value"))
        assertNull(failure.cause)
        assertEquals("broken", records.cache[invitation])
        assertEquals(0, records.confirmations)
    }

    @Test fun accountBindingAndForeignRetainAreRejectedBeforeRecordsOrEncryption() = runTest {
        val records = Records(); val cipher = Cipher()
        assertFailsWith<IllegalArgumentException> { EncryptedInvitationDraftStore(community, records, cipher) }
        val store = EncryptedInvitationDraftStore(actor, records, cipher)
        assertFailsWith<IllegalArgumentException> { store.retain(draft(community)) }
        assertFailsWith<IllegalArgumentException> { store.read("bad") }
        assertEquals(0, cipher.encrypted)
        assertEquals(0, records.writes)
    }

    @Test fun cancellationAfterRecordCommitPropagatesWithoutErasingRetainedSecret() = runTest {
        val records = Records().also { it.throwAfterWrite = true }; val cipher = Cipher()
        val store = EncryptedInvitationDraftStore(actor, records, cipher)
        assertFailsWith<CancellationException> { store.retain(draft()) }
        assertEquals("cipher-1", records.disk[invitation])
        assertEquals(token, store.read(invitation)!!.secret.reveal())
        assertEquals(1, records.writes)
        assertEquals(1, cipher.encrypted)
    }
}

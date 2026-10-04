package ru.sodovaya.volty.data.nearby

import kotlin.test.*

class PreferenceInvitationDraftRecordsTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val invitation = "20000000-0000-0000-0000-000000000002"
    private class Access : InvitationPreferenceAccess {
        val cache = mutableMapOf<String, String>()
        val disk = mutableMapOf<String, String>()
        val issued = mutableListOf<Map<String, String>>()
        var fileExists = false
        var succeeds = true
        override fun hasStoredFile() = fileExists
        override fun value(key: String) = cache[key]
        override fun commit(values: Map<String, String>): Boolean {
            issued += values.toMap()
            cache.putAll(values) // Deliberately model Android's failed-commit memory visibility.
            if (succeeds) { disk.clear(); disk.putAll(cache); fileExists = true }
            return succeeds
        }
    }

    @Test fun failedCommitThenConfirmationChangesFenceAndFlushesSameCachedCiphertext() {
        val access = Access().also { it.succeeds = false }
        val records = PreferenceInvitationDraftRecords(actor, access)
        records.locked { assertFalse(it.write(invitation, "ciphertext")) }
        assertEquals("ciphertext", access.cache["draft.$invitation"])
        assertTrue(access.disk.isEmpty())
        access.succeeds = true
        records.locked { assertEquals("ciphertext", it.peek(invitation)); assertTrue(it.confirm()) }
        assertEquals("1", access.issued[0]["schema"])
        assertEquals("1", access.issued[0]["fence"])
        assertEquals("2", access.issued[1]["fence"])
        assertEquals("ciphertext", access.disk["draft.$invitation"])
        records.locked { assertTrue(it.confirm()) }
        assertEquals("1", access.issued[2]["fence"])
    }

    @Test fun existingFileWithoutValidSchemaCannotBecomeAnEmptyNewStore() {
        for (schema in listOf(null, "2")) {
            val access = Access().also { it.fileExists = true; if (schema != null) it.cache["schema"] = schema }
            val records = PreferenceInvitationDraftRecords(actor, access)
            var entered = false
            assertFailsWith<IllegalStateException> { records.locked { entered = true; it.peek(invitation) } }
            assertFalse(entered)
            assertTrue(access.issued.isEmpty())
        }
        val virgin = Access()
        PreferenceInvitationDraftRecords(actor, virgin).locked { assertNull(it.peek(invitation)) }
        assertTrue(virgin.issued.isEmpty())
    }

    @Test fun existingCiphertextCannotBeOverwrittenAndUnmarkedCachedRecordIsCorrupt() {
        val access = Access(); val records = PreferenceInvitationDraftRecords(actor, access)
        records.locked { assertTrue(it.write(invitation, "first")) }
        assertFailsWith<IllegalStateException> { records.locked { it.write(invitation, "second") } }
        assertEquals("first", access.disk["draft.$invitation"])
        assertEquals(1, access.issued.size)
        access.cache.remove("schema")
        access.fileExists = false
        assertFailsWith<IllegalStateException> { records.locked { it.peek(invitation) } }
    }

    @Test fun invalidFenceCannotAcknowledgeARecordWithoutAChangedCommit() {
        for (fence in listOf("bad", "0", "-1", Long.MAX_VALUE.toString())) {
            val access = Access().also { it.fileExists = true; it.cache["schema"] = "1"; it.cache["fence"] = fence }
            val records = PreferenceInvitationDraftRecords(actor, access)
            assertFailsWith<IllegalStateException> { records.locked { it.confirm() } }
            assertTrue(access.issued.isEmpty())
        }
    }

    @Test fun confirmationMarkerCanAlternateWhileEveryCommitStillChangesTheCachedValue() {
        val access = Access().also { it.fileExists = true; it.cache["schema"] = "1"; it.cache["fence"] = "1" }
        val records = PreferenceInvitationDraftRecords(actor, access)
        repeat(6) { records.locked { assertTrue(it.confirm()) } }
        assertEquals(listOf("2", "1", "2", "1", "2", "1"), access.issued.map { it["fence"] })
        assertEquals(access.cache, access.disk)
    }
}

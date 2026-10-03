package ru.sodovaya.volty.backend.nearby

import java.util.Base64
import java.lang.reflect.Proxy
import javax.sql.DataSource
import kotlin.test.*

class InvitationTokensTest {
    private val raw = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    @Test fun canonicalTokenHasStableHashAndNeverAppearsInFailures() {
        assertEquals(64, InvitationTokens.hash(raw).length)
        assertEquals(InvitationTokens.hash(raw), InvitationTokens.hash(raw))
        assertNotEquals(InvitationTokens.hash(raw), InvitationTokens.hash(Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })))
        for (invalid in listOf(raw + "=", raw.dropLast(1), raw + "A", "!".repeat(43), raw.dropLast(1) + "F")) {
            val failure = assertFailsWith<IllegalArgumentException> { InvitationTokens.hash(invalid) }
            assertFalse(failure.message.orEmpty().contains(invalid))
        }
    }
    @Test fun invalidTtlAndQuotaAreRejectedBeforeOpeningConnection() {
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> error("Database must not be opened") } as DataSource
        val repository = JdbcInvitationRepository(source)
        val id = "00000000-0000-0000-0000-000000000001"
        for (ttl in listOf(0L, 899_999L, 604_800_001L, Long.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { repository.create(id, id, id, raw, ttl, 1, { 0 }) }
        for (quota in listOf(0, -1, 1001, Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { repository.create(id, id, id, raw, 900_000, quota, { 0 }) }
    }
}

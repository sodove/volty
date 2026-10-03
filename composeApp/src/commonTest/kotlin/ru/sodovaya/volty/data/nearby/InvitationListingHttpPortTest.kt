package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class InvitationListingHttpPortTest {
    private val community = "20000000-0000-0000-0000-000000000002"
    private val first = "30000000-0000-0000-0000-000000000003"
    private val second = "40000000-0000-0000-0000-000000000004"
    private fun item(id: String = second, revoked: Boolean = false) = """{"invitationId":"$id","communityId":"$community","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":10,"uses":2,"revokedAtMillis":${if (revoked) "101" else "null"},"creationRevision":7,"revocationRevision":${if (revoked) "8" else "null"}}"""
    private fun page(vararg items: String) = """{"items":[${items.joinToString(",") }]}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient) = InvitationListingHttpPort(client, "https://example.test/v2/nearby") { "bearer-secret" }
    @Test fun boundedExplicitCursorRequestPreservesExpiredAndRevokedMetadata() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(page(item(revoked = true))) }; val port = port(client)
        try {
            val result = assertIs<InvitationListingCommandResult.Listed>(port.list(community, first, 2))
            assertEquals(second, result.items.single().invitationId); assertEquals(101, result.items.single().revokedAtMillis)
            assertEquals(900100, result.items.single().expiresAtMillis); assertEquals(2, result.items.single().uses)
            assertEquals(1, requests.size); assertEquals(HttpMethod.Get, requests.single().method)
            assertEquals("/v2/nearby/communities/$community/invitations", requests.single().url.encodedPath)
            assertEquals(first, requests.single().url.parameters["after"]); assertEquals("2", requests.single().url.parameters["limit"])
            assertEquals("Bearer bearer-secret", requests.single().headers[HttpHeaders.Authorization])
        } finally { port.close(); client.close() }
    }
    @Test fun foreignDuplicateUnorderedOverlimitAndMalformedMetadataPagesAreRejected() = runTest {
        // Allow two rows for duplicate/order fixtures, so the size guard cannot hide those paths.
        val cases = listOf(Triple(page(item().replace(community, first)), 1, null), Triple(page(item(), item()), 2, null),
            Triple(page(item(second), item(first)), 2, null), Triple(page(item(first), item(second)), 1, null), Triple(page(item(first)), 1, first),
            Triple(page(item().replace("\"uses\":2", "\"uses\":11")), 1, null),
            Triple(page(item().replace("\"revokedAtMillis\":null", "\"revokedAtMillis\":101")), 1, null),
            Triple(page(item().replace("\"maxUses\":10", "\"maxUses\":\"10\"")), 1, null),
            Triple(page(item().replace("900100", "101")), 1, null), Triple(page(item().replace("\"creationRevision\":7", "\"creationRevision\":0")), 1, null))
        var issued = 0; val client = client { respond(cases[issued++].first) }; val port = port(client)
        try {
            for ((_, limit, after) in cases) assertEquals(InvitationListingCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.list(community, after, limit))
            assertEquals(cases.size, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun invalidInputDenialAndRedirectNeverRegenerateSecretsOrRetry() = runTest {
        var issued = 0; val client = client { issued++; if (issued == 1) respond("secret", HttpStatusCode.NotFound) else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test")) }; val port = port(client)
        try {
            assertFailsWith<IllegalArgumentException> { port.list(community, "bad", 20) }
            assertFailsWith<IllegalArgumentException> { port.list(community, null, 51) }
            assertEquals(0, issued)
            assertEquals(InvitationListingCommandResult.Failed(InvitationCommandFailure.ACCESS_DENIED), port.list(community))
            assertEquals(InvitationListingCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE), port.list(community))
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun oversizedAndMalformedUtf8CannotBecomeManagementList() = runTest {
        val malformed = (page(item()).dropLast(1) + ",\"ignored\":\"").encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        val bodies = listOf(ByteArray(16 * 1024 + 1) { 120 }, malformed)
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(2) { assertEquals(InvitationListingCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.list(community)) }
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun finiteIssuedTimeoutAndCancellationDoNotRetryRead() = runTest {
        var issued = 0; val entered = CompletableDeferred<Unit>(); var cancelled = false
        val client = client {
            if (issued++ == 0) { delay(10_001); respond(page(item())) }
            else { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        }; val port = port(client)
        try {
            assertEquals(InvitationListingCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE), port.list(community))
            val job = launch { port.list(community) }; entered.await(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled); assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
}

package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InvitationHttpPortTest {
    private val community = "20000000-0000-0000-0000-000000000002"
    private val invitation = "abcdef00-0000-0000-0000-000000000003"
    private val operation = "40000000-0000-0000-0000-000000000004"
    private val foreign = "50000000-0000-0000-0000-000000000005"
    private val token = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE"
    private val ttl = 900_000L
    private fun metadata() = """{"invitationId":"$invitation","communityId":"$community","createdAtMillis":100,"expiresAtMillis":900100,"maxUses":10,"uses":0,"revokedAtMillis":null,"creationRevision":7,"revocationRevision":null}"""
    private fun accepted(changed: String = "true") = """{"operationId":"$operation","invitationId":"$invitation","communityId":"$community","revision":8,"membershipChanged":$changed}"""
    private fun revoked() = """{"invitationId":"$invitation","communityId":"$community","revision":9}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient) = InvitationHttpPort(client, "https://example.test/v2/nearby", { "bearer-secret" })
    @Test fun stableSecretsStayInBodiesAndCommandsNeverImplicitlyJoinVoice() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(when { it.method == HttpMethod.Put -> metadata(); it.url.encodedPath.endsWith("/revoke") -> revoked(); else -> accepted() }) }; val port = port(client)
        try {
            repeat(2) {
                val result = assertIs<InvitationCreationCommandResult.Created>(port.create(community, invitation, token, ttl, 10))
                assertEquals(invitation, result.receipt.invitationId); assertFalse(result.toString().contains(token))
            }
            assertIs<InvitationRevocationCommandResult.Revoked>(port.revoke(community, invitation))
            val result = assertIs<InvitationAcceptanceCommandResult.Accepted>(port.accept(operation, token))
            assertEquals(community, result.receipt.communityId); assertTrue(result.receipt.membershipChanged); assertFalse(result.toString().contains(token))
            assertEquals(4, requests.size)
            assertEquals((requests[0].body as TextContent).text, (requests[1].body as TextContent).text)
            assertEquals("/v2/nearby/communities/$community/invitations/$invitation", requests[0].url.encodedPath)
            assertEquals("/v2/nearby/communities/$community/invitations/$invitation/revoke", requests[2].url.encodedPath)
            assertEquals("/v2/nearby/invitations/accept", requests[3].url.encodedPath)
            requests.forEach { assertFalse(it.url.toString().contains(token)); assertEquals("Bearer bearer-secret", it.headers[HttpHeaders.Authorization]) }
            assertEquals(token, Json.parseToJsonElement((requests[0].body as TextContent).text).jsonObject.getValue("token").jsonPrimitive.content)
            assertEquals(operation, Json.parseToJsonElement((requests[3].body as TextContent).text).jsonObject.getValue("operationId").jsonPrimitive.content)
            assertEquals(listOf(HttpMethod.Put, HttpMethod.Put, HttpMethod.Post, HttpMethod.Post), requests.map { it.method })
        } finally { port.close(); client.close() }
    }
    @Test fun creationRequiresExactIdentityExpiryQuotaAndUnrevokedMetadata() = runTest {
        val bodies = listOf(metadata().replace(community, foreign), metadata().replace(invitation, foreign),
            metadata().replace("900100", "900101"), metadata().replace("\"maxUses\":10", "\"maxUses\":9"),
            metadata().replace("\"uses\":0", "\"uses\":11"), metadata().replace("\"creationRevision\":7", "\"creationRevision\":0"),
            metadata().replace("\"revokedAtMillis\":null", "\"revokedAtMillis\":101"), metadata().replace("\"expiresAtMillis\":900100", "\"expiresAtMillis\":\"900100\""),
            metadata().replace(",\"revocationRevision\":null", ""))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(bodies.size) { assertEquals(InvitationCreationCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.create(community, invitation, token, ttl, 10)) }
            assertEquals(bodies.size, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun historicalAcceptanceAllowsEitherMembershipChangeButRejectsForeignOperationOrInvalidTypes() = runTest {
        val bodies = listOf(accepted(), accepted("false"), accepted().replace(operation, foreign), accepted("\"true\""),
            accepted().replace(community, "bad"), accepted().replace("\"revision\":8", "\"revision\":-1"))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            assertTrue(assertIs<InvitationAcceptanceCommandResult.Accepted>(port.accept(operation, token)).receipt.membershipChanged)
            assertFalse(assertIs<InvitationAcceptanceCommandResult.Accepted>(port.accept(operation, token)).receipt.membershipChanged)
            repeat(4) { assertEquals(InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.accept(operation, token)) }
            assertEquals(6, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun revocationRejectsForeignIdentityAndQuotedRevision() = runTest {
        val bodies = listOf(revoked().replace(community, foreign), revoked().replace(invitation, foreign), revoked().replace("\"revision\":9", "\"revision\":\"9\""))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(3) { assertEquals(InvitationRevocationCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.revoke(community, invitation)) }
            assertEquals(3, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun errorsRedirectsAndExceptionsAreTypedWithoutSecretOrRetry() = runTest {
        var issued = 0; val client = client {
            when (issued++) {
                0 -> respond(token, HttpStatusCode.Unauthorized)
                1 -> respond(token, HttpStatusCode.NotFound)
                2 -> respond("""{"code":"invitation_conflict"}""", HttpStatusCode.Conflict)
                3 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test"))
                else -> throw IllegalStateException(token)
            }
        }; val port = port(client)
        try {
            val results = List(5) { assertIs<InvitationAcceptanceCommandResult.Failed>(port.accept(operation, token)) }
            assertEquals(listOf(InvitationCommandFailure.UNAUTHORIZED, InvitationCommandFailure.ACCESS_DENIED, InvitationCommandFailure.CONFLICT,
                InvitationCommandFailure.UNAVAILABLE, InvitationCommandFailure.UNAVAILABLE), results.map { it.reason })
            assertTrue(results.none { it.toString().contains(token) }); assertEquals(5, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun boundedBodiesAndMalformedUtf8NeverBecomeHistoricalReceipt() = runTest {
        val malformed = (accepted().dropLast(1) + ",\"ignored\":\"").encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        assertEquals(operation, Json.parseToJsonElement(malformed.decodeToString()).jsonObject.getValue("operationId").jsonPrimitive.content)
        val bodies = listOf(ByteArray(16 * 1024 + 1) { 120 }, malformed)
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(2) { assertEquals(InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.INVALID_RESPONSE), port.accept(operation, token)) }
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun finiteTimeoutAndIssuedCancellationNeverAutomaticallyRetry() = runTest {
        var issued = 0; val entered = CompletableDeferred<Unit>(); var cancelled = false
        val client = client {
            if (issued++ == 0) { delay(10_001); respond(accepted()) }
            else { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }
        }; val port = port(client)
        try {
            assertEquals(InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAVAILABLE), port.accept(operation, token))
            val job = launch { port.accept(operation, token) }; entered.await(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled); assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun invalidInputsIncludingUnusedBase64BitsNeverReachAuthOrNetwork() = runTest {
        var issued = 0; var bearerCalls = 0; val client = client { issued++; error("No network") }
        val port = InvitationHttpPort(client, "https://example.test/v2/nearby") { bearerCalls++; null }
        try {
            for (raw in listOf(token + "=", token.dropLast(1), token.dropLast(1) + "F", "!".repeat(43)))
                assertFailsWith<IllegalArgumentException> { port.accept(operation, raw) }
            assertFailsWith<IllegalArgumentException> { port.create(community, invitation, token, ttl - 1, 10) }
            assertFailsWith<IllegalArgumentException> { port.create(community, invitation, token, ttl, 1001) }
            assertFailsWith<IllegalArgumentException> { port.revoke(community, invitation.uppercase()) }
            assertEquals(0, bearerCalls)
            assertEquals(InvitationAcceptanceCommandResult.Failed(InvitationCommandFailure.UNAUTHORIZED), port.accept(operation, token))
            assertEquals(1, bearerCalls); assertEquals(0, issued)
        } finally { port.close(); client.close() }
    }
}

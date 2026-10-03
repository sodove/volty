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
class CommunityRemovalHttpPortTest {
    private val community = "20000000-0000-0000-0000-000000000002"
    private val operation = "30000000-0000-0000-0000-000000000003"
    private val successor = "40000000-0000-0000-0000-000000000004"
    private fun receipt(id: String = community, op: String = operation, pending: String = "true") =
        """{"operationId":"$op","communityId":"$id","revision":9,"cleanupPending":$pending}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient) = CommunityRemovalHttpPort(client, "https://example.test/v2/nearby/communities", { "bearer-secret" })
    @Test fun stableLeavePayloadAndExplicitDeleteKeepRequestIdentity() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(receipt()) }; val port = port(client)
        try {
            repeat(2) { assertIs<CommunityRemovalResult.Removed>(port.leave(community, operation, successor)) }
            assertIs<CommunityRemovalResult.Removed>(port.delete(community, operation))
            assertEquals(3, requests.size)
            assertEquals((requests[0].body as TextContent).text, (requests[1].body as TextContent).text)
            assertEquals("/v2/nearby/communities/$community/leave", requests[0].url.encodedPath)
            assertEquals("/v2/nearby/communities/$community/delete", requests[2].url.encodedPath)
            requests.forEach { assertEquals(HttpMethod.Post, it.method); assertEquals("Bearer bearer-secret", it.headers[HttpHeaders.Authorization]) }
            assertTrue((requests[0].body as TextContent).text.contains(successor))
            assertFalse((requests[2].body as TextContent).text.contains("successorId"))
            assertTrue(requests.all { !(it.body as TextContent).text.contains("actorId") })
        } finally { port.close(); client.close() }
    }
    @Test fun ownerConflictsRemainTypedAndNeverAutomaticallyDelete() = runTest {
        val codes = listOf("successor_required", "delete_required", "invalid_successor", "unknown")
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond("""{"code":"${codes[requests.lastIndex]}"}""", HttpStatusCode.Conflict) }; val port = port(client)
        try {
            val reasons = List(4) { assertIs<CommunityRemovalResult.Failed>(port.leave(community, operation)).reason }
            assertEquals(listOf(CommunityRemovalFailure.SUCCESSOR_REQUIRED, CommunityRemovalFailure.DELETE_REQUIRED,
                CommunityRemovalFailure.INVALID_SUCCESSOR, CommunityRemovalFailure.CONFLICT), reasons)
            assertEquals(4, requests.size); assertTrue(requests.all { it.url.encodedPath.endsWith("/leave") })
        } finally { port.close(); client.close() }
    }
    @Test fun foreignReceiptAndMissingOrQuotedCleanupEvidenceAreRejected() = runTest {
        val bodies = listOf(receipt(successor), receipt(op = successor), receipt(pending = "\"false\""),
            receipt().replace(",\"cleanupPending\":true", ""), receipt().replace("\"revision\":9", "\"revision\":-1"))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(bodies.size) { assertEquals(CommunityRemovalResult.Failed(CommunityRemovalFailure.INVALID_RESPONSE), port.leave(community, operation)) }
            assertEquals(5, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun actualCleanupBooleanIsPreservedIndependentlyOfIgnoredSessionShape() = runTest {
        var issued = 0; val client = client {
            respond(receipt(pending = if (issued++ == 0) "true" else "false").dropLast(1) + ",\"sessionId\":null,\"phase\":\"CANCELLED\"}")
        }; val port = port(client)
        try {
            assertTrue(assertIs<CommunityRemovalResult.Removed>(port.leave(community, operation)).receipt.cleanupPending)
            assertFalse(assertIs<CommunityRemovalResult.Removed>(port.leave(community, operation)).receipt.cleanupPending)
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun boundedBodiesAndInvalidUtf8NeverProduceRemovalReceipt() = runTest {
        val malformed = (receipt().dropLast(1) + ",\"ignored\":\"").encodeToByteArray() +
            byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        val replacementDocument = Json.parseToJsonElement(malformed.decodeToString()).jsonObject
        assertEquals(community, replacementDocument.getValue("communityId").jsonPrimitive.content)
        assertEquals(operation, replacementDocument.getValue("operationId").jsonPrimitive.content)
        assertTrue(replacementDocument.getValue("cleanupPending").jsonPrimitive.boolean)
        val bodies = listOf(ByteArray(16 * 1024 + 1) { 120 }, malformed)
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(2) { assertEquals(CommunityRemovalResult.Failed(CommunityRemovalFailure.INVALID_RESPONSE), port.delete(community, operation)) }
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun deniedRedirectAndNetworkExceptionsHaveSafeTypedOutcomes() = runTest {
        var issued = 0; val client = client {
            when (issued++) {
                0 -> respond("bearer-secret", HttpStatusCode.Unauthorized)
                1 -> respond("bearer-secret", HttpStatusCode.NotFound)
                2 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test"))
                else -> throw IllegalStateException("bearer-secret")
            }
        }; val port = port(client)
        try {
            val reasons = List(4) { assertIs<CommunityRemovalResult.Failed>(port.delete(community, operation)).reason }
            assertEquals(listOf(CommunityRemovalFailure.UNAUTHORIZED, CommunityRemovalFailure.ACCESS_DENIED,
                CommunityRemovalFailure.UNAVAILABLE, CommunityRemovalFailure.UNAVAILABLE), reasons)
            assertEquals(4, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun cancellationAfterIssuePropagatesWithoutAutomaticRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); var cancelled = false; var issued = 0
        val client = client { issued++; entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }; val port = port(client)
        try {
            val job = launch { port.leave(community, operation) }
            entered.await(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled); assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun slowIssuedRequestHasFiniteWholeDeadline() = runTest {
        var issued = 0; val client = client { issued++; delay(10_001); respond(receipt()) }; val port = port(client)
        try {
            assertEquals(CommunityRemovalResult.Failed(CommunityRemovalFailure.UNAVAILABLE), port.leave(community, operation))
            assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }
}

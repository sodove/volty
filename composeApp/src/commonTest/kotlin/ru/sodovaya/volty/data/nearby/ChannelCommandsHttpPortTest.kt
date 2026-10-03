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
class ChannelCommandsHttpPortTest {
    private val community = "20000000-0000-0000-0000-000000000002"
    private val channel = "abcdef00-0000-0000-0000-000000000003"
    private val operation = "40000000-0000-0000-0000-000000000004"
    private val foreign = "50000000-0000-0000-0000-000000000005"
    private fun created(parent: String = community, child: String = channel) = """{"communityId":"$parent","channel":{"id":"$child","name":"Поездка","isDefault":false},"revision":7}"""
    private fun deleted(parent: String = community, child: String = channel, op: String = operation, pending: String = "true") =
        """{"operationId":"$op","communityId":"$parent","channelId":"$child","revision":8,"cleanupPending":$pending}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient) = ChannelCommandsHttpPort(client, "https://example.test/v2/nearby/communities", { "bearer-secret" })
    @Test fun stableCreateAndDeleteIssueExactCommandsWithoutJoining() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = client { requests += it; respond(if (it.method == HttpMethod.Put) created() else deleted()) }; val port = port(client)
        try {
            repeat(2) {
                val result = assertIs<ChannelCreationCommandResult.Created>(port.create(community, channel, "  Поездка  "))
                assertEquals(channel, result.receipt.channelId); assertEquals(community, result.receipt.communityId)
                assertEquals("Поездка", result.receipt.name); assertEquals(7, result.receipt.revision)
            }
            assertIs<ChannelDeletionCommandResult.Deleted>(port.delete(community, channel, operation))
            assertEquals(3, requests.size)
            assertEquals(List(2) { HttpMethod.Put } + HttpMethod.Post, requests.map { it.method })
            assertEquals((requests[0].body as TextContent).text, (requests[1].body as TextContent).text)
            assertEquals("/v2/nearby/communities/$community/channels/$channel", requests[0].url.encodedPath)
            assertEquals("/v2/nearby/communities/$community/channels/$channel/delete", requests[2].url.encodedPath)
            assertEquals(setOf("name"), Json.parseToJsonElement((requests[0].body as TextContent).text).jsonObject.keys)
            assertEquals(operation, Json.parseToJsonElement((requests[2].body as TextContent).text).jsonObject.getValue("operationId").jsonPrimitive.content)
            requests.forEach { assertEquals("Bearer bearer-secret", it.headers[HttpHeaders.Authorization]) }
        } finally { port.close(); client.close() }
    }
    @Test fun foreignCreationIdentityAndIncoherentCreationMetadataAreRejected() = runTest {
        val bodies = listOf(created(parent = foreign), created(child = foreign), created().replace("false", "true"),
            created().replace("false", "\"false\""), created().replace("\"revision\":7", "\"revision\":0"),
            created().replace("\"revision\":7", "\"revision\":\"7\""), created().replace("Поездка", "Other"),
            created().replace("\"name\":\"Поездка\"", "\"name\":123"))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(bodies.size) { assertEquals(ChannelCreationCommandResult.Failed(ChannelCommandFailure.INVALID_RESPONSE), port.create(community, channel, "Поездка")) }
            assertEquals(bodies.size, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun deleteRejectsForeignIdentityAndMissingQuotedOrNegativeEvidence() = runTest {
        val bodies = listOf(deleted(parent = foreign), deleted(child = foreign), deleted(op = foreign), deleted(pending = "\"false\""),
            deleted().replace(",\"cleanupPending\":true", ""), deleted().replace("\"revision\":8", "\"revision\":-1"))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(bodies.size) { assertEquals(ChannelDeletionCommandResult.Failed(ChannelCommandFailure.INVALID_RESPONSE), port.delete(community, channel, operation)) }
            assertEquals(bodies.size, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun deletionKeepsActualCleanupFlagIndependentlyOfIgnoredTerminalShape() = runTest {
        var issued = 0; val client = client { respond(deleted(pending = if (issued++ == 0) "true" else "false").dropLast(1) + ",\"phase\":\"CANCELLED\",\"sessionId\":null}") }; val port = port(client)
        try {
            assertTrue(assertIs<ChannelDeletionCommandResult.Deleted>(port.delete(community, channel, operation)).receipt.cleanupPending)
            assertFalse(assertIs<ChannelDeletionCommandResult.Deleted>(port.delete(community, channel, operation)).receipt.cleanupPending)
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun conflictsCapDenialRedirectAndNetworkErrorsAreTypedWithoutFallback() = runTest {
        var issued = 0; val client = client {
            when (issued++) {
                0 -> respond("""{"code":"channel_limit_reached"}""", HttpStatusCode.Conflict)
                1 -> respond("""{"code":"channel_conflict"}""", HttpStatusCode.Conflict)
                2 -> respond("bearer-secret", HttpStatusCode.NotFound)
                3 -> respond("bearer-secret", HttpStatusCode.Unauthorized)
                4 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test"))
                else -> throw IllegalStateException("bearer-secret")
            }
        }; val port = port(client)
        try {
            val reasons = List(6) { assertIs<ChannelCreationCommandResult.Failed>(port.create(community, channel, "Поездка")).reason }
            assertEquals(listOf(ChannelCommandFailure.LIMIT_REACHED, ChannelCommandFailure.CONFLICT, ChannelCommandFailure.ACCESS_DENIED,
                ChannelCommandFailure.UNAUTHORIZED, ChannelCommandFailure.UNAVAILABLE, ChannelCommandFailure.UNAVAILABLE), reasons)
            assertEquals(6, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun oversizedAndInvalidUtf8SuccessesAreNeverReceipts() = runTest {
        val malformed = (deleted().dropLast(1) + ",\"ignored\":\"").encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        assertEquals(channel, Json.parseToJsonElement(malformed.decodeToString()).jsonObject.getValue("channelId").jsonPrimitive.content)
        val bodies = listOf(ByteArray(16 * 1024 + 1) { 120 }, malformed)
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(2) { assertEquals(ChannelDeletionCommandResult.Failed(ChannelCommandFailure.INVALID_RESPONSE), port.delete(community, channel, operation)) }
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun cancellationAfterIssuePropagatesWithoutRetry() = runTest {
        val entered = CompletableDeferred<Unit>(); var cancelled = false; var issued = 0
        val client = client { issued++; entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } }; val port = port(client)
        try {
            val job = launch { port.delete(community, channel, operation) }; entered.await(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled); assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun slowIssuedRequestTimesOutOnce() = runTest {
        var issued = 0; val client = client { issued++; delay(10_001); respond(created()) }; val port = port(client)
        try {
            assertEquals(ChannelCreationCommandResult.Failed(ChannelCommandFailure.UNAVAILABLE), port.create(community, channel, "Поездка"))
            assertEquals(1, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun invalidInputAndMissingBearerNeverIssueNetworkRequest() = runTest {
        var issued = 0; var bearerCalls = 0
        val client = client { issued++; error("No network allowed") }
        val port = ChannelCommandsHttpPort(client, "https://example.test/v2/nearby/communities") { bearerCalls++; null }
        try {
            assertFailsWith<IllegalArgumentException> { port.create(community, channel.uppercase(), "Поездка") }
            assertFailsWith<IllegalArgumentException> { port.create(community, channel, " ") }
            assertFailsWith<IllegalArgumentException> { port.create(community, channel, "bad\nname") }
            assertFailsWith<IllegalArgumentException> { port.delete(community, channel, "bad") }
            assertEquals(0, bearerCalls)
            assertEquals(ChannelCreationCommandResult.Failed(ChannelCommandFailure.UNAUTHORIZED), port.create(community, channel, "Поездка"))
            assertEquals(1, bearerCalls); assertEquals(0, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun unsafeBaseUrlsAreRejectedBeforeCreatingRequests() = runTest {
        val client = client { error("No network allowed") }
        try {
            for (base in listOf("http://example.test", "https://user:secret@example.test", "https://example.test?q=secret", "https://example.test#fragment"))
                assertFailsWith<IllegalArgumentException> { ChannelCommandsHttpPort(client, base) { "secret" } }
        } finally { client.close() }
    }
}

package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class VoiceSessionHttpPortTest {
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private val operation = VoiceOperationId("10000000-0000-0000-0000-000000000001")
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val session = VoiceSessionId("30000000-0000-0000-0000-000000000003")
    private val generation = VoiceGeneration(7)
    private val tag = VoiceSessionTag(session, generation)
    private var time = 100L
    private fun port(client: HttpClient) = VoiceSessionHttpPort(client, "https://example.test/v2/nearby", { "bearer-secret" }, { time })
    private fun body(credit: String = ",\"remainingLeaseMillis\":5000", credentials: Boolean = true,
                     sessionId: String = session.value, phase: String = "CONNECTING") =
        """{"phase":"$phase","sessionId":"$sessionId","deadlineEpochMillis":999999999$credit${if (credentials) ",\"credentials\":{\"serverUrl\":\"wss://sfu.example.test\",\"participantToken\":\"participant-secret\",\"roomEpoch\":\"room\"}" else ""}}"""

    @Test fun grantIsChargedFromIssuedRequestStartNotResponseArrivalOrWallDeadline() = runTest {
        var issued: HttpRequestData? = null
        val client = client { request -> issued = request; time = 4000; respond(body()) }
        val port = port(client)
        try {
            val result = assertIs<VoiceHttpReply.Credentials>(port.join(operation, generation, channel))
            assertEquals(100, result.lease.requestStartedMillis); assertEquals(4000, result.lease.responseReceivedMillis)
            assertEquals(5100, result.lease.deadlineMillis); assertEquals(tag, result.tag)
            assertEquals("/v2/nearby/operations/${operation.value}/join", issued!!.url.encodedPath)
            assertEquals("Bearer bearer-secret", issued!!.headers[HttpHeaders.Authorization])
            assertTrue((issued!!.body as TextContent).text.contains(channel))
            assertFalse(result.toString().contains("participant-secret")); assertFalse(result.toString().contains("bearer-secret"))
        } finally { port.close(); client.close() }
    }

    @Test fun expiredReceiptRetainsCleanupIdentityWithoutCredentials() = runTest {
        val client = client { time = 5100; respond(body()) }
        val port = port(client)
        try {
            val result = assertIs<VoiceHttpReply.Expired>(port.join(operation, generation, channel))
            assertEquals(tag, result.tag); assertFalse(result.toString().contains("participant-secret"))
        } finally { port.close(); client.close() }
    }

    @Test fun missingOrZeroCreditNeverFallsBackToThirtySeconds() = runTest {
        var credit = ""
        val client = client { respond(body(credit)) }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE), port.join(operation, generation, channel))
            credit = ",\"remainingLeaseMillis\":0"
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE), port.join(operation, generation, channel))
        } finally { port.close(); client.close() }
    }

    @Test fun foreignHeartbeatSessionCannotAcknowledgeCurrentLease() = runTest {
        val client = client { respond(body(credentials = false, sessionId = channel, phase = "CONNECTED")) }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE), port.heartbeat(tag))
        } finally { port.close(); client.close() }
    }

    @Test fun equalClockTicksStillAllocateIncreasingRequestOrdinalsAndPendingGivesNoLease() = runTest {
        var response = body()
        val client = client { respond(response) }
        val port = port(client)
        try {
            val first = assertIs<VoiceHttpReply.Credentials>(port.join(operation, generation, channel))
            response = body(credentials = false, phase = "CONNECTED")
            val second = assertIs<VoiceHttpReply.Acknowledged>(port.heartbeat(tag))
            assertTrue(second.lease.requestId.value > first.lease.requestId.value)
            response = """{"phase":"PENDING","operationId":"${operation.value}","sessionId":"${session.value}","deadlineEpochMillis":999999999}"""
            assertIs<VoiceHttpReply.Pending>(port.status(operation, generation))
        } finally { port.close(); client.close() }
    }

    @Test fun deniedAndOversizedBodiesDoNotExposePrivateErrorsOrMintLease() = runTest {
        var denied = true
        val client = client {
            if (denied) respond("private participant-secret", HttpStatusCode.Unauthorized)
            else respond("x".repeat(16 * 1024 + 1))
        }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.UNAUTHORIZED), port.join(operation, generation, channel))
            denied = false
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE), port.join(operation, generation, channel))
        } finally { port.close(); client.close() }
    }

    @Test fun activeJoinWithoutCredentialsCannotBecomeAnEngineGrant() = runTest {
        val client = client { respond(body(credentials = false)) }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.INVALID_RESPONSE), port.join(operation, generation, channel))
        } finally { port.close(); client.close() }
    }

    @Test fun redirectIsNotFollowedToAnotherOriginWithBearer() = runTest {
        var issued = 0
        val client = client {
            issued++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://foreign.example.test/voice"))
        }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE), port.join(operation, generation, channel))
            assertEquals(1, issued, "No redirected request issued")
        } finally { port.close(); client.close() }
    }

    @Test fun busyConflictRemainsDistinctWithoutLeakingErrorMessage() = runTest {
        val client = client { respond("""{"code":"voice_busy","message":"participant-secret"}""", HttpStatusCode.Conflict) }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.BUSY), port.join(operation, generation, channel))
        } finally { port.close(); client.close() }
    }

    @Test fun networkFailureAndDeadlineAfterIssuedRequestAreUnavailable() = runTest {
        var fail = true
        var issued = 0
        val client = client {
            issued++
            if (fail) error("private participant-secret")
            delay(10_001)
            respond(body())
        }
        val port = port(client)
        try {
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE), port.join(operation, generation, channel))
            fail = false
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE), port.join(operation, generation, channel))
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }

    @Test fun cancellingAnActuallyIssuedRequestPropagatesCancellation() = runTest {
        val issued = CompletableDeferred<Unit>()
        val client = client { issued.complete(Unit); awaitCancellation() }
        val port = port(client)
        try {
            val pending = async { port.join(operation, generation, channel) }
            issued.await()
            pending.cancel(); pending.join()
            assertTrue(pending.isCancelled)
        } finally { port.close(); client.close() }
    }
}

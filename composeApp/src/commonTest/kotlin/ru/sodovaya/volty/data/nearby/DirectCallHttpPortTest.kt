package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.voice.VoiceHttpReply
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class DirectCallHttpPortTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val key = "40000000-0000-0000-0000-000000000004"
    private val operation = VoiceOperationId("50000000-0000-0000-0000-000000000005")
    private val session = "60000000-0000-0000-0000-000000000006"
    private val generation = VoiceGeneration(1)
    private fun snapshot(phase: String = "RINGING", caller: String = actor, callee: String = peer, id: String = call,
                         outcome: String? = null) = """{"callId":"$id","callerId":"$caller","calleeId":"$callee","phase":"$phase","revision":1,"ringDeadlineEpochMillis":45100,"outcome":${outcome?.let {"\"$it\""} ?: "null"}}"""
    private fun response(parent: String = snapshot(), media: String? = null, failure: String? = null) =
        """{"call":$parent,"session":${media ?: "null"},"retryableFailure":${failure?.let {"\"$it\""} ?: "null"}}"""
    private fun credential(op: String = operation.value) = """{"phase":"CONNECTING","operationId":"$op","sessionId":"$session","deadlineEpochMillis":1,"remainingLeaseMillis":12000,"credentials":{"serverUrl":"wss://media.test","participantToken":"private-own-token","roomEpoch":"volty-call-$call"}}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler)}}

    @Test fun ringingCreditUsesOriginalRequestStartAndNotPhoneEpochDeadline() = runTest {
        var clock = 100L
        val http = client {clock = 400; respond("""{"call":${snapshot()},"remainingRingMillis":1000}""")}
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock})
        try {
            val observation = assertNotNull(assertIs<DirectCallReply.Ready>(port.status(call)).ringObservation)
            assertEquals(call, observation.callId); assertEquals(1, observation.revision)
            assertEquals(100, observation.requestStartedMillis); assertEquals(400, observation.responseReceivedMillis)
            assertEquals(1100, observation.deadlineMillis)
            assertTrue(observation.isFresh(1099)); assertFalse(observation.isFresh(1100)); assertFalse(observation.isFresh(399))
        } finally {port.close(); http.close()}
    }

    @Test fun delayedOrZeroRingingCreditNeverRenewsOnReceipt() = runTest {
        for (credit in listOf(1000, 0)) {
            var clock = 100L
            val http = client {clock = 1100; respond("""{"call":${snapshot()},"remainingRingMillis":$credit}""")}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock})
            try {
                val observation = assertNotNull(assertIs<DirectCallReply.Ready>(port.status(call)).ringObservation)
                assertFalse(observation.isFresh(clock)); assertEquals(100L + credit, observation.deadlineMillis)
            } finally {port.close(); http.close()}
        }
    }

    @Test fun malformedOrNonRingingCreditCannotProduceTimerEvidence() = runTest {
        for (credit in listOf("-1", "45001", "\"1000\"", "0.1", Long.MAX_VALUE.toString())) {
            val http = client {respond("""{"call":${snapshot()},"remainingRingMillis":$credit}""")}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
            try {assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<DirectCallReply.Failed>(port.status(call)).reason)}
            finally {port.close(); http.close()}
        }
        val http = client {respond("""{"call":${snapshot("ACCEPTED")},"remainingRingMillis":1000}""")}
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
        try {assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<DirectCallReply.Failed>(port.status(call)).reason)}
        finally {port.close(); http.close()}
    }

    @Test fun overflowOrBackwardClockCannotProduceRingingCredit() = runTest {
        for ((started, received) in listOf(Long.MAX_VALUE - 100 to Long.MAX_VALUE - 50, 100L to 99L)) {
            var clock = started
            val http = client {clock = received; respond("""{"call":${snapshot()},"remainingRingMillis":1000}""")}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock})
            try {assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<DirectCallReply.Failed>(port.status(call)).reason)}
            finally {port.close(); http.close()}
        }
    }

    @Test fun outgoingCreationAndEarlyCancellationKeepOriginalPayloadAndBearer() = runTest {
        val issued = mutableListOf<String>()
        val http = client { request ->
            assertEquals("Bearer account-token", request.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Post, request.method)
            val body = (request.body as TextContent).text
            for (value in listOf(peer, key, operation.value)) assertTrue(body.contains(value))
            assertFalse(body.contains("actorId")); assertFalse(body.contains("roomEpoch"))
            issued += request.url.encodedPath
            respond(response(if (request.url.encodedPath.endsWith("cancel-outgoing"))
                snapshot("CANCELLED", outcome = "CALLER_CANCELLED") else snapshot()), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"account-token"}, {100})
        try {
            val created = port.create(call, peer, key, operation)
            assertEquals(1, issued.size)
            assertIs<DirectCallReply.Ready>(created)
            val cancelled = port.cancelOutgoing(call, peer, key, operation)
            assertEquals(2, issued.size)
            assertIs<DirectCallReply.Ready>(cancelled)
            assertEquals(listOf("/v2/nearby/calls/$call/create", "/v2/nearby/calls/$call/cancel-outgoing"), issued)
        } finally {port.close(); http.close()}
    }

    @Test fun acceptedCredentialsUseRequestStartAndExpiredReplyDiscardsToken() = runTest {
        var clock = 100L
        val http = client {clock += 12_000; respond(response(snapshot("ACCEPTED"), credential()), headers = headersOf(HttpHeaders.ContentType, "application/json"))}
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock})
        try {
            val reply = assertIs<DirectCallReply.Ready>(port.resume(call, operation, generation))
            assertIs<VoiceHttpReply.Expired>(reply.session)
            assertFalse(reply.toString().contains("private-own-token"))
        } finally {port.close(); http.close()}
    }

    @Test fun acceptedRegisteredReplyCarriesOwnGenerationAndConservativeCredit() = runTest {
        var clock = 100L
        val http = client {clock += 300; respond(response(snapshot("ACCEPTED", peer, actor), credential()), headers = headersOf(HttpHeaders.ContentType, "application/json"))}
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock})
        try {
            val reply = assertIs<DirectCallReply.Ready>(port.accept(call, operation, generation, 1))
            val media = assertIs<VoiceHttpReply.Credentials>(reply.session)
            assertEquals(generation, media.tag.generation)
            assertEquals(12_100L, media.lease.deadlineMillis)
            assertEquals(400L, media.lease.responseReceivedMillis)
            assertFalse(reply.toString().contains("private-own-token"))
        } finally {port.close(); http.close()}
    }

    @Test fun incoherentParentIdentityAndCredentialsAreRejected() = runTest {
        val outsider = "70000000-0000-0000-0000-000000000007"
        val cases = listOf(
            response(snapshot(id = outsider)),
            response(snapshot(caller = outsider, callee = peer)),
            response(snapshot("ENDED", outcome = "CALLEE_ENDED"), credential()),
            response(snapshot("RINGING"), credential()),
            response(snapshot("ACCEPTED"), credential(key)),
            response(snapshot("ACCEPTED"), credential().replace("volty-call-$call", "foreign-room")),
            response(snapshot("RINGING", outcome = "RING_TIMEOUT")),
        )
        for (document in cases) {
            val http = client {respond(document, headers = headersOf(HttpHeaders.ContentType, "application/json"))}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
            try {assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<DirectCallReply.Failed>(port.resume(call, operation, generation)).reason)}
            finally {port.close(); http.close()}
        }
    }

    @Test fun incomingPageRequiresActualCalleeRingingAndForwardCursor() = runTest {
        for ((document, valid) in listOf(
            """{"calls":[${snapshot(caller = peer, callee = actor)}],"nextCursor":"$call"}""" to true,
            """{"calls":[${snapshot()}]}""" to false,
            """{"calls":[${snapshot("ACCEPTED", peer, actor)}]}""" to false,
            """{"calls":[${snapshot(caller = peer, callee = actor)},${snapshot(caller = peer, callee = actor)}]}""" to false,
        )) {
            val http = client {respond(document, headers = headersOf(HttpHeaders.ContentType, "application/json"))}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
            try {
                val reply = port.incoming(limit = 2)
                if (valid) assertIs<IncomingCallReply.Ready>(reply)
                else assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<IncomingCallReply.Failed>(reply).reason)
            } finally {port.close(); http.close()}
        }
    }

    @Test fun unavailableAndStatusNeverPublishBearerFromMalformedResponse() = runTest {
        for ((status, useResume) in listOf(HttpStatusCode.ServiceUnavailable to true, HttpStatusCode.OK to false)) {
            val http = client {respond(response(snapshot("ACCEPTED"), credential(), if (useResume) "UNAVAILABLE" else null), status,
                headersOf(HttpHeaders.ContentType, "application/json"))}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
            try {
                val reply = if (useResume) port.resume(call, operation, generation) else port.status(call)
                assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<DirectCallReply.Failed>(reply).reason)
            } finally {port.close(); http.close()}
        }
    }

    @Test fun everyRemainingActionPreservesMethodIdentityAndTransportMeaning() = runTest {
        val issued = mutableListOf<String>()
        val http = client {request ->
            issued += "${request.method.value}:${request.url.encodedPath}"
            val document = when {
                request.url.encodedPath.endsWith("accept") -> {
                    val body = (request.body as TextContent).text
                    assertTrue(body.contains(operation.value)); assertTrue(body.contains("\"expectedRevision\":7"))
                    response(snapshot("ACCEPTED", peer, actor), """{"phase":"CONNECTED","sessionId":"$session","remainingLeaseMillis":12000}""")
                }
                request.url.encodedPath.endsWith("decline") -> response(snapshot("DECLINED", peer, actor, outcome = "CALLEE_DECLINED"))
                request.url.encodedPath.endsWith("stop") -> response(snapshot("ENDED", outcome = "CALLER_ENDED"))
                else -> response()
            }
            respond(document, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
        try {
            assertIs<DirectCallReply.Ready>(port.status(call))
            val accepted = assertIs<DirectCallReply.Ready>(port.accept(call, operation, generation, 7))
            assertTrue(assertIs<VoiceHttpReply.Acknowledged>(accepted.session).confirmedTransport)
            assertIs<DirectCallReply.Ready>(port.decline(call))
            assertIs<DirectCallReply.Ready>(port.stop(call))
            assertEquals(listOf("GET:/v2/nearby/calls/$call", "POST:/v2/nearby/calls/$call/accept",
                "POST:/v2/nearby/calls/$call/decline", "POST:/v2/nearby/calls/$call/stop"), issued)
        } finally {port.close(); http.close()}
    }

    @Test fun pendingUnavailableAndTerminalKeepActualEvidenceWithoutManufacturingCleanup() = runTest {
        for ((document, status, expected) in listOf(
            Triple(response(snapshot("ACCEPTED"), """{"phase":"PENDING","operationId":"${operation.value}","sessionId":"$session"}"""), HttpStatusCode.Accepted, "pending"),
            Triple(response(snapshot("RINGING"), failure = "UNAVAILABLE"), HttpStatusCode.ServiceUnavailable, "unavailable"),
            Triple(response(snapshot("ENDED", outcome = "MEDIA_LOST"), """{"phase":"CANCELLED","sessionId":"$session","cleanupPending":true}"""), HttpStatusCode.OK, "ended"),
            Triple(response(snapshot("ENDED", outcome = "MEDIA_LOST")), HttpStatusCode.OK, "no-child"),
        )) {
            val http = client {respond(document, status, headersOf(HttpHeaders.ContentType, "application/json"))}
            val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
            try {
                val reply = assertIs<DirectCallReply.Ready>(port.resume(call, operation, generation))
                when (expected) {
                    "pending" -> assertIs<VoiceHttpReply.Pending>(reply.session)
                    "unavailable" -> {assertEquals("UNAVAILABLE", reply.retryableFailure); assertNull(reply.session)}
                    "ended" -> assertEquals(true, assertIs<VoiceHttpReply.Ended>(reply.session).cleanupPending)
                    else -> assertNull(reply.session)
                }
            } finally {port.close(); http.close()}
        }
    }

    @Test fun filteredEmptyPageAdvancesButBackwardCursorAndInvalidBoundsAreRejected() = runTest {
        var issued = 0
        val http = client {issued++; respond("""{"calls":[],"nextCursor":"$call"}""")}
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {100})
        try {
            assertEquals(call, assertIs<IncomingCallReply.Ready>(port.incoming(after = peer)).nextCursor)
            assertEquals(DirectCallFailure.INVALID_RESPONSE, assertIs<IncomingCallReply.Failed>(port.incoming(after = call)).reason)
            assertEquals(2, issued)
            assertFailsWith<IllegalArgumentException> {port.incoming(limit = 51)}
            assertFailsWith<IllegalArgumentException> {port.resume("bad", operation, generation)}
            assertFailsWith<IllegalArgumentException> {port.create(call, actor, key, operation)}
            assertEquals(2, issued)
        } finally {port.close(); http.close()}
    }

    @Test fun missingAuthenticationAndRedirectNeverIssueUntrustedFollowUp() = runTest {
        var issued = 0
        val http = client {issued++; respond("", HttpStatusCode.TemporaryRedirect, headersOf(HttpHeaders.Location, "https://foreign.test/calls"))}
        var token: String? = null
        val port = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {token}, {100})
        try {
            assertEquals(DirectCallFailure.UNAUTHORIZED, assertIs<DirectCallReply.Failed>(port.status(call)).reason)
            assertEquals(0, issued)
            token = "private-token"
            assertEquals(DirectCallFailure.UNAVAILABLE, assertIs<DirectCallReply.Failed>(port.status(call)).reason)
            assertEquals(1, issued)
        } finally {port.close(); http.close()}
    }
}

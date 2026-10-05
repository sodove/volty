package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.nearby.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSharingHttpPortTest {
    private val session = "10000000-0000-0000-0000-000000000001"
    private val generation = "20000000-0000-0000-0000-000000000002"
    private val tag = ChannelSharingTag(session, generation)
    private fun reply(phase: String = "ACTIVE", mode: String = "\"LOCATION\"", credit: String = "1000", sid: String = session, gen: String = generation) =
        """{"sessionId":"$sid","generation":"$gen","phase":"$phase","mode":$mode,"remainingMillis":$credit}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler)}}

    @Test fun actualStartBindsPathBodyBearerAndOriginalIssuanceCredit() = runTest {
        var clock = 100L
        val http = client {request ->
            assertEquals("/v2/nearby/sharing/$session/$generation/start", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method); assertEquals("Bearer original-token", request.headers[HttpHeaders.Authorization])
            val body = assertIs<TextContent>(request.body).text
            assertTrue(body.contains("\"mode\":\"LOCATION\"")); assertTrue(body.contains("\"ttl\":\"HALF_HOUR\""))
            clock = 400; respond(reply())
        }
        val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"original-token"}, {clock})
        try {
            val observation = assertIs<ChannelSharingReply.Active>(port.start(tag, ChannelSharingMode.LOCATION, ChannelSharingTtl.HALF_HOUR)).observation
            assertEquals(tag, observation.tag); assertEquals(0, observation.requestOrdinal)
            assertEquals(100, observation.requestStartedMillis); assertEquals(400, observation.responseReceivedMillis); assertEquals(1100, observation.deadlineMillis)
            assertTrue(observation.isFresh(1099)); assertFalse(observation.isFresh(1100)); assertFalse(observation.isFresh(399))
        } finally {port.close(); http.close()}
    }

    @Test fun negativeAcknowledgementRequiresExactOriginalTagAndPerformsNoPositivePreflight() = runTest {
        val paths = mutableListOf<String>()
        val http = client {paths += it.url.encodedPath; respond(reply("REVOKED", "null", "0"))}
        val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100})
        try {
            assertEquals(ChannelSharingReply.Negative(tag, ChannelSharingNegativePhase.REVOKED), port.revoke(tag))
            assertEquals(listOf("/v2/nearby/sharing/$session/$generation/revoke"), paths)
            assertEquals(ChannelSharingFailure.INVALID_RESPONSE, assertIs<ChannelSharingReply.Failed>(port.status(tag)).reason)
        } finally {port.close(); http.close()}
    }

    @Test fun incoherentOrMisboundPositiveEvidenceCannotGrantConsent() = runTest {
        val invalid = listOf(reply(sid = generation), reply(gen = session), reply(mode = "null"), reply(mode = "\"EVERYTHING\""),
            reply(credit = "\"1000\""), reply(credit = "1.5"), reply(credit = "0"), reply(credit = "-1"), reply(credit = "28800001"),
            reply("REVOKED"), reply().dropLast(1) + ",\"deadlineEpochMillis\":100000}", reply().dropLast(1) + ",\"actorId\":\"$session\"}")
        for (body in invalid) {
            val http = client {respond(body)}; val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100})
            try {assertEquals(ChannelSharingFailure.INVALID_RESPONSE, assertIs<ChannelSharingReply.Failed>(port.status(tag)).reason, body)}
            finally {port.close(); http.close()}
        }
        val http = client {respond(reply())}; val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100})
        try {assertEquals(ChannelSharingFailure.INVALID_RESPONSE, assertIs<ChannelSharingReply.Failed>(port.revoke(tag)).reason)}
        finally {port.close(); http.close()}
    }

    @Test fun delayedExpiredOverflowAndBackwardClockNeverRenewConsentOnReceipt() = runTest {
        for ((start, receipt, failure) in listOf(Triple(100L, 1100L, ChannelSharingFailure.EXPIRED),
            Triple(100L, 99L, ChannelSharingFailure.INVALID_RESPONSE), Triple(Long.MAX_VALUE - 100, Long.MAX_VALUE - 50, ChannelSharingFailure.INVALID_RESPONSE))) {
            var clock = start; val http = client {clock = receipt; respond(reply())}
            val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"token"}, {clock})
            try {assertEquals(failure, assertIs<ChannelSharingReply.Failed>(port.status(tag)).reason)}
            finally {port.close(); http.close()}
        }
    }

    @Test fun cancelledBearerAndClosedChildIssueNoHttpAndLeaveBorrowedParentAlive() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var requests = 0
        val http = client {requests++; respond(reply())}
        val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {
            withContext(NonCancellable) {entered.complete(Unit); release.await()}; "token"
        }, {100})
        try {
            val pending = async {port.status(tag)}; runCurrent(); assertTrue(entered.isCompleted)
            pending.cancel(); release.complete(Unit); runCurrent(); assertTrue(pending.isCancelled); assertEquals(0, requests)
            port.close(); assertEquals(ChannelSharingFailure.RETIRED, assertIs<ChannelSharingReply.Failed>(port.revoke(tag)).reason)
            assertEquals(0, requests); assertEquals(HttpStatusCode.OK, http.get("https://api.test/parent").status); assertEquals(1, requests)
        } finally {release.complete(Unit); port.close(); http.close()}
    }

    @Test fun oversizedInvalidUtf8AndRedirectResponsesCannotBecomePositiveEvidence() = runTest {
        for (kind in 0..2) {
            var requests = 0
            val http = client {requests++; when(kind) {
                0 -> respond(reply().dropLast(1) + ",\"padding\":\"" + "x".repeat(8192) + "\"}")
                1 -> respond(byteArrayOf(0xC3.toByte(), 0x28))
                else -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test"))
            }}
            val port = ChannelSharingHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100})
            try {
                assertEquals(if (kind == 2) ChannelSharingFailure.UNAVAILABLE else ChannelSharingFailure.INVALID_RESPONSE,
                    assertIs<ChannelSharingReply.Failed>(port.status(tag)).reason, "Response kind $kind")
                assertEquals(1, requests)
            } finally {port.close(); http.close()}
        }
    }
}

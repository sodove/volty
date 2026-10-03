package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRosterHttpPortTest {
    private val channel = "10000000-0000-0000-0000-000000000001"
    private val actor = "20000000-0000-0000-0000-000000000002"
    private val session = "30000000-0000-0000-0000-000000000003"
    private val operation = "40000000-0000-0000-0000-000000000004"
    private val other = "50000000-0000-0000-0000-000000000005"
    private val tag = VoiceRosterTag(channel, 7)
    private fun peer(deadline: Long = 101000, phase: String = "CONNECTED", media: String = "CONNECTED", sid: String = "\"PA_live\"") =
        """{"actorId":"$actor","sessionId":"$session","operationId":"$operation","phase":"$phase","deadlineEpochMillis":$deadline,"mediaPresence":"$media","participantSid":$sid}"""
    private fun snapshot(vararg peers: String, parent: String = channel) = """{"channelId":"$parent","revision":8,"serverTimeEpochMillis":100000,"peers":[${peers.joinToString(",")}]}"""
    private fun TestScope.client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }
    }
    private fun port(client: HttpClient, clock: () -> Long = { 100 }) = VoiceRosterHttpPort(client, "https://example.test/v2/nearby", { "bearer-secret" }, clock)
    @Test fun authoritativeSnapshotCarriesCallerTagAndConservativeRequestStartCredit() = runTest {
        val requests = mutableListOf<HttpRequestData>(); var now = 100L
        val client = client { requests += it; now = 180; respond(snapshot(peer())) }; val port = port(client) { now }
        try {
            val observation = assertIs<VoiceRosterSnapshotReply.Received>(port.snapshot(tag)).observation
            assertEquals(tag, observation.update.tag); assertEquals(8, observation.update.revision)
            assertEquals(100000, observation.serverTimeEpochMillis); assertEquals(100, observation.requestStartedMillis)
            assertEquals(180, observation.responseReceivedMillis)
            val row = observation.update.peers.single(); assertEquals(actor, row.actorId); assertTrue(row.transportConfirmed)
            assertEquals(1100, observation.peerDeadlines.getValue(row.sessionId))
            assertTrue(observation.leaseIsFresh(row.sessionId, 1099)); assertFalse(observation.leaseIsFresh(row.sessionId, 1100))
            assertFalse(observation.leaseIsFresh(row.sessionId, 179))
            assertEquals(1, requests.size); assertEquals(HttpMethod.Get, requests.single().method)
            assertEquals("/v2/nearby/channels/$channel/roster", requests.single().url.encodedPath)
            assertEquals("Bearer bearer-secret", requests.single().headers[HttpHeaders.Authorization])
        } finally { port.close(); client.close() }
    }
    @Test fun expiredAndUnconfirmedRowsNeverEarnFreshOrAudiblePresence() = runTest {
        val client = client { respond(snapshot(peer(99999), peer(101000, "JOINING", "UNKNOWN", "null").replace(actor, other).replace(session, operation))) }; val port = port(client)
        try {
            val observation = assertIs<VoiceRosterSnapshotReply.Received>(port.snapshot(tag)).observation
            assertEquals(2, observation.update.peers.size)
            assertFalse(observation.leaseIsFresh(VoiceSessionId(session), 100))
            val joining = observation.update.peers.last(); assertFalse(joining.transportConfirmed); assertEquals(VoiceRosterPhase.JOINING, joining.phase)
            assertTrue(observation.leaseIsFresh(joining.sessionId, 100))
        } finally { port.close(); client.close() }
    }
    @Test fun foreignDuplicateMalformedOrContradictorySnapshotCannotProduceCurrentRows() = runTest {
        val bodies = listOf(snapshot(peer(), parent = other), snapshot(peer(), peer().replace(session, other)), snapshot(peer(), peer().replace(actor, other)),
            snapshot(peer(sid = "null")), snapshot(peer(phase = "CONNECTING")), snapshot(peer(media = "UNKNOWN")),
            snapshot(peer().replace("\"deadlineEpochMillis\":101000", "\"deadlineEpochMillis\":\"101000\"")),
            snapshot(peer()).replace("\"revision\":8", "\"revision\":\"8\""), snapshot(peer()).replace("\"serverTimeEpochMillis\":100000", "\"serverTimeEpochMillis\":-1"),
            snapshot(peer().replace(actor, "bad")), snapshot(peer().replace("PA_live", " ")), snapshot(peer(deadline = 130001)))
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(bodies.size) { assertEquals(VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag)) }
            assertEquals(bodies.size, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun delayedResponseRollbackAndOverflowCannotRefreshPeerLease() = runTest {
        var issued = 0; var now = 100L
        val client = client { now = when (issued++) { 0 -> 2100; 1 -> 99; else -> Long.MAX_VALUE - 1 }; respond(snapshot(peer())) }; val port = port(client) { now }
        try {
            val late = assertIs<VoiceRosterSnapshotReply.Received>(port.snapshot(tag)).observation
            assertFalse(late.leaseIsFresh(VoiceSessionId(session), late.responseReceivedMillis))
            now = 100; assertEquals(VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag))
            now = Long.MAX_VALUE - 2; assertEquals(VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag))
            assertEquals(3, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun concurrentRepliesKeepIncreasingOrdinalsEvenAtEqualClockTicks() = runTest {
        val firstEntered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var issued = 0
        val client = client { if (issued++ == 0) { firstEntered.complete(Unit); release.await() }; respond(snapshot(peer())) }; val port = port(client)
        try {
            val first = async { port.snapshot(tag) }; firstEntered.await()
            val second = assertIs<VoiceRosterSnapshotReply.Received>(port.snapshot(tag)).observation
            release.complete(Unit); val original = assertIs<VoiceRosterSnapshotReply.Received>(first.await()).observation
            assertEquals(0, original.requestId.value); assertEquals(1, second.requestId.value)
            assertEquals(original.requestStartedMillis, second.requestStartedMillis); assertEquals(2, issued)
        } finally { release.complete(Unit); port.close(); client.close() }
    }
    @Test fun boundedAndMalformedUtf8ResponsesAreRejectedBeforeSnapshot() = runTest {
        val malformed = (snapshot(peer()).dropLast(1) + ",\"ignored\":\"").encodeToByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".encodeToByteArray()
        val bodies = listOf(ByteArray(256 * 1024 + 1) { 120 }, malformed)
        var issued = 0; val client = client { respond(bodies[issued++]) }; val port = port(client)
        try {
            repeat(2) { assertEquals(VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag)) }
            assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun deniedRedirectAndExceptionHaveSafeTypedResultsWithoutRetry() = runTest {
        var issued = 0; val client = client {
            when (issued++) {
                0 -> respond("bearer-secret", HttpStatusCode.Unauthorized)
                1 -> respond("bearer-secret", HttpStatusCode.Forbidden)
                2 -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.test"))
                else -> throw IllegalStateException("bearer-secret")
            }
        }; val port = port(client)
        try {
            val replies = List(4) { assertIs<VoiceRosterSnapshotReply.Failed>(port.snapshot(tag)) }
            assertEquals(listOf(VoiceRosterFailure.UNAUTHORIZED, VoiceRosterFailure.ACCESS_DENIED, VoiceRosterFailure.UNAVAILABLE, VoiceRosterFailure.UNAVAILABLE), replies.map { it.reason })
            assertTrue(replies.none { it.toString().contains("bearer-secret") }); assertEquals(4, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun issuedTimeoutAndCancellationHaveFiniteLifetimeAndNoRetry() = runTest {
        var issued = 0; val entered = CompletableDeferred<Unit>(); var cancelled = false
        val client = client { if (issued++ == 0) { delay(10001); respond(snapshot(peer())) } else { entered.complete(Unit); try { awaitCancellation() } finally { cancelled = true } } }; val port = port(client)
        try {
            assertEquals(VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE), port.snapshot(tag))
            val job = launch { port.snapshot(tag) }; entered.await(); job.cancelAndJoin(); runCurrent()
            assertTrue(cancelled); assertEquals(2, issued)
        } finally { port.close(); client.close() }
    }
    @Test fun emptyQueryAndFragmentDelimitersCannotRedirectRosterPathIntoUrlMetadata() = runTest {
        val client = client { error("No request allowed") }; var candidate: VoiceRosterHttpPort? = null
        try {
            for (base in listOf("https://example.test/v2/nearby?", "https://example.test/v2/nearby#",
                "https://example.test/v2/nearby?q=x", "https://example.test/v2/nearby#x", "http://example.test/v2/nearby")) {
                assertFailsWith<IllegalArgumentException> { candidate = VoiceRosterHttpPort(client, base, { "secret" }, { 100 }) }
            }
        } finally { candidate?.close(); client.close() }
    }
}

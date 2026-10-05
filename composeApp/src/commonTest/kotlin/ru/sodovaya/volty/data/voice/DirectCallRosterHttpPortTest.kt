package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.get
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DirectCallRosterHttpPortTest {
    private val call = "10000000-0000-0000-0000-000000000001"
    private val actor = "20000000-0000-0000-0000-000000000002"
    private val peer = "30000000-0000-0000-0000-000000000003"
    private val sid = "40000000-0000-0000-0000-000000000004"
    private val otherSid = "50000000-0000-0000-0000-000000000005"
    private val tag = DirectCallRosterTag(call, peer, 7)
    private fun row(owner: String, session: String, phase: String = "CONNECTING", media: String = "UNKNOWN", participant: String = "null") =
        """{"actorId":"$owner","sessionId":"$session","phase":"$phase","deadlineEpochMillis":101000,"mediaPresence":"$media","participantSid":$participant}"""
    private fun body(first: String = row(actor, sid), second: String = row(peer, otherSid, "CONNECTED", "CONNECTED", "\"actual-sid\"")) =
        """{"callId":"$call","revision":8,"serverTimeEpochMillis":100000,"peers":[$first,$second]}"""
    private fun TestScope.client(handler: MockRequestHandler) = HttpClient(MockEngine) {engine {
        dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler)
    }}
    private fun port(client: HttpClient, clock: () -> Long = {100}) =
        DirectCallRosterHttpPort(client, "https://api.test/v2/nearby", actor, {"original-bearer"}, clock)

    @Test fun exactPairHasOnlyActualTransportEvidenceAndConservativeCredit() = runTest {
        var clock = 100L; val issued = mutableListOf<String>()
        val client = client {
            assertEquals("Bearer original-bearer", it.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Get, it.method); issued += it.url.encodedPath
            clock = 180; respond(body())
        }
        val port = port(client) {clock}
        try {
            val observation = assertIs<DirectCallRosterReply.Received>(port.snapshot(tag)).observation
            assertEquals(tag, observation.tag); assertEquals(8, observation.revision)
            assertEquals(setOf(actor, peer), observation.peers.map {it.actorId}.toSet())
            assertFalse(observation.peers.single {it.actorId == actor}.transportConfirmed)
            assertTrue(observation.peers.single {it.actorId == peer}.transportConfirmed)
            assertEquals(100, observation.requestStartedMillis); assertEquals(180, observation.responseReceivedMillis)
            assertEquals(1100, observation.leaseDeadlineMillis)
            assertFalse(observation.leaseIsFresh(179)); assertTrue(observation.leaseIsFresh(1099)); assertFalse(observation.leaseIsFresh(1100))
            assertEquals(listOf("/v2/nearby/calls/$call/roster"), issued)
        } finally {port.close(); client.close()}
    }

    @Test fun incoherentForeignOrPrivateNonceSnapshotsNeverBecomePairEvidence() = runTest {
        val bodies = listOf(body().replace(call, actor), body().replace(peer, call), body(row(actor, sid), row(actor, otherSid)),
            body(row(actor, sid), row(peer, sid)), body().replace("\"revision\":8", "\"revision\":\"8\""),
            body().replace("101000", "130001"), body().replace("\"actual-sid\"", "null"),
            body().replace("\"mediaPresence\":\"CONNECTED\"", "\"mediaPresence\":\"UNKNOWN\""),
            body().replace("\"phase\":\"CONNECTING\"", "\"phase\":\"CONNECTED\""),
            body(row(actor, sid).replace("101000", "100999")),
            body().replace("\"revision\":8", "\"revision\":8,\"operationId\":\"$sid\""))
        var index = 0; val client = client {respond(bodies[index++])}; val port = port(client)
        try {
            repeat(bodies.size) {assertEquals(DirectCallRosterReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag))}
            assertEquals(bodies.size, index)
        } finally {port.close(); client.close()}
    }

    @Test fun elapsedResponseNeverRebasesOriginalPairCreditOnReceipt() = runTest {
        var clock = 100L; val client = client {clock = 2000; respond(body())}; val port = port(client) {clock}
        try {
            val observation = assertIs<DirectCallRosterReply.Received>(port.snapshot(tag)).observation
            assertEquals(1100, observation.leaseDeadlineMillis); assertFalse(observation.leaseIsFresh(2000))
        } finally {port.close(); client.close()}
    }

    @Test fun denialAndRedirectAreNotRosterOrFollowingRequests() = runTest {
        val statuses = listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound, HttpStatusCode.Found)
        var issued = 0; val client = client {respond("{}", statuses[issued++], headersOf(HttpHeaders.Location, "https://elsewhere.test"))}
        val port = port(client)
        try {
            assertEquals(DirectCallRosterReply.Failed(VoiceRosterFailure.UNAUTHORIZED), port.snapshot(tag))
            assertEquals(DirectCallRosterReply.Failed(VoiceRosterFailure.ACCESS_DENIED), port.snapshot(tag))
            assertEquals(DirectCallRosterReply.Failed(VoiceRosterFailure.UNAVAILABLE), port.snapshot(tag)); assertEquals(3, issued)
        } finally {port.close(); client.close()}
    }

    @Test fun wrongPeerSelfAndCancelledRequestCannotIssueHttp() = runTest {
        var issued = 0; val client = client {issued++; respond(body())}; val port = port(client)
        try {
            assertFailsWith<IllegalArgumentException> {port.snapshot(tag.copy(peerId = actor))}
            val cancelled = Job().apply {cancel()}
            val request = CoroutineScope(coroutineContext + cancelled).async {port.snapshot(tag)}
            assertFailsWith<CancellationException> {request.await()}; assertEquals(0, issued)
        } finally {port.close(); client.close()}
    }

    @Test fun cancellationAfterBearerEntryCannotIssueAndClosingChildPreservesParentClient() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var issued = 0
        val client = client {issued++; respond(body())}
        val port = DirectCallRosterHttpPort(client, "https://api.test/v2/nearby", actor,
            {entered.complete(Unit); release.await(); "bearer"}, {100})
        try {
            val request = async {port.snapshot(tag)}; runCurrent(); assertTrue(entered.isCompleted)
            request.cancel(); release.complete(Unit)
            assertFailsWith<CancellationException> {request.await()}; assertEquals(0, issued)
            port.close(); client.get("https://api.test/parent-alive"); assertEquals(1, issued)
        } finally {release.complete(Unit); port.close(); client.close()}
    }

    @Test fun reversedCompletionRetainsOriginalRequestOrdinalAndDeadline() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var issued = 0; var clock = 100L
        val client = client {
            if (++issued == 1) {entered.complete(Unit); release.await()}
            respond(body())
        }
        val port = port(client) {clock}
        try {
            val older = async {port.snapshot(tag)}; runCurrent(); assertTrue(entered.isCompleted)
            clock = 200
            val newer = assertIs<DirectCallRosterReply.Received>(port.snapshot(tag)).observation
            assertFalse(older.isCompleted)
            clock = 300; release.complete(Unit)
            val previous = assertIs<DirectCallRosterReply.Received>(older.await()).observation
            assertTrue(previous.requestId.value < newer.requestId.value)
            assertEquals(1100, previous.leaseDeadlineMillis); assertEquals(1200, newer.leaseDeadlineMillis)
            assertEquals(2, issued)
        } finally {release.complete(Unit); port.close(); client.close()}
    }

    @Test fun oversizedOrInvalidUtf8BodyIsNotPairEvidence() = runTest {
        var issued = 0
        val client = client {if (++issued == 1) respond(" ".repeat(65537)) else respond(byteArrayOf(0xC3.toByte(), 0x28))}
        val port = port(client)
        try {
            repeat(2) {assertEquals(DirectCallRosterReply.Failed(VoiceRosterFailure.INVALID_RESPONSE), port.snapshot(tag))}
            assertEquals(2, issued)
        } finally {port.close(); client.close()}
    }
}

package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.*
import ru.sodovaya.volty.data.nearby.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class VoiceCrossPortLeaseSequenceTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val operation = VoiceOperationId("40000000-0000-0000-0000-000000000004")
    private val session = VoiceSessionId("50000000-0000-0000-0000-000000000005")
    private val generation = VoiceGeneration(1)

    @Test fun actualCallGrantAndSessionHeartbeatAdvanceOneSequenceAndReducerLease() = runTest {
        var clock = 100L; val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                respond(if (it.url.encodedPath.contains("/calls/"))
                    """{"call":{"callId":"$call","callerId":"$actor","calleeId":"$peer","phase":"ACCEPTED","revision":1,"ringDeadlineEpochMillis":45100},"session":{"phase":"CONNECTING","sessionId":"${session.value}","remainingLeaseMillis":5000,"credentials":{"serverUrl":"wss://media.test","participantToken":"own-token","roomEpoch":"volty-call-$call"}}}"""
                else """{"phase":"CONNECTED","sessionId":"${session.value}","remainingLeaseMillis":10000}""")
            }
        }}
        val sequence = VoiceLeaseRequestSequence()
        val calls = DirectCallHttpPort(actor, http, "https://api.test/v2/nearby", {"token"}, {clock}, sequence)
        val sessions = VoiceSessionHttpPort(http, "https://api.test/v2/nearby", {"token"}, {clock}, sequence)
        try {
            val grant = assertIs<VoiceHttpReply.Credentials>(assertIs<DirectCallReply.Ready>(calls.resume(call, operation, generation)).session)
            val joining = VoiceSessionReducer.reduce(VoiceSessionState(), VoiceSessionEvent.Join(operation)).state
            val active = VoiceSessionReducer.reduce(joining, VoiceSessionEvent.SessionGranted(operation, generation, session, grant.lease)).state
            clock = 200
            val ack = assertIs<VoiceHttpReply.Acknowledged>(sessions.heartbeat(grant.tag))
            assertEquals(listOf("/v2/nearby/calls/$call/resume", "/v2/nearby/sessions/${session.value}/heartbeat"), issued)
            assertTrue(ack.lease.requestId.value > grant.lease.requestId.value)
            val renewed = VoiceSessionReducer.reduce(active, VoiceSessionEvent.LeaseAck(ack.tag, ack.lease)).state
            assertEquals(10_200L, renewed.leaseDeadlineMillis)
            assertEquals(ack.lease.requestId, renewed.lastLeaseRequestId)
        } finally {calls.close(); sessions.close(); http.close()}
    }

    @Test fun equalClockTicksAndReversedHttpCompletionKeepIssuanceOrder() = runTest {
        val release = CompletableDeferred<Unit>(); var issued = 0
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued++
                if (issued == 1) release.await()
                respond("""{"phase":"CONNECTED","sessionId":"${session.value}","remainingLeaseMillis":10000}""")
            }
        }}
        val sequence = VoiceLeaseRequestSequence()
        val first = VoiceSessionHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100}, sequence)
        val second = VoiceSessionHttpPort(http, "https://api.test/v2/nearby", {"token"}, {100}, sequence)
        try {
            val held = async {first.status(operation, generation)}
            runCurrent(); assertEquals(1, issued)
            val finished = assertIs<VoiceHttpReply.Acknowledged>(second.status(operation, generation))
            assertEquals(2, issued); assertFalse(held.isCompleted)
            release.complete(Unit)
            val earlier = assertIs<VoiceHttpReply.Acknowledged>(held.await())
            assertEquals(earlier.lease.requestStartedMillis, finished.lease.requestStartedMillis)
            assertTrue(earlier.lease.requestId.value < finished.lease.requestId.value)
        } finally {release.complete(Unit); first.close(); second.close(); http.close(); runCurrent()}
    }
}

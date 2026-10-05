package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.nearby.*
import ru.sodovaya.volty.data.social.SocialAuthenticatedAccount
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

class DirectCallVoiceSessionControlTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val key = "40000000-0000-0000-0000-000000000004"
    private val operation = VoiceOperationId("50000000-0000-0000-0000-000000000005")
    private val sid = VoiceSessionId("60000000-0000-0000-0000-000000000006")
    private val generation = VoiceGeneration(5)
    private val base = "https://api.test/v2/nearby"
    private fun snapshot(phase: String, incoming: Boolean = false, outcome: String? = null) =
        """{"callId":"$call","callerId":"${if (incoming) peer else actor}","calleeId":"${if (incoming) actor else peer}","phase":"$phase","revision":7,"ringDeadlineEpochMillis":45100,"outcome":${outcome?.let {"\"$it\""} ?: "null"}}"""
    private fun credentials() = """{"phase":"CONNECTING","sessionId":"${sid.value}","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://media.test","participantToken":"own-private-token","roomEpoch":"volty-call-$call"}}"""
    private fun ended(cleanup: Boolean) = """{"phase":"CANCELLED","operationId":"${operation.value}","sessionId":"${sid.value}","cleanupPending":$cleanup}"""
    private fun TestScope.account(job: Job, root: Job) = SocialAuthenticatedAccount(SocialUserId(actor), job,
        root + StandardTestDispatcher(testScheduler)) {"original-account-token"}

    @Test fun callerRingsThenResumesOriginalAcceptedCallAndMaintainsEarnedSession() = runTest {
        val root = Job(); val job = Job(root); var accepted = false; var clock = 100L
        val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("Bearer original-account-token", it.headers[HttpHeaders.Authorization])
                issued += it.url.encodedPath
                val path = it.url.encodedPath
                respond(when {
                    path.endsWith("create") -> {
                        val body = (it.body as TextContent).text
                        for (original in listOf(peer, key, operation.value)) assertTrue(body.contains(original))
                        """{"call":${snapshot(if (accepted) "ACCEPTED" else "RINGING")}}"""
                    }
                    path.endsWith("resume") -> """{"call":${snapshot("ACCEPTED")},"session":${credentials()}}"""
                    path.endsWith("heartbeat") -> """{"phase":"CONNECTED","sessionId":"${sid.value}","remainingLeaseMillis":12000}"""
                    path.endsWith("cancel-outgoing") -> """{"call":${snapshot("ENDED", outcome = "CALLER_ENDED")}}"""
                    else -> ended(false)
                })
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {clock})
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            port.rememberJoinIntent(operation, generation, call, false)
            assertIs<VoiceHttpReply.Pending>(port.join(operation, generation, call))
            assertEquals(listOf("/v2/nearby/calls/$call/create"), issued)
            accepted = true; clock = 200
            val grant = assertIs<VoiceHttpReply.Credentials>(port.join(operation, generation, call))
            clock = 300
            val ack = assertIs<VoiceHttpReply.Acknowledged>(port.heartbeat(grant.tag))
            assertTrue(ack.lease.requestId.value > grant.lease.requestId.value)
            assertEquals(listOf("/v2/nearby/calls/$call/create", "/v2/nearby/calls/$call/create",
                "/v2/nearby/calls/$call/resume", "/v2/nearby/sessions/${sid.value}/heartbeat"), issued)
            job.cancel(); port.retireAdmission()
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(port.cancel(operation, generation)).cleanupPending)
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing", "/v2/nearby/operations/${operation.value}/cancel"), issued.takeLast(2))
            assertFalse(issued.any {it.endsWith("/join")})
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun calleeUsesOriginalExplicitRevisionAndKeepsUnconfirmedJournalCleanup() = runTest {
        val root = Job(); val job = Job(root); val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                respond(when {
                    it.url.encodedPath.endsWith("incoming") -> """{"calls":[${snapshot("RINGING", true)}]}"""
                    it.url.encodedPath.endsWith("accept") -> {
                        val body = (it.body as TextContent).text
                        assertTrue(body.contains(operation.value)); assertTrue(body.contains("\"expectedRevision\":7"))
                        """{"call":${snapshot("ACCEPTED", true)},"session":${credentials()}}"""
                    }
                    it.url.encodedPath.endsWith("stop") -> """{"call":${snapshot("ENDED", true, "CALLEE_ENDED")}}"""
                    else -> ended(true)
                })
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        try {
            val ring = assertIs<IncomingCallReply.Ready>(port.incoming()).calls.single()
            assertTrue(port.registerIncoming(ring, operation))
            port.rememberJoinIntent(operation, generation, call, false)
            assertIs<VoiceHttpReply.Credentials>(port.join(operation, generation, call))
            assertEquals(listOf("/v2/nearby/calls/incoming", "/v2/nearby/calls/$call/accept"), issued)
            job.cancel(); port.retireAdmission()
            assertEquals(true, assertIs<VoiceHttpReply.Ended>(port.cancel(operation, generation)).cleanupPending)
            assertEquals(listOf("/v2/nearby/calls/$call/stop", "/v2/nearby/operations/${operation.value}/cancel"), issued.takeLast(2))
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun parentTerminalAloneNeverAcknowledgesUnknownJournalCleanup() = runTest {
        val root = Job(); val job = Job(root); val issued = mutableListOf<String>(); var unavailable = true
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                when {
                    it.url.encodedPath.endsWith("cancel-outgoing") -> respond("""{"call":${snapshot("CANCELLED", outcome = "CALLER_CANCELLED")}}""")
                    unavailable -> respond("""{"code":"nearby_unavailable"}""", HttpStatusCode.ServiceUnavailable)
                    else -> respond(ended(false))
                }
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            port.rememberJoinIntent(operation, generation, call, false)
            assertEquals(VoiceHttpFailure.UNAVAILABLE, assertIs<VoiceHttpReply.Failed>(port.cancel(operation, generation)).reason)
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing", "/v2/nearby/operations/${operation.value}/cancel"), issued)
            unavailable = false
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(port.cancel(operation, generation)).cleanupPending)
            assertEquals(4, issued.size)
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun wrongTargetOperationGenerationAndReplaceNeverReachHttp() = runTest {
        val root = Job(); val job = Job(root); var issued = 0
        val http = HttpClient(MockEngine) {engine {dispatcher = StandardTestDispatcher(testScheduler); addHandler {issued++; respond("", HttpStatusCode.Unauthorized)}}}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            port.rememberJoinIntent(operation, generation, peer, false)
            assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(port.join(operation, generation, call)).reason)
            port.rememberJoinIntent(operation, generation, call, false)
            data class Join(val operation: VoiceOperationId, val generation: VoiceGeneration, val target: String, val replace: Boolean = false)
            for (request in listOf(Join(VoiceOperationId(key), generation, call), Join(operation, VoiceGeneration(6), call),
                Join(operation, generation, peer), Join(operation, generation, call, true)))
                assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(port.join(
                    request.operation, request.generation, request.target, request.replace)).reason)
            assertEquals(0, issued)
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun failedParentStillAttemptsOriginalJournalButNeverAcknowledgesWholeStop() = runTest {
        val root = Job(); val job = Job(root); val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                if (it.url.encodedPath.endsWith("cancel-outgoing")) respond("""{"code":"nearby_unavailable"}""", HttpStatusCode.ServiceUnavailable)
                else respond(ended(false))
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            port.rememberJoinIntent(operation, generation, call, false)
            assertEquals(VoiceHttpFailure.UNAVAILABLE, assertIs<VoiceHttpReply.Failed>(port.cancel(operation, generation)).reason)
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing", "/v2/nearby/operations/${operation.value}/cancel"), issued)
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun onlyActualReturnedSessionTagCanDisconnectItsOriginalCall() = runTest {
        val root = Job(); val job = Job(root); val issued = mutableListOf<String>()
        val http = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued += it.url.encodedPath
                respond(when {
                    it.url.encodedPath.endsWith("create") -> """{"call":${snapshot("ACCEPTED")}}"""
                    it.url.encodedPath.endsWith("resume") -> """{"call":${snapshot("ACCEPTED")},"session":${credentials()}}"""
                    it.url.encodedPath.endsWith("cancel-outgoing") -> """{"call":${snapshot("ENDED", outcome = "CALLER_ENDED")}}"""
                    else -> ended(false)
                })
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            port.rememberJoinIntent(operation, generation, call, false)
            val grant = assertIs<VoiceHttpReply.Credentials>(port.join(operation, generation, call))
            assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(port.disconnect(grant.tag.copy(generation = VoiceGeneration(6)))).reason)
            assertEquals(VoiceHttpFailure.ACCESS_DENIED, assertIs<VoiceHttpReply.Failed>(port.disconnect(grant.tag.copy(sessionId = VoiceSessionId(peer)))).reason)
            assertEquals(2, issued.size)
            port.retireAdmission(); job.cancel()
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(port.disconnect(grant.tag)).cleanupPending)
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing", "/v2/nearby/sessions/${sid.value}/disconnect"), issued.takeLast(2))
        } finally {port.close(); http.close(); root.cancel()}
    }

    @Test fun actualVoiceMailboxWaitsThroughRingAndIssuesOneEngineStartAfterAcceptance() = runTest {
        val root = Job(); val job = Job(root); var accepted = false
        val dispatcher = StandardTestDispatcher(testScheduler); val issued = mutableListOf<String>(); val effects = mutableListOf<VoiceControlDispatch>()
        val http = HttpClient(MockEngine) {engine {
            this.dispatcher = dispatcher
            addHandler {
                issued += it.url.encodedPath
                respond(when {
                    it.url.encodedPath.endsWith("create") -> """{"call":${snapshot(if (accepted) "ACCEPTED" else "RINGING")}}"""
                    it.url.encodedPath.endsWith("resume") -> """{"call":${snapshot("ACCEPTED")},"session":${credentials()}}"""
                    it.url.encodedPath.endsWith("cancel-outgoing") -> """{"call":${snapshot("ENDED", outcome = "CALLER_ENDED")}}"""
                    else -> ended(false)
                })
            }
        }}
        val port = DirectCallVoiceSessionControl.create(account(job, root), http, base, {100})
        val owner = VoiceControlOwner(CoroutineScope(root + dispatcher), port, effects::add)
        try {
            assertTrue(port.registerOutgoing(call, peer, key, operation))
            owner.join(call, operation); runCurrent()
            assertEquals(listOf("/v2/nearby/calls/$call/create"), issued)
            assertTrue(owner.state.value.wanted); assertNull(owner.state.value.sessionId)
            assertFalse(effects.any {it.effect is VoiceSessionEffect.StartEngine})
            accepted = true
            owner.retryJoin(); runCurrent()
            assertEquals(sid, owner.state.value.sessionId)
            assertEquals(1, effects.count {it.effect is VoiceSessionEffect.StartEngine})
            owner.event(VoiceSessionEvent.EnableSharing()); runCurrent()
            assertFalse(owner.state.value.sharingEnabled)
            assertFalse(effects.any {it.effect == VoiceSessionEffect.AddNearbyDemand})
            owner.stop(); runCurrent()
            assertFalse(owner.state.value.wanted)
            assertTrue(owner.unconfirmedStops.value.isEmpty()); assertTrue(owner.pendingRemoteStops.value.isEmpty())
            assertEquals(listOf("/v2/nearby/calls/$call/cancel-outgoing", "/v2/nearby/sessions/${sid.value}/disconnect"), issued.takeLast(2))
        } finally {owner.stop(); runCurrent(); port.close(); http.close(); root.cancel(); runCurrent()}
    }
}

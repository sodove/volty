package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class DirectCallRoutesTest {
    private fun id() = UUID.randomUUID().toString()
    private val actor = id(); private val peer = id(); private val call = id(); private val key = id(); private val operation = id(); private val callerOperation = id()
    private fun dependencies(port: DirectCallEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf {id == actor}
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyCalls = port)

    @Test fun statusCarriesOnlyBoundedRingingCreditAndNeverAddsItToTerminalResponse() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor)
        port.statusRingCredit = 25_000
        val ring = client.get("/v2/nearby/calls/$call") {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK, ring.status)
        assertTrue(ring.bodyAsText().contains("\"remainingRingMillis\":25000"))
        assertFalse(ring.bodyAsText().contains(callerOperation)); assertFalse(ring.bodyAsText().contains(key))
        port.terminal = true
        val terminal = client.get("/v2/nearby/calls/$call") {bearerAuth(token)}
        assertEquals(HttpStatusCode.OK, terminal.status)
        assertFalse(terminal.bodyAsText().contains("\"remainingRingMillis\":25000"))
        port.terminal = false; port.statusRingCredit = 45_001
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v2/nearby/calls/$call") {bearerAuth(token)}.status)
    }

    @Test fun everyCallPathRequiresAuthenticationEvenWhenDisabled() = testApplication {
        application {module(dependencies())}
        for (action in listOf("create", "accept", "resume", "decline", "stop", "cancel-outgoing"))
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/nearby/calls/$call/$action").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/calls/$call").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/calls/incoming").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/calls/$call/roster").status)
    }

    @Test fun authenticatedDisabledCallsReturnUnavailableWithoutCredentials() = testApplication {
        val deps = dependencies(); application {module(deps)}
        val response = client.get("/v2/nearby/calls/incoming") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains("participantToken"))
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/v2/nearby/calls/$call/roster") {
            bearerAuth(deps.tokenService.issueAccessToken(actor))
        }.status)
    }

    @Test fun callRosterUsesLiveActorAndOmitsAllOriginalOperationsAndCredentials() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val response = client.get("/v2/nearby/calls/$call/roster?actorId=$peer") {
            bearerAuth(deps.tokenService.issueAccessToken(actor))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("roster:$actor:$call"), port.calls)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"callId\":\"$call\"")); assertFalse(body.contains("channelId"))
        assertTrue(body.contains(actor)); assertTrue(body.contains(peer)); assertTrue(body.contains("CONNECTED"))
        for (private in listOf(key, operation, callerOperation, "operationId", "participantToken", "nodeEpoch", "idempotencyKey"))
            assertFalse(body.contains(private))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test fun absentCallRosterIsNotFoundAndMalformedIdDoesNotReachPort() = testApplication {
        val port = RecordingPort().apply {rosterSnapshot = null}; val deps = dependencies(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor)
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/nearby/calls/$call/roster") {bearerAuth(token)}.status)
        assertEquals(listOf("roster:$actor:$call"), port.calls)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/nearby/calls/not-a-uuid/roster") {bearerAuth(token)}.status)
        assertEquals(1, port.calls.size)
    }

    @Test fun misboundOrIncoherentCallRosterNeverPublishesParticipantData() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val original = assertNotNull(port.rosterSnapshot)
        for (invalid in listOf(original.copy(callId = id()), original.copy(peers = original.peers.take(1)),
            original.copy(peers = listOf(original.peers.first(), original.peers.first())),
            original.copy(peers = original.peers.map {it.copy(actorId = id())}),
            original.copy(peers = original.peers.map {it.copy(deadlineEpochMillis = 30101)}),
            original.copy(peers = listOf(original.peers.first(), original.peers.last().copy(deadlineEpochMillis = 15099))),
            original.copy(peers = original.peers.map {it.copy(phase = RosterConnectionPhase.CONNECTED, participantSid = null)}))) {
            port.rosterSnapshot = invalid
            val response = client.get("/v2/nearby/calls/$call/roster") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertFalse(response.bodyAsText().contains("participantSid"))
        }
    }

    @Test fun creationBindsLiveActorAndOmitsPrivateOriginalCallerPayloadFromSnapshot() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val response = client.post("/v2/nearby/calls/$call/create") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"calleeId":"$peer","idempotencyKey":"$key","operationId":"$operation","actorId":"forged"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("create:$actor:$peer:$call:$key:$operation"), port.calls)
        val body = response.bodyAsText()
        assertTrue(body.contains("RINGING")); assertFalse(body.contains(key)); assertFalse(body.contains(callerOperation))
        assertFalse(body.contains("idempotencyKey")); assertFalse(body.contains("participantToken"))
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Test fun acceptAndResumeExposeOnlyOwnRegisteredCredentialsAndRemainingCredit() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for (action in listOf("accept", "resume")) {
            val response = client.post("/v2/nearby/calls/$call/$action") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"operationId":"$operation","expectedRevision":0,"roomEpoch":"forged"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("CONNECTING")); assertFalse(body.contains("CONNECTED"))
            assertTrue(body.contains("opaque-own-credential")); assertTrue(body.contains("\"remainingLeaseMillis\":12000"))
            assertFalse(body.contains(callerOperation)); assertFalse(body.contains(key)); assertFalse(body.contains("nodeEpoch"))
        }
        assertEquals(listOf("accept:$actor:$call:$operation:0", "resume:$actor:$call:$operation"), port.calls)
    }

    @Test fun statusAndIncomingNeverExposeCredentialsOrCallerNonces() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for (path in listOf("$call", "incoming?limit=2&after=$call")) {
            val response = client.get("/v2/nearby/calls/$path") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("participantToken")); assertFalse(body.contains(key)); assertFalse(body.contains(callerOperation))
        }
        assertEquals(listOf("status:$actor:$call", "incoming:$actor:2:$call"), port.calls)
    }

    @Test fun malformedIdsBoundsAndRevisionAreRejectedBeforeEndpointPort() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for ((path, body) in listOf(
            "bad/accept" to """{"operationId":"$operation","expectedRevision":0}""",
            "$call/accept" to """{"operationId":"bad","expectedRevision":0}""",
            "$call/accept" to """{"operationId":"$operation","expectedRevision":-1}""",
            "$call/create" to """{"calleeId":"$peer","operationId":"$operation","idempotencyKey":"bad"}""",
        )) {
            val response = client.post("/v2/nearby/calls/$path") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        for (query in listOf("limit=0", "limit=51", "limit=bad", "after=bad"))
            assertEquals(HttpStatusCode.BadRequest, client.get("/v2/nearby/calls/incoming?$query") {bearerAuth(deps.tokenService.issueAccessToken(actor))}.status)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun declineStopAndOutgoingCancellationPreserveExactActorAndOriginalBinding() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for (action in listOf("decline", "stop")) assertEquals(HttpStatusCode.OK,
            client.post("/v2/nearby/calls/$call/$action") {bearerAuth(deps.tokenService.issueAccessToken(actor))}.status)
        val response = client.post("/v2/nearby/calls/$call/cancel-outgoing") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"calleeId":"$peer","idempotencyKey":"$key","operationId":"$operation"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("decline:$actor:$call", "stop:$actor:$call", "cancel:$actor:$peer:$call:$key:$operation"), port.calls)
        assertFalse(response.bodyAsText().contains("participantToken"))
    }

    @Test fun terminalParentNeverSerializesStaleReadyEvenIfPortReturnedIt() = testApplication {
        val port = RecordingPort().apply {terminal = true; denial = DirectCallRejection.TERMINAL}
        val deps = dependencies(port); application {module(deps)}
        val response = client.post("/v2/nearby/calls/$call/resume") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$operation"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ENDED")); assertFalse(body.contains("opaque-own-credential"))
        assertFalse(body.contains("CONNECTING"))
    }

    @Test fun unavailableResponseNeverCarriesReadyCredentialEvenIfPortReturnedBoth() = testApplication {
        val port = RecordingPort().apply {failure = SfuFailure.UNAVAILABLE}
        val deps = dependencies(port); application {module(deps)}
        val response = client.post("/v2/nearby/calls/$call/accept") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"operationId":"$operation","expectedRevision":0}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("UNAVAILABLE")); assertFalse(body.contains("opaque-own-credential"))
    }

    @Test fun rejectionCodesRemainStableAndNeverReturnCredentials() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for ((rejection, status, code) in listOf(
            Triple(DirectCallRejection.ACCESS_DENIED, HttpStatusCode.NotFound, "call_unavailable"),
            Triple(DirectCallRejection.NOT_FRIENDS, HttpStatusCode.Forbidden, "friendship_required"),
            Triple(DirectCallRejection.IDENTITY_MISMATCH, HttpStatusCode.Conflict, "operation_conflict"),
            Triple(DirectCallRejection.CLAIM_BUSY, HttpStatusCode.Conflict, "voice_busy"),
            Triple(DirectCallRejection.REVISION_MISMATCH, HttpStatusCode.Conflict, "call_revision_conflict"),
            Triple(DirectCallRejection.NOT_RINGING, HttpStatusCode.Conflict, "call_not_ringing"),
        )) {
            port.denial = rejection
            val response = client.post("/v2/nearby/calls/$call/resume") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"operationId":"$operation"}""")
            }
            assertEquals(status, response.status)
            assertTrue(response.bodyAsText().contains(code))
            assertFalse(response.bodyAsText().contains("opaque-own-credential"))
        }
    }

    @Test fun onlyObservedActiveMediaReportsConnectedWithoutIssuingAnotherCredential() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        for (state in listOf(null, SfuParticipantState.JOINED, SfuParticipantState.ACTIVE)) {
            port.sessionOverride = SessionAction.Confirmed(id(), 30_100, 12_000,
                SessionMedia(participantSid = if (state == null) null else "observed-sid", state = state))
            val response = client.post("/v2/nearby/calls/$call/resume") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"operationId":"$operation"}""")
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains(if (state == SfuParticipantState.ACTIVE) "CONNECTED" else "CONNECTING"))
            assertFalse(response.bodyAsText().contains("participantToken"))
        }
    }

    private inner class RecordingPort : DirectCallEndpointPort {
        val calls = mutableListOf<String>()
        var denial: DirectCallRejection? = null
        var terminal = false
        var statusRingCredit: Long? = null
        var failure: SfuFailure? = null
        var sessionOverride: SessionAction? = null
        var rosterSnapshot: DirectCallRosterSnapshot? = DirectCallRosterSnapshot(call, 7, 100, listOf(
            ChannelRosterPeer(actor, id(), operation, RosterConnectionPhase.CONNECTING, 15100),
            ChannelRosterPeer(peer, id(), callerOperation, RosterConnectionPhase.CONNECTED, 15100, MediaPresence.CONNECTED, "observed-sid")))
        override fun roster(actor: String, call: String): DirectCallRosterSnapshot? {
            calls += "roster:$actor:$call"; return rosterSnapshot
        }
        private fun record(phase: DirectCallPhase = DirectCallPhase.RINGING) = DirectCallRecord(call, peer, actor, key, callerOperation,
            phase, 45_100, if (phase == DirectCallPhase.RINGING) 0 else 1,
            if (phase == DirectCallPhase.DECLINED) DirectCallOutcome.CALLEE_DECLINED else if (phase == DirectCallPhase.ENDED) DirectCallOutcome.CALLEE_ENDED else null)
        override fun create(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision {
            calls += "create:$actor:$callee:$call:$key:$operation"; return DirectCallDecision(record().copy(callerId = actor, calleeId = callee))
        }
        override fun status(actor: String, call: String): DirectCallDecision {calls += "status:$actor:$call"; return DirectCallDecision(record(if (terminal) DirectCallPhase.ENDED else DirectCallPhase.RINGING), remainingRingMillis = statusRingCredit)}
        override fun incoming(actor: String, limit: Int, cursor: String?): IncomingCallPage {calls += "incoming:$actor:$limit:$cursor"; return IncomingCallPage(listOf(record()), null)}
        override fun accept(actor: String, call: String, operation: String, revision: Long): DirectCallAction {calls += "accept:$actor:$call:$operation:$revision"; return ready()}
        override fun resume(actor: String, call: String, operation: String): DirectCallAction {calls += "resume:$actor:$call:$operation"; return ready()}
        override fun decline(actor: String, call: String): DirectCallDecision {calls += "decline:$actor:$call"; return DirectCallDecision(record(DirectCallPhase.DECLINED))}
        override fun stop(actor: String, call: String): DirectCallDecision {calls += "stop:$actor:$call"; return DirectCallDecision(record(DirectCallPhase.ENDED))}
        override fun cancelOutgoing(actor: String, callee: String, call: String, key: String, operation: String): DirectCallDecision {
            calls += "cancel:$actor:$callee:$call:$key:$operation"; return DirectCallDecision(record(DirectCallPhase.ENDED))
        }
        private fun ready() = DirectCallAction(DirectCallDecision(record(if (terminal) DirectCallPhase.ENDED else DirectCallPhase.ACCEPTED), denial),
            sessionOverride ?: SessionAction.Ready(SessionCredentials("wss://test", "opaque-own-credential", id(), "volty-call-$call", 30_100), 12_000), failure)
    }
}

package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class SessionRoutesTest {
    private val actor = UUID.randomUUID().toString()
    private val operation = UUID.randomUUID().toString()
    private val destination = UUID.randomUUID().toString()
    private val session = UUID.randomUUID().toString()
    private fun dependencies(port: SessionEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbySessions = port)

    @Test fun allSessionPathsRequireAuthenticationEvenWhenDisabled() = testApplication {
        application { module(dependencies()) }
        val paths = listOf("operations/$operation/join", "operations/$operation/cancel", "sessions/$session/heartbeat", "sessions/$session/disconnect")
        for (path in paths) assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/nearby/$path").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/operations/$operation").status)
    }

    @Test fun disabledDependencyReturnsUnavailableWithoutCredentials() = testApplication {
        val deps = dependencies()
        application { module(deps) }
        val response = client.post("/v2/nearby/operations/$operation/join") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"destinationId":"$destination"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("nearby_unavailable"))
        assertFalse(response.bodyAsText().contains("participantToken"))
    }

    @Test fun joinUsesAuthenticatedActorAndDoesNotClaimMediaConnected() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        val response = client.post("/v2/nearby/operations/$operation/join") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"destinationId":"$destination","actorId":"forged-account"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("join:$actor:$operation:$destination"), port.calls)
        assertFalse(port.replacementRequested)
        val body = response.bodyAsText()
        assertTrue(body.contains("CONNECTING"))
        assertTrue(body.contains("opaque-participant-credential"))
        assertFalse(body.contains("CONNECTED"))
        assertFalse(body.contains("nodeEpoch"))
        assertTrue(body.contains("\"remainingLeaseMillis\":12000"))
    }

    @Test fun replacementMustBeExplicitAndBusyHasItsOwnResponseCode() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        val response = client.post("/v2/nearby/operations/$operation/join") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"destinationId":"$destination","replace":true}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(port.replacementRequested)
        port.denial = SessionDenial.BUSY
        val busy = client.post("/v2/nearby/operations/$operation/join") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"destinationId":"$destination"}""")
        }
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertTrue(busy.bodyAsText().contains("voice_busy"))
        assertFalse(port.replacementRequested)
    }

    @Test fun arbitraryRoomNamesAndMalformedOperationIdsNeverReachPort() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        for ((op, dest) in listOf("bad-id" to destination, operation to "client-chosen-room")) {
            val response = client.post("/v2/nearby/operations/$op/join") {
                bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
                setBody("""{"destinationId":"$dest"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
        assertTrue(port.calls.isEmpty())
    }

    @Test fun statusHasNoCredentialsAndUnknownOperationIsNotFound() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        val response = client.get("/v2/nearby/operations/$operation") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertFalse(response.bodyAsText().contains("participantToken"))
        assertEquals(listOf("status:$actor:$operation"), port.calls)
        assertTrue(response.bodyAsText().contains("\"remainingLeaseMillis\":7000"))
        port.missing = true
        assertEquals(HttpStatusCode.NotFound, client.get("/v2/nearby/operations/$operation") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }.status)
    }

    @Test fun disconnectAndHeartbeatCarryAuthenticatedActorAndExactSession() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        for (action in listOf("heartbeat", "disconnect")) {
            assertEquals(HttpStatusCode.OK, client.post("/v2/nearby/sessions/$session/$action") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }.status)
        }
        assertEquals(listOf("heartbeat:$actor:$session", "disconnect:$actor:$session"), port.calls)
    }

    @Test fun onlyConfirmedActiveTransportEarnsConnectedPhaseAndNoStatusCredentials() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        for (state in listOf(null, SfuParticipantState.JOINED, SfuParticipantState.ACTIVE)) {
            port.media = if (state == null) SessionMedia() else SessionMedia("PA_current", state, 1)
            val response = client.post("/v2/nearby/sessions/$session/heartbeat") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"phase\":\"${if (state == SfuParticipantState.ACTIVE) "CONNECTED" else "CONNECTING"}\""))
            assertFalse(response.bodyAsText().contains("participantToken"))
            assertFalse(response.bodyAsText().contains("audible"))
        }
    }

    @Test fun cancellationUsesOperationWithoutNeedingSessionOrDestination() = testApplication {
        val port = RecordingPort()
        val deps = dependencies(port)
        application { module(deps) }
        val response = client.post("/v2/nearby/operations/$operation/cancel") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("CANCELLED"))
        assertEquals(listOf("cancel:$actor:$operation"), port.calls)
    }

    @Test fun terminalPhaseDoesNotFabricateRemoteCleanupCompletion() = testApplication {
        val port = RecordingPort().also { it.cleanupPending = true }
        val deps = dependencies(port)
        application { module(deps) }
        suspend fun cancel() = client.post("/v2/nearby/operations/$operation/cancel") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        val pending = cancel()
        assertEquals(HttpStatusCode.OK, pending.status)
        assertTrue(pending.bodyAsText().contains("\"cleanupPending\":true"))
        port.cleanupPending = false
        val completed = cancel()
        assertEquals(HttpStatusCode.OK, completed.status)
        assertTrue(completed.bodyAsText().contains("\"cleanupPending\":false"))
        assertEquals(listOf("cancel:$actor:$operation", "cancel:$actor:$operation"), port.calls)
    }

    private inner class RecordingPort : SessionEndpointPort {
        val calls = mutableListOf<String>()
        var missing = false
        var replacementRequested = false
        var denial: SessionDenial? = null
        var media = SessionMedia()
        var cleanupPending = false
        // Deliberately incoherent null session + pending cleanup: route must
        // project repository evidence, never guess completion from phase/ID.
        private fun ended() = SessionAction.Ended(SessionJournalEntry(SessionOperation(actor, operation, null, null, OperationPhase.CANCELLED, null), null, false, cleanupPending))
        override fun join(actor: String, operation: String, destination: String, replace: Boolean): SessionAction {
            calls += "join:$actor:$operation:$destination"
            replacementRequested = replace
            denial?.let { return SessionAction.Denied(it) }
            return SessionAction.Ready(SessionCredentials("wss://test.invalid", "opaque-participant-credential", session, "server-room-generation", 30000), 12_000)
        }
        override fun status(actor: String, operation: String): SessionAction? {
            calls += "status:$actor:$operation"
            return if (missing) null else SessionAction.Confirmed(session, 30000, 7_000, media)
        }
        override fun cancel(actor: String, operation: String): SessionAction { calls += "cancel:$actor:$operation"; return ended() }
        override fun heartbeat(actor: String, session: String): SessionAction? { calls += "heartbeat:$actor:$session"; return SessionAction.Confirmed(session, 30000, 7_000, media) }
        override fun disconnect(actor: String, session: String): SessionAction? { calls += "disconnect:$actor:$session"; return ended() }
    }
}

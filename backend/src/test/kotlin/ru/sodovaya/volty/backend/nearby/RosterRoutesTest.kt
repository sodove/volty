package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class RosterRoutesTest {
    private val actor = UUID.randomUUID().toString()
    private val channel = UUID.randomUUID().toString()
    private fun dependencies(port: RosterEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf { id == actor }
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyRoster = port)

    @Test fun confirmedSnapshotCarriesCurrentTransportSidWithoutImplyingDeviceAudio() = testApplication {
        val session = UUID.randomUUID().toString()
        val deps = dependencies(RosterEndpointPort { _, _ -> ChannelRosterSnapshot(channel, 7, 100,
            listOf(ChannelRosterPeer(actor, session, UUID.randomUUID().toString(), RosterConnectionPhase.CONNECTED,
                30_100, MediaPresence.CONNECTED, "PA_current"))) })
        application { module(deps) }
        val response = client.get("/v2/nearby/channels/$channel/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("\"participantSid\":\"PA_current\""))
        assertTrue(response.bodyAsText().contains("\"phase\":\"CONNECTED\""))
        assertFalse(response.bodyAsText().contains("audible"))
    }

    @Test fun authenticationAndDisabledBoundaryDoNotExposeRoster() = testApplication {
        val deps = dependencies()
        application { module(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v2/nearby/channels/$channel/roster").status)
        val disabled = client.get("/v2/nearby/channels/$channel/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, disabled.status)
        assertFalse(disabled.bodyAsText().contains("peers"))
    }

    @Test fun callerCannotChooseActorOrRoomAndMalformedChannelNeverReachesPort() = testApplication {
        val calls = mutableListOf<Pair<String, String>>()
        val deps = dependencies(RosterEndpointPort { owner, destination ->
            calls += owner to destination
            ChannelRosterSnapshot(destination, 7, 100, emptyList())
        })
        application { module(deps) }
        val response = client.get("/v2/nearby/channels/$channel/roster?actorId=forged&room=forged") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf(actor to channel), calls)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/nearby/channels/client-room/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }.status)
        assertEquals(1, calls.size)
    }

    @Test fun unknownOrForbiddenChannelHasNoSnapshot() = testApplication {
        val deps = dependencies(RosterEndpointPort { _, _ -> null })
        application { module(deps) }
        val response = client.get("/v2/nearby/channels/$channel/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("membership_required"))
        assertFalse(response.bodyAsText().contains("peers"))
    }

    @Test fun snapshotForAnotherChannelNeverLeaksPeers() = testApplication {
        val privateActor = UUID.randomUUID().toString()
        val privateChannel = UUID.randomUUID().toString()
        val deps = dependencies(RosterEndpointPort { _, _ ->
            ChannelRosterSnapshot(privateChannel, 10, 100, listOf(ChannelRosterPeer(privateActor, UUID.randomUUID().toString(), UUID.randomUUID().toString(), RosterConnectionPhase.CONNECTING, 30000)))
        })
        application { module(deps) }
        val response = client.get("/v2/nearby/channels/$channel/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains(privateActor))
        assertFalse(response.bodyAsText().contains(privateChannel))
    }

    @Test fun snapshotKeepsRevisionDeadlineAndUnknownMediaExplicit() = testApplication {
        val session = UUID.randomUUID().toString()
        val operation = UUID.randomUUID().toString()
        val deps = dependencies(RosterEndpointPort { _, destination ->
            ChannelRosterSnapshot(destination, 23, 100, listOf(ChannelRosterPeer(actor, session, operation, RosterConnectionPhase.CONNECTING, 30000)))
        })
        application { module(deps) }
        val response = client.get("/v2/nearby/channels/$channel/roster") { bearerAuth(deps.tokenService.issueAccessToken(actor)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"revision\":23"))
        assertTrue(body.contains("UNKNOWN"))
        assertTrue(body.contains("CONNECTING"))
        assertTrue(body.contains(session))
        assertTrue(body.contains("\"deadlineEpochMillis\":30000"))
        assertFalse(body.contains("CONNECTED"))
        assertFalse(body.contains("participantToken"))
        assertEquals("no-store", response.headers["Cache-Control"])
    }
}

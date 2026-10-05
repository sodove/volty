package ru.sodovaya.volty.backend.nearby

import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class ChannelSharingRoutesTest {
    private fun id() = UUID.randomUUID().toString()
    private val actor = id(); private val session = id(); private val generation = id()
    private val path = "/v2/nearby/sharing/$session/$generation"
    private fun dependencies(port: ChannelSharingEndpointPort? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf {id == actor}
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = userId == actor
    }, testMode = true, nearbyChannelSharing = port)

    @Test fun allConsentPathsRequireAuthenticationAndDisabledPortsAreUnavailable() = testApplication {
        val deps = dependencies(); application {module(deps)}
        assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
        for (action in listOf("start", "revoke")) assertEquals(HttpStatusCode.Unauthorized, client.post("$path/$action").status)
        val token = deps.tokenService.issueAccessToken(actor)
        assertEquals(HttpStatusCode.ServiceUnavailable, client.get(path) {bearerAuth(token)}.status)
        for (action in listOf("start", "revoke")) assertEquals(HttpStatusCode.ServiceUnavailable, client.post("$path/$action") {bearerAuth(token)}.status)
    }

    @Test fun startUsesAuthenticatedActorAndExplicitModeTtlWithoutExposingServerDeadline() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val response = client.post("$path/start") {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json)
            setBody("""{"mode":"RIDE","ttl":"TWO_HOURS"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("start:$actor:$session:$generation:RIDE:TWO_HOURS"), port.calls)
        val text = response.bodyAsText()
        assertTrue(text.contains("\"remainingMillis\":5000")); assertTrue(text.contains(session)); assertTrue(text.contains(generation))
        assertFalse(text.contains("deadlineEpochMillis")); assertFalse(text.contains(actor)); assertFalse(text.contains("latitude"))
    }

    @Test fun malformedPathsModesTtlAndOversizedBodiesNeverReachConsentPort() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v2/nearby/sharing/invalid/$generation") {bearerAuth(token)}.status)
        for (body in listOf("""{"mode":"ALL","ttl":"TWO_HOURS"}""", """{"mode":"LOCATION","ttl":"FOREVER"}""", "not json")) {
            assertEquals(HttpStatusCode.BadRequest, client.post("$path/start") {bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)}.status)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("$path/start") {bearerAuth(token); contentType(ContentType.Application.Json); setBody("x".repeat(16 * 1024 + 1))}.status)
        assertTrue(port.calls.isEmpty())
    }

    @Test fun unavailableOriginalConsentUsesGenericDenialAndNoPositiveFallback() = testApplication {
        val port = RecordingPort().apply {reply = null}; val deps = dependencies(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor)
        assertEquals(HttpStatusCode.NotFound, client.get(path) {bearerAuth(token)}.status)
        val start = client.post("$path/start") {bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"mode":"LOCATION","ttl":"HALF_HOUR"}""")}
        assertEquals(HttpStatusCode.NotFound, start.status)
        assertEquals(listOf("status:$actor:$session:$generation", "start:$actor:$session:$generation:LOCATION:HALF_HOUR"), port.calls)
        assertFalse(start.bodyAsText().contains("member"))
    }

    @Test fun originalRevokeDoesNotPerformMembershipOrActiveSessionStatusPreflight() = testApplication {
        val port = RecordingPort().apply {reply = ChannelSharingDecision(ChannelSharingConsent(actor, session, generation, ChannelSharingPhase.REVOKED, null, null), 0)}
        val deps = dependencies(port); application {module(deps)}
        val response = client.post("$path/revoke") {bearerAuth(deps.tokenService.issueAccessToken(actor))}
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(listOf("revoke:$actor:$session:$generation"), port.calls)
        assertTrue(response.bodyAsText().contains("REVOKED")); assertTrue(response.bodyAsText().contains("\"remainingMillis\":0"))
    }

    @Test fun deliberatelyMisboundOrIncoherentPortEvidenceNeverBecomesSuccess() = testApplication {
        val port = RecordingPort(); val deps = dependencies(port); application {module(deps)}
        val token = deps.tokenService.issueAccessToken(actor); val valid = assertNotNull(port.reply)
        for (invalid in listOf(
            valid.copy(consent = valid.consent.copy(actorId = id())), valid.copy(consent = valid.consent.copy(sessionId = id())),
            valid.copy(consent = valid.consent.copy(generation = id())), valid.copy(remainingMillis = 28_800_001),
            valid.copy(remainingMillis = 0), valid.copy(consent = valid.consent.copy(mode = null)),
            valid.copy(consent = valid.consent.copy(deadlineEpochMillis = null)), valid.copy(consent = valid.consent.copy(deadlineEpochMillis = -1)),
            valid.copy(consent = valid.consent.copy(phase = ChannelSharingPhase.REVOKED)),
        )) {
            port.reply = invalid; assertEquals(HttpStatusCode.ServiceUnavailable, client.get(path) {bearerAuth(token)}.status)
        }
        port.reply = valid
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("$path/revoke") {bearerAuth(token)}.status)
    }

    private inner class RecordingPort : ChannelSharingEndpointPort {
        val calls = mutableListOf<String>()
        var reply: ChannelSharingDecision? = ChannelSharingDecision(ChannelSharingConsent(actor, session, generation, ChannelSharingPhase.ACTIVE, ChannelSharingMode.RIDE, 100_000), 5000)
        override fun start(actor: String, session: String, generation: String, mode: ChannelSharingMode, ttl: ChannelSharingTtl): ChannelSharingDecision? {
            calls += "start:$actor:$session:$generation:$mode:$ttl"; return reply
        }
        override fun status(actor: String, session: String, generation: String): ChannelSharingDecision? {calls += "status:$actor:$session:$generation"; return reply}
        override fun revoke(actor: String, session: String, generation: String): ChannelSharingDecision? {calls += "revoke:$actor:$session:$generation"; return reply}
    }
}

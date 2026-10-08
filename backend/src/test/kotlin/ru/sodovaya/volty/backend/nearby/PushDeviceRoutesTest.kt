package ru.sodovaya.volty.backend.nearby

import com.auth0.jwt.JWT
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import ru.sodovaya.volty.backend.*
import java.util.UUID
import kotlin.test.*

class PushDeviceRoutesTest {
    private val actor = id(); private val installation = id(); private val binding = id()
    private val key = "a".repeat(64); private val token = "private-fcm-token"
    private val path = "/v2/nearby/push/device"
    private fun id() = UUID.randomUUID().toString()
    private fun dependencies(port: PushDeviceEndpointPort? = null, active: Boolean = true) = AppDependencies(AppConfig.forTests(), object : BackendStore {
        override fun findUserById(id: String) = UserRecord(actor, "test@example.invalid", "ignored", "Test", true).takeIf {id == actor}
        override fun isAccessActive(userId: String, issuedAtEpochSeconds: Long) = active && userId == actor
    }, testMode = true, nearbyPushDevices = port)
    private fun body(generation: Long = 1, rawToken: String = "\"$token\"") = """{"installationId":"$installation","installationKey":"$key","generation":$generation,"bindingId":"$binding","token":$rawToken,"actorId":"forged","authIssuedAtSeconds":0}"""

    @Test fun authenticationAndCurrentAccountAreRequiredBeforeTouchingPort() = testApplication {
        val recorded = mutableListOf<PushDeviceRegistration>()
        val deps = dependencies(PushDeviceEndpointPort { recorded += it; PushDeviceDecision.APPLIED }, active = false)
        application { module(deps) }
        assertEquals(HttpStatusCode.Unauthorized, client.put(path) { contentType(ContentType.Application.Json); setBody(body()) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.put(path) {
            bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(body())
        }.status)
        assertTrue(recorded.isEmpty())
    }

    @Test fun liveJwtOwnsRegistrationAndLogoutNeverReturnsPrivateValues() = testApplication {
        val recorded = mutableListOf<PushDeviceRegistration>()
        val deps = dependencies(PushDeviceEndpointPort { recorded += it; PushDeviceDecision.APPLIED })
        application { module(deps) }
        val access = deps.tokenService.issueAccessToken(actor)
        for (request in listOf(body(), body(2, "null"))) {
            val response = client.put(path) { bearerAuth(access); contentType(ContentType.Application.Json); setBody(request) }
            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            for (secret in listOf(token, key, installation, binding, "forged")) assertFalse(response.bodyAsText().contains(secret))
        }
        assertEquals(2, recorded.size)
        assertTrue(recorded.all { it.actorId == actor && it.authIssuedAtSeconds == JWT.decode(access).issuedAt.time / 1000 })
        assertEquals(token, recorded[0].token); assertNull(recorded[1].token)
        assertEquals(2L, recorded[1].generation)
    }

    @Test fun malformedAndOversizedBodiesNeverReachPortOrEchoSecrets() = testApplication {
        val recorded = mutableListOf<PushDeviceRegistration>()
        val deps = dependencies(PushDeviceEndpointPort { recorded += it; PushDeviceDecision.APPLIED })
        application { module(deps) }
        for ((payload, status) in listOf(body(0) to HttpStatusCode.BadRequest,
            body().replace(key, "short") to HttpStatusCode.BadRequest,
            "{\"token\":\"$token\"" to HttpStatusCode.BadRequest,
            body().replace(token, "x".repeat(4097)) to HttpStatusCode.BadRequest,
            body() + " ".repeat(8193) to HttpStatusCode.PayloadTooLarge)) {
            val response = client.put(path) { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(payload) }
            assertEquals(status, response.status)
            assertFalse(response.bodyAsText().contains(token)); assertFalse(response.bodyAsText().contains(key))
        }
        val stream = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.Json
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully((body() + " ".repeat(8193)).toByteArray()) }
        }
        assertNull(stream.contentLength)
        val chunked = client.put(path) { bearerAuth(deps.tokenService.issueAccessToken(actor)); setBody(stream) }
        assertEquals(HttpStatusCode.PayloadTooLarge, chunked.status)
        assertTrue(recorded.isEmpty())
    }

    @Test fun privateRepositoryDecisionsHaveBoundedPublicStatus() = testApplication {
        var result = PushDeviceDecision.REPLAY
        val deps = dependencies(PushDeviceEndpointPort { result }); application { module(deps) }
        for ((decision, status) in listOf(PushDeviceDecision.REPLAY to HttpStatusCode.NoContent,
            PushDeviceDecision.STALE to HttpStatusCode.Conflict, PushDeviceDecision.TOKEN_CONFLICT to HttpStatusCode.Conflict,
            PushDeviceDecision.ACCESS_DENIED to HttpStatusCode.Forbidden, PushDeviceDecision.INVALID to HttpStatusCode.BadRequest)) {
            result = decision
            val response = client.put(path) { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(body()) }
            assertEquals(status, response.status)
            val responseText = response.bodyAsText()
            if (decision == PushDeviceDecision.STALE) assertTrue(responseText.contains("\"code\":\"push_binding_stale\""))
            if (decision == PushDeviceDecision.TOKEN_CONFLICT) assertTrue(responseText.contains("\"code\":\"push_token_conflict\""))
            assertFalse(response.bodyAsText().contains(token)); assertFalse(response.bodyAsText().contains(key))
        }
    }

    @Test fun unconfiguredEndpointIsExplicitlyUnavailable() = testApplication {
        val deps = dependencies(); application { module(deps) }
        val response = client.put(path) { bearerAuth(deps.tokenService.issueAccessToken(actor)); contentType(ContentType.Application.Json); setBody(body()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains(token))
    }
}

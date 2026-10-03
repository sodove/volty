package ru.sodovaya.volty.backend.nearby

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import ru.sodovaya.volty.backend.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.*

class MediaWebhookRoutesTest {
    private val session = UUID.randomUUID().toString()
    private val bytes = """{"event":"participant_joined","id":"EV_signed","room":{"name":"room-epoch"},"participant":{"identity":"$session","sid":"PA_signed"}}""".toByteArray()
    private fun signed(body: ByteArray = bytes) = JWT.create().withIssuer("hook-key")
        .withExpiresAt(Date.from(Instant.now().plusSeconds(60)))
        .withClaim("sha256", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body)))
        .sign(Algorithm.HMAC256("hook-secret"))
    private fun deps(endpoint: MediaWebhookEndpoint? = null) = AppDependencies(AppConfig.forTests(), object : BackendStore {}, testMode = true, nearbyMediaWebhook = endpoint)
    private fun endpoint(accept: (VerifiedMediaWebhook) -> MediaWebhookDisposition) =
        MediaWebhookEndpoint(MediaWebhookVerifier("hook-key", "hook-secret"), MediaWebhookEventPort(accept))

    @Test fun disabledEndpointAcknowledgesNoDelivery() = testApplication {
        application { module(deps()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/v2/nearby/media/webhook") { setBody(bytes); header(HttpHeaders.Authorization, signed()) }.status)
    }

    @Test fun actualSignedBytesReachEventPortButTamperingAndAbsentSignatureDoNot() = testApplication {
        val received = mutableListOf<VerifiedMediaWebhook>()
        application { module(deps(endpoint { received += it; MediaWebhookDisposition.ACCEPTED })) }
        val response = client.post("/v2/nearby/media/webhook") { setBody(bytes); header(HttpHeaders.Authorization, signed()); contentType(ContentType.parse("application/webhook+json")) }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(session, received.single().sessionId)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/nearby/media/webhook") { setBody(bytes + " ".toByteArray()); header(HttpHeaders.Authorization, signed()) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v2/nearby/media/webhook") { setBody(bytes) }.status)
        assertEquals(1, received.size)
    }

    @Test fun bodyLimitIsEnforcedBeforeEventDeliveryEvenForSignedBody() = testApplication {
        var delivered = 0
        application { module(deps(endpoint { delivered++; MediaWebhookDisposition.ACCEPTED })) }
        val oversized = ByteArray(65_537) { ' '.code.toByte() }
        val response = client.post("/v2/nearby/media/webhook") { setBody(oversized); header(HttpHeaders.Authorization, signed(oversized)) }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertEquals(0, delivered)
    }

    @Test fun unknownLengthStreamIsBoundedAndExactLimitStillVerifiesOriginalBytes() = testApplication {
        var delivered = 0
        application { module(deps(endpoint { delivered++; MediaWebhookDisposition.ACCEPTED })) }
        val exact = bytes + ByteArray(65_536 - bytes.size) { ' '.code.toByte() }
        fun stream(body: ByteArray) = object : OutgoingContent.WriteChannelContent() {
            override suspend fun writeTo(channel: ByteWriteChannel) { channel.writeFully(body) }
        }
        assertEquals(HttpStatusCode.NoContent, client.post("/v2/nearby/media/webhook") { setBody(stream(exact)); header(HttpHeaders.Authorization, signed(exact)) }.status)
        val oversized = exact + byteArrayOf(' '.code.toByte())
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v2/nearby/media/webhook") { setBody(stream(oversized)); header(HttpHeaders.Authorization, signed(oversized)) }.status)
        assertEquals(1, delivered)
    }

    @Test fun failedDurabilityReturnsRetryAndEventCollisionReturnsConflict() = testApplication {
        var disposition = MediaWebhookDisposition.RETRY
        application { module(deps(endpoint { disposition })) }
        assertEquals(HttpStatusCode.ServiceUnavailable, client.post("/v2/nearby/media/webhook") { setBody(bytes); header(HttpHeaders.Authorization, signed()) }.status)
        disposition = MediaWebhookDisposition.CONFLICT
        assertEquals(HttpStatusCode.Conflict, client.post("/v2/nearby/media/webhook") { setBody(bytes); header(HttpHeaders.Authorization, signed()) }.status)
    }

    @Test fun portExceptionExposesNeitherOriginalBodyNorCredentials() = testApplication {
        application { module(deps(endpoint { throw IllegalStateException("private-body-token") })) }
        val response = client.post("/v2/nearby/media/webhook") { setBody(bytes); header(HttpHeaders.Authorization, signed()) }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertFalse(response.bodyAsText().contains("private-body-token"))
        assertFalse(response.bodyAsText().contains(session))
    }
}

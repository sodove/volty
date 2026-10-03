package ru.sodovaya.volty.backend.nearby

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.test.*

class MediaWebhookVerifierTest {
    private val verifier = MediaWebhookVerifier("webhook-test-key", "webhook-test-secret")
    private val session = UUID.randomUUID().toString()
    private fun body(kind: String = "participant_joined", identity: String = session, sid: String = "PA_test", eventId: String = "EV_not-a-uuid") =
        """{"event":"$kind","id":"$eventId","room":{"name":"room-epoch"},"participant":{"identity":"$identity","sid":"$sid"},"createdAt":"1"}""".toByteArray()
    private fun token(bytes: ByteArray, issuer: String = "webhook-test-key", secret: String = "webhook-test-secret", expires: Instant = Instant.now().plusSeconds(60)) =
        JWT.create().withIssuer(issuer).withExpiresAt(Date.from(expires))
            .withClaim("sha256", Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .sign(Algorithm.HMAC256(secret))

    @Test fun signedOriginalBodyProducesBoundedImmutableEventAndDigest() {
        val bytes = body()
        val event = assertIs<MediaWebhookResult.Accepted>(verifier.verify(bytes, token(bytes))).event
        assertEquals("EV_not-a-uuid", event.eventId)
        assertEquals("room-epoch", event.roomEpoch)
        assertEquals(session, event.sessionId)
        assertEquals("PA_test", event.participantSid)
        assertEquals(MediaWebhookKind.JOINED, event.kind)
        assertEquals(64, event.bodySha256.length)
        assertFalse(event.toString().contains("sha256\":"))
        assertFalse(event.toString().contains(token(bytes)))
    }

    @Test fun leftIsASeparateObservationNotAClientChosenTerminalCommand() {
        val bytes = body("participant_left")
        assertEquals(MediaWebhookKind.LEFT, assertIs<MediaWebhookResult.Accepted>(verifier.verify(bytes, token(bytes))).event.kind)
    }

    @Test fun missingInvalidExpiredOrWrongIssuerAuthorizationNeverProducesEvent() {
        val bytes = body()
        for (auth in listOf(null, "", "invalid", "Bearer " + token(bytes), token(bytes, issuer = "wrong"),
            token(bytes, secret = "wrong"), token(bytes, expires = Instant.now().minusSeconds(60)))) {
            assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes, auth))
        }
    }

    @Test fun originalBytesMustMatchSignedDigestIncludingWhitespace() {
        val bytes = body()
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes + " ".toByteArray(), token(bytes)))
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(body(identity = UUID.randomUUID().toString()), token(bytes)))
    }

    @Test fun oversizedAndInvalidUtf8BodiesCannotReachSignatureParsing() {
        val oversized = ByteArray(65_537) { ' '.code.toByte() }
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(oversized, token(oversized)))
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(malformed, token(malformed)))
    }

    @Test fun intentionallyIncoherentEventProvenanceIsRejectedAfterValidSignature() {
        for (bytes in listOf(body(identity = "client-room"), body(sid = ""), body(eventId = ""),
            body(sid = "x".repeat(257)), body(eventId = "x".repeat(257)))) {
            assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes, token(bytes)))
        }
    }

    @Test fun irrelevantSignedEventsAreIgnoredButInvalidSignatureIsNotAcknowledged() {
        val bytes = body("track_published")
        assertIs<MediaWebhookResult.Ignored>(verifier.verify(bytes, token(bytes)))
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes, "invalid"))
    }

    @Test fun signedRoomFinishedHasRoomButNoFabricatedParticipant() {
        val bytes = """{"event":"room_finished","id":"EV_room","room":{"name":"room-epoch","sid":"RM_old"}}""".toByteArray()
        val event = assertIs<MediaWebhookResult.Accepted>(verifier.verify(bytes, token(bytes))).event
        assertEquals(MediaWebhookKind.ROOM_FINISHED, event.kind)
        assertEquals("room-epoch", event.roomEpoch)
        assertNull(event.sessionId)
        assertNull(event.participantSid)
        assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes, "invalid"))
    }

    @Test fun roomFinishedCannotCarryParticipantOrMissingRoomProvenance() {
        val missingRoom = """{"event":"room_finished","id":"EV_room"}""".toByteArray()
        for (bytes in listOf(body("room_finished"), missingRoom)) {
            assertIs<MediaWebhookResult.Rejected>(verifier.verify(bytes, token(bytes)))
        }
    }
}

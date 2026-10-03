package ru.sodovaya.volty.backend.nearby

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import kotlin.test.*

class SessionCredentialsTest {
    private fun row(phase: OperationPhase = OperationPhase.ACTIVE) = SessionJournalEntry(
        SessionOperation("actor", "operation", "destination", "immutable-session", phase, System.currentTimeMillis() + 30_000),
        SfuEpoch("voice-room-generation", "a".repeat(32)), false, false)

    @Test fun credentialsUseSessionIdentityAndOnlyMicrophoneMediaGrants() {
        val active = row()
        val credential = JwtSessionCredentialIssuer("wss://voice.example.invalid", "test-key", "test-secret").issue(active)
        val verified = JWT.require(Algorithm.HMAC256("test-secret")).build().verify(credential.participantToken)
        assertEquals("immutable-session", verified.subject)
        val video = verified.getClaim("video").asMap()
        assertEquals("voice-room-generation", video["room"])
        assertEquals(true, video["roomJoin"])
        assertEquals(true, video["canPublish"])
        assertEquals(true, video["canSubscribe"])
        assertEquals(listOf("microphone"), video["canPublishSources"])
        assertNotEquals(true, video["roomAdmin"])
        assertEquals(active.operation.deadlineEpochMillis!! / 1000, verified.getClaim("exp").asLong())
    }

    @Test fun terminalRowCannotMintCredentials() {
        val terminal = row(OperationPhase.CANCELLED)
        assertFailsWith<IllegalStateException> { JwtSessionCredentialIssuer("wss://voice.example.invalid", "test-key", "test-secret").issue(terminal) }
    }

    @Test fun actionAndCredentialsRenderingDoNotExposeBearer() {
        val value = SessionCredentials("wss://voice.example.invalid", "secret-that-must-stay-private", "session", "room", 1000)
        assertFalse(value.toString().contains(value.participantToken))
        assertFalse(SessionAction.Ready(value).toString().contains(value.participantToken))
    }
}

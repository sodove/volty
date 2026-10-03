package ru.sodovaya.volty.backend.nearby

import io.livekit.server.WebhookReceiver
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

enum class MediaWebhookKind { JOINED, LEFT }
enum class MediaWebhookFailure { BODY_TOO_LARGE, INVALID_UTF8, UNAUTHORIZED, INVALID_EVENT }

/** Signed notification is reconciliation intent, never authority to resurrect/end media by itself. */
data class VerifiedMediaWebhook(
    val eventId: String, val bodySha256: String, val roomEpoch: String,
    val sessionId: String, val participantSid: String, val kind: MediaWebhookKind,
)

sealed interface MediaWebhookResult {
    data class Accepted(val event: VerifiedMediaWebhook) : MediaWebhookResult
    data object Ignored : MediaWebhookResult
    data class Rejected(val reason: MediaWebhookFailure) : MediaWebhookResult
}

/** Route must bound streaming input before allocating bytes. No body/token/exception escapes. */
class MediaWebhookVerifier(apiKey: String, apiSecret: String) {
    private val receiver = WebhookReceiver(apiKey, apiSecret)

    init { require(apiKey.isNotBlank() && apiSecret.isNotBlank()) }

    fun verify(bytes: ByteArray, authorization: String?): MediaWebhookResult {
        if (bytes.size > MAXIMUM_BODY_BYTES) return MediaWebhookResult.Rejected(MediaWebhookFailure.BODY_TOO_LARGE)
        val original = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return MediaWebhookResult.Rejected(MediaWebhookFailure.INVALID_UTF8) }
        if (authorization == null || authorization.length > 8192 || authorization.isBlank()) {
            return MediaWebhookResult.Rejected(MediaWebhookFailure.UNAUTHORIZED)
        }
        // Pinned sender uses the raw JWT Authorization value, not a Bearer prefix.
        val event = try { receiver.receive(original, authorization) } catch (_: Exception) {
            return MediaWebhookResult.Rejected(MediaWebhookFailure.UNAUTHORIZED)
        }
        val kind = when (event.event) {
            "participant_joined" -> MediaWebhookKind.JOINED
            "participant_left" -> MediaWebhookKind.LEFT
            else -> return MediaWebhookResult.Ignored
        }
        val identity = event.participant.identity
        val canonical = try { UUID.fromString(identity).toString() } catch (_: IllegalArgumentException) { null }
        if (!bounded(event.id, 256) || !bounded(event.room.name, 128) || !bounded(event.participant.sid, 256) || canonical != identity) {
            return MediaWebhookResult.Rejected(MediaWebhookFailure.INVALID_EVENT)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return MediaWebhookResult.Accepted(VerifiedMediaWebhook(event.id, digest, event.room.name, identity, event.participant.sid, kind))
    }

    private fun bounded(value: String, maximum: Int) = value.isNotBlank() && value.length <= maximum

    companion object { const val MAXIMUM_BODY_BYTES = 65_536 }
}

package ru.sodovaya.volty.data.voice

import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import ru.sodovaya.volty.domain.voice.*

/** Shared channel/call evidence decoder. No HTTP ownership, clock sampling or device claims. */
internal object VoiceSessionResponseDecoder {
    private val json = Json {ignoreUnknownKeys = true}
    private val id = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun decode(document: JsonObject, generation: VoiceGeneration, expectedOperation: VoiceOperationId?,
        expectedSession: VoiceSessionId?, allowCredentials: Boolean, terminalOnly: Boolean,
        ordinal: VoiceLeaseRequestId, started: Long, received: Long,
        requireCredentials: Boolean = allowCredentials, expectedRoom: String? = null): VoiceHttpReply {
        document["cleanupPending"]?.takeUnless {it == JsonNull}?.let {
            require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null)
        }
        val wire = json.decodeFromJsonElement<StateBody>(document)
        require(received >= started)
        wire.operationId?.let {require(id.matches(it)); require(expectedOperation == null || it == expectedOperation.value)}
        wire.sessionId?.let {require(id.matches(it))}
        require(expectedSession == null || wire.sessionId == expectedSession.value)
        wire.deadlineEpochMillis?.let {require(it >= 0)}
        val session = wire.sessionId?.let(::VoiceSessionId)
        if (wire.phase in setOf("CANCELLED", "EXPIRED")) {
            require(wire.credentials == null && wire.remainingLeaseMillis == null)
            return VoiceHttpReply.Ended(generation, session, wire.phase, wire.cleanupPending)
        }
        require(!terminalOnly)
        if (wire.phase == "PENDING") {
            require(wire.credentials == null && wire.remainingLeaseMillis == null)
            return VoiceHttpReply.Pending(generation, session, wire.operationId?.let(::VoiceOperationId))
        }
        require(wire.phase == "CONNECTING" || wire.phase == "CONNECTED")
        val tag = VoiceSessionTag(requireNotNull(session), generation)
        val lease = VoiceLeaseGrant(ordinal, started, received, requireNotNull(wire.remainingLeaseMillis))
        val credentials = wire.credentials
        if (requireCredentials) require(credentials != null)
        if (credentials != null) {
            require(allowCredentials && wire.phase == "CONNECTING")
            val url = Url(credentials.serverUrl)
            require(url.protocol == URLProtocol.WSS && url.host.isNotBlank() && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.fragment.isEmpty())
            require(credentials.participantToken.length in 1..8192 && credentials.participantToken.isNotBlank())
            require(credentials.roomEpoch.isNotBlank() && credentials.roomEpoch.length <= 128)
            require(expectedRoom == null || credentials.roomEpoch == expectedRoom)
        }
        if (lease.expiredOnReceipt) return VoiceHttpReply.Expired(tag)
        return if (credentials != null) VoiceHttpReply.Credentials(tag, VoiceConnectionCredentials(credentials.serverUrl, credentials.participantToken), lease)
        else VoiceHttpReply.Acknowledged(tag, lease, wire.phase == "CONNECTED")
    }

    @Serializable private data class StateBody(
        val phase: String, val sessionId: String? = null, val operationId: String? = null,
        val deadlineEpochMillis: Long? = null, val remainingLeaseMillis: Long? = null,
        val credentials: CredentialBody? = null, val cleanupPending: Boolean? = null,
    )
    @Serializable private data class CredentialBody(val serverUrl: String, val participantToken: String, val roomEpoch: String) {
        override fun toString() = "CredentialBody(redacted)"
    }
}

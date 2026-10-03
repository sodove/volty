package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.*

enum class VoiceHttpFailure { UNAUTHORIZED, ACCESS_DENIED, BUSY, CONFLICT, UNAVAILABLE, INVALID_RESPONSE }

/** HTTP control results never assert microphone capture, playback or audibility. */
sealed interface VoiceHttpReply {
    data class Credentials(val tag: VoiceSessionTag, val connection: VoiceConnectionCredentials, val lease: VoiceLeaseGrant) : VoiceHttpReply
    data class Acknowledged(val tag: VoiceSessionTag, val lease: VoiceLeaseGrant, val confirmedTransport: Boolean) : VoiceHttpReply
    data class Pending(val generation: VoiceGeneration, val sessionId: VoiceSessionId?, val operationId: VoiceOperationId?) : VoiceHttpReply
    /** null is an older/unknown server reply, never proof of remote cleanup. */
    data class Ended(val generation: VoiceGeneration, val sessionId: VoiceSessionId?, val phase: String,
                     val cleanupPending: Boolean? = null) : VoiceHttpReply
    /** Caller must retire the attempt/stop this session; expired credentials are discarded. */
    data class Expired(val tag: VoiceSessionTag) : VoiceHttpReply
    data class Failed(val reason: VoiceHttpFailure) : VoiceHttpReply
}

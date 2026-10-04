package ru.sodovaya.volty.data.nearby

import ru.sodovaya.volty.data.voice.VoiceHttpReply
import ru.sodovaya.volty.domain.voice.*

enum class DirectCallPhase { RINGING, ACCEPTED, DECLINED, CANCELLED, MISSED, ENDED }
enum class DirectCallOutcome { CALLEE_DECLINED, CALLER_CANCELLED, CALLEE_CANCELLED, RING_TIMEOUT, CALLER_ENDED, CALLEE_ENDED, FRIENDSHIP_REVOKED, MEDIA_TIMEOUT, MEDIA_LOST }
enum class DirectCallFailure { UNAUTHORIZED, ACCESS_DENIED, FRIENDSHIP_REQUIRED, BUSY, CONFLICT, UNAVAILABLE, INVALID_RESPONSE }

/** Server deadline is display metadata, never microphone authorization or a phone clock. */
data class DirectCallSnapshot(val callId: String, val callerId: String, val calleeId: String,
    val phase: DirectCallPhase, val revision: Long, val ringDeadlineEpochMillis: Long, val outcome: DirectCallOutcome?) {
    val terminal get() = phase != DirectCallPhase.RINGING && phase != DirectCallPhase.ACCEPTED
}
sealed interface DirectCallReply {
    data class Ready(val call: DirectCallSnapshot, val session: VoiceHttpReply? = null, val retryableFailure: String? = null) : DirectCallReply
    data class Failed(val reason: DirectCallFailure) : DirectCallReply
}
sealed interface IncomingCallReply {
    data class Ready(val calls: List<DirectCallSnapshot>, val nextCursor: String?) : IncomingCallReply
    data class Failed(val reason: DirectCallFailure) : IncomingCallReply
}
interface DirectCallControlPort {
    suspend fun create(call: String, callee: String, key: String, operation: VoiceOperationId): DirectCallReply
    suspend fun cancelOutgoing(call: String, callee: String, key: String, operation: VoiceOperationId): DirectCallReply
    suspend fun status(call: String): DirectCallReply
    suspend fun incoming(after: String? = null, limit: Int = 20): IncomingCallReply
    suspend fun accept(call: String, operation: VoiceOperationId, generation: VoiceGeneration, revision: Long): DirectCallReply
    suspend fun resume(call: String, operation: VoiceOperationId, generation: VoiceGeneration): DirectCallReply
    suspend fun decline(call: String): DirectCallReply
    suspend fun stop(call: String): DirectCallReply
}

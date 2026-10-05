package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.*

/** Replies carry validated identity and request-start monotonic lease provenance. */
interface VoiceSessionControlPort {
    /** Pure destination permission, checked when a queued sharing request is consumed. */
    fun allowsSharing(operation: VoiceOperationId): Boolean = true
    /** Actual typed journal destination. Absence never earns a sharing HTTP capability. */
    fun sharingChannel(operation: VoiceOperationId): String? = null
    /** Pure intent registration before scheduling a producer that may never start. */
    fun rememberJoinIntent(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) = Unit
    suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean = false): VoiceHttpReply
    suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply
    suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply
    suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply
    suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply
}

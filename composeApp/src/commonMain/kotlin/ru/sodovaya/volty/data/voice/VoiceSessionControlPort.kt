package ru.sodovaya.volty.data.voice

import ru.sodovaya.volty.domain.voice.*

/** Replies carry validated identity and request-start monotonic lease provenance. */
interface VoiceSessionControlPort {
    suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean = false): VoiceHttpReply
    suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply
    suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply
    suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply
    suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply
}

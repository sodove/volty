package ru.sodovaya.volty.domain.voice

sealed interface VoiceSessionEvent {
    data class Join(val operationId: VoiceOperationId) : VoiceSessionEvent
    data class SessionGranted(
        val operationId: VoiceOperationId,
        val generation: VoiceGeneration,
        val sessionId: VoiceSessionId,
        val nowMillis: Long,
    ) : VoiceSessionEvent
    data object Stop : VoiceSessionEvent
    /** Acknowledges engine release, not server claim or SFU revocation. */
    data class Stopped(val generation: VoiceGeneration) : VoiceSessionEvent
    data class Failed(val generation: VoiceGeneration) : VoiceSessionEvent
}

package ru.sodovaya.volty.domain.voice

sealed interface VoiceSessionEffect {
    data class RequestJoin(val operationId: VoiceOperationId, val generation: VoiceGeneration) : VoiceSessionEffect
    data class CancelJoin(val operationId: VoiceOperationId, val generation: VoiceGeneration) : VoiceSessionEffect
    data class StartEngine(val tag: VoiceSessionTag) : VoiceSessionEffect
    data class StopEngine(val generation: VoiceGeneration) : VoiceSessionEffect
    data class StopSession(val sessionId: VoiceSessionId) : VoiceSessionEffect
    data object RemoveNearbyDemand : VoiceSessionEffect
    data object ClearSharingQueue : VoiceSessionEffect
}

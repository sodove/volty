package ru.sodovaya.volty.domain.voice

sealed interface VoiceSessionEffect {
    data class RequestJoin(val operationId: VoiceOperationId, val generation: VoiceGeneration) : VoiceSessionEffect
    data class CancelJoin(val operationId: VoiceOperationId, val generation: VoiceGeneration) : VoiceSessionEffect
    /** Apply preferences before allowing capture/publication, not after connecting. */
    data class StartEngine(
        val tag: VoiceSessionTag,
        val muted: Boolean = false,
        val deafened: Boolean = false,
    ) : VoiceSessionEffect
    data class StopEngine(val generation: VoiceGeneration) : VoiceSessionEffect
    data class StopSession(val sessionId: VoiceSessionId) : VoiceSessionEffect
    data object RemoveNearbyDemand : VoiceSessionEffect
    data object AddNearbyDemand : VoiceSessionEffect
    data object ClearSharingQueue : VoiceSessionEffect
    data class SetMuted(val tag: VoiceSessionTag, val value: Boolean) : VoiceSessionEffect
    data class SetDeafened(val tag: VoiceSessionTag, val value: Boolean) : VoiceSessionEffect
}

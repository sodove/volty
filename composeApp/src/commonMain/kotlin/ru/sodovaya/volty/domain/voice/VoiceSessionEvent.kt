package ru.sodovaya.volty.domain.voice

sealed interface VoiceSessionEvent {
    data class Join(val operationId: VoiceOperationId) : VoiceSessionEvent
    data class SessionGranted(
        val operationId: VoiceOperationId,
        val generation: VoiceGeneration,
        val sessionId: VoiceSessionId,
        val lease: VoiceLeaseGrant,
    ) : VoiceSessionEvent
    data object Stop : VoiceSessionEvent
    /** Acknowledges engine release, not server claim or SFU revocation. */
    data class Stopped(val generation: VoiceGeneration) : VoiceSessionEvent
    data class Failed(val generation: VoiceGeneration) : VoiceSessionEvent

    sealed interface Tagged : VoiceSessionEvent { val tag: VoiceSessionTag }
    data class TransportConnected(override val tag: VoiceSessionTag) : Tagged
    data class CaptureStarted(override val tag: VoiceSessionTag) : Tagged
    data class CaptureStopped(override val tag: VoiceSessionTag) : Tagged
    data class Published(override val tag: VoiceSessionTag) : Tagged
    data class Unpublished(override val tag: VoiceSessionTag) : Tagged
    data class Subscribed(override val tag: VoiceSessionTag, val peer: String) : Tagged
    /** Adapter aggregates track lifetimes; emit when the peer has no subscribed audio tracks. */
    data class Unsubscribed(override val tag: VoiceSessionTag, val peer: String) : Tagged
    data class RenderStarted(override val tag: VoiceSessionTag) : Tagged
    data class RenderStopped(override val tag: VoiceSessionTag) : Tagged
    data class Reconnecting(override val tag: VoiceSessionTag) : Tagged
    /** Backend receipt only; SDK transport callbacks cannot authorize a lease. */
    data class LeaseAck(override val tag: VoiceSessionTag, val lease: VoiceLeaseGrant) : Tagged
    data class Tick(val nowMillis: Long) : VoiceSessionEvent
    data class SetMuted(val value: Boolean) : VoiceSessionEvent
    data class SetDeafened(val value: Boolean) : VoiceSessionEvent
    data object EnableSharing : VoiceSessionEvent
    data object HidePosition : VoiceSessionEvent
}

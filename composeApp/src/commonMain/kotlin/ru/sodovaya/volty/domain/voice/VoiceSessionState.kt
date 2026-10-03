package ru.sodovaya.volty.domain.voice

enum class VoicePhase { IDLE, JOINING, CONNECTING, CONNECTED, RECONNECTING, STOPPING, FAILED }

/** Local intent and adapter evidence. Membership and credentials are owned elsewhere. */
data class VoiceSessionState(
    val generation: VoiceGeneration = VoiceGeneration(0),
    val operationId: VoiceOperationId? = null,
    val sessionId: VoiceSessionId? = null,
    val phase: VoicePhase = VoicePhase.IDLE,
    val wanted: Boolean = false,
    val cleanupPending: Boolean = false,
    val transportConnected: Boolean = false,
    val captureStarted: Boolean = false,
    val published: Boolean = false,
    val subscribedPeers: Set<String> = emptySet(),
    val renderStarted: Boolean = false,
    val leaseDeadlineMillis: Long? = null,
    val lastLeaseRequestId: VoiceLeaseRequestId? = null,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val sharingEnabled: Boolean = false,
)

data class VoiceTransition(val state: VoiceSessionState, val effects: List<VoiceSessionEffect>)

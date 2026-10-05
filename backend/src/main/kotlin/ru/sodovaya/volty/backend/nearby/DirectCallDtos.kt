package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable

@Serializable data class DirectCallCreateRequest(val calleeId: String, val idempotencyKey: String, val operationId: String)
@Serializable data class DirectCallAcceptRequest(val operationId: String, val expectedRevision: Long)
@Serializable data class DirectCallResumeRequest(val operationId: String)

/** Caller key/original operation are private and never projected into peer snapshots. */
@Serializable data class DirectCallSnapshotDto(
    val callId: String, val callerId: String, val calleeId: String, val phase: String,
    val revision: Long, val ringDeadlineEpochMillis: Long, val outcome: String? = null,
) {
    companion object {
        fun from(value: DirectCallRecord) = DirectCallSnapshotDto(value.callId, value.callerId, value.calleeId,
            value.phase.name, value.revision, value.ringDeadlineMillis, value.outcome?.name)
    }
}
@Serializable data class DirectCallResponse(val call: DirectCallSnapshotDto, val session: SessionStateResponse? = null,
    val retryableFailure: String? = null, val remainingRingMillis: Long? = null)
@Serializable data class IncomingCallsResponse(val calls: List<DirectCallSnapshotDto>, val nextCursor: String? = null)

/** Transport peers deliberately omit both original participant operation nonces. */
@Serializable data class DirectCallRosterResponse(
    val callId: String, val revision: Long, val serverTimeEpochMillis: Long, val peers: List<DirectCallRosterPeerDto>,
)
@Serializable data class DirectCallRosterPeerDto(
    val actorId: String, val sessionId: String, val phase: String, val deadlineEpochMillis: Long,
    val mediaPresence: String, val participantSid: String? = null,
)

package ru.sodovaya.volty.domain.voice

/** Explicit call subscription; never a channel UUID or an engine generation. */
data class DirectCallRosterTag(val callId: String, val peerId: String, val generation: Long) {
    init {require(callId.isNotBlank() && peerId.isNotBlank() && generation >= 0)}
}
data class DirectCallRosterPeer(
    val actorId: String, val sessionId: VoiceSessionId, val phase: VoiceRosterPhase,
    val participantSid: String?, val transportConfirmed: Boolean,
)
/** Original request credit and observed transport only; neither establishes audibility. */
data class DirectCallRosterObservation(
    val tag: DirectCallRosterTag, val revision: Long, val peers: List<DirectCallRosterPeer>,
    val requestId: VoiceLeaseRequestId, val requestStartedMillis: Long, val responseReceivedMillis: Long,
    val serverTimeEpochMillis: Long, val leaseDeadlineMillis: Long,
) {
    fun leaseIsFresh(now: Long) = now >= responseReceivedMillis && now < leaseDeadlineMillis
}
sealed interface DirectCallRosterReply {
    data class Received(val observation: DirectCallRosterObservation) : DirectCallRosterReply
    data class Failed(val reason: VoiceRosterFailure) : DirectCallRosterReply
}
fun interface DirectCallRosterPort {
    suspend fun snapshot(tag: DirectCallRosterTag): DirectCallRosterReply
}

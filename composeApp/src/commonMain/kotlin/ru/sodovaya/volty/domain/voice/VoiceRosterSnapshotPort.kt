package ru.sodovaya.volty.domain.voice

enum class VoiceRosterFailure { UNAUTHORIZED, ACCESS_DENIED, UNAVAILABLE, INVALID_RESPONSE }
sealed interface VoiceRosterSnapshotReply {
    data class Received(val observation: VoiceRosterObservation) : VoiceRosterSnapshotReply
    data class Failed(val reason: VoiceRosterFailure) : VoiceRosterSnapshotReply
}
/** Separate transport read from subscription ownership and permission to capture/share. */
interface VoiceRosterSnapshotPort { suspend fun snapshot(tag: VoiceRosterTag): VoiceRosterSnapshotReply }

/** Persisted roster rows plus conservative local freshness, not SDK count or audibility. */
data class VoiceRosterObservation(
    val update: VoiceRosterUpdate,
    val requestId: VoiceLeaseRequestId,
    val requestStartedMillis: Long,
    val responseReceivedMillis: Long,
    val serverTimeEpochMillis: Long,
    val peerDeadlines: Map<VoiceSessionId, Long>,
    val profileNames: Map<String, String> = emptyMap(),
) {
    init {
        require(update.revision >= 0 && requestStartedMillis >= 0 && responseReceivedMillis >= requestStartedMillis && serverTimeEpochMillis >= 0)
        require(peerDeadlines.keys == update.peers.map { it.sessionId }.toSet())
        require(peerDeadlines.values.all { it >= requestStartedMillis && it - requestStartedMillis <= 30_000 })
    }
    /** Clock includes sleep. Times before receipt, expired or unknown session fail closed.
     * Subscription owner must separately detect later clock rollback and retire its state. */
    fun leaseIsFresh(session: VoiceSessionId, nowMillis: Long): Boolean = nowMillis >= responseReceivedMillis &&
        peerDeadlines[session]?.let { nowMillis < it } == true
}

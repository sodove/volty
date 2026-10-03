package ru.sodovaya.volty.domain.voice

/** Roster subscription generation is independent of the active voice engine generation. */
data class VoiceRosterTag(val channelId: String, val generation: Long) {
    init { require(channelId.isNotBlank() && generation >= 0) }
}

enum class VoiceRosterPhase { JOINING, CONNECTING, CONNECTED }

/** SFU evidence only. Neither lease freshness nor phone audibility follows from this record. */
data class VoiceRosterPeer(
    val actorId: String,
    val sessionId: VoiceSessionId,
    val operationId: VoiceOperationId,
    val phase: VoiceRosterPhase,
    val deadlineEpochMillis: Long,
    val participantSid: String?,
    val transportConfirmed: Boolean,
)

data class VoiceRosterUpdate(val tag: VoiceRosterTag, val revision: Long, val peers: List<VoiceRosterPeer>)
data class VoiceRosterResult(val replica: VoiceRosterReplica, val requestResync: Boolean)

/**
 * Full-snapshot replica, not a membership count or a rendering of fresh presence.
 * The future transport must own tags and handle resync; peer lease expiry is a
 * separate pending integration. Null currentPeers means unknown, not zero peers.
 */
data class VoiceRosterReplica(
    val tag: VoiceRosterTag,
    val revision: Long? = null,
    private val rows: List<VoiceRosterPeer> = emptyList(),
    private val resyncRequired: Boolean = false,
) {
    val currentPeers: List<VoiceRosterPeer>? get() = rows.takeIf { revision != null && !resyncRequired }

    fun receive(update: VoiceRosterUpdate): VoiceRosterResult {
        if (update.tag != tag) return unchanged()
        if (revision != null && update.revision < revision) return unchanged()
        if (update.revision < 0 || !validPeers(update.peers)) return requireResync()
        val peers = update.peers.sortedBy { it.actorId }
        if (update.revision == revision && peers != rows) return requireResync()
        return VoiceRosterResult(copy(revision = update.revision, rows = peers, resyncRequired = false), false)
    }

    fun disconnected(owner: VoiceRosterTag): VoiceRosterResult = if (owner == tag) requireResync() else unchanged()

    private fun requireResync() = VoiceRosterResult(copy(resyncRequired = true), !resyncRequired)
    private fun unchanged() = VoiceRosterResult(this, false)

    private fun validPeers(peers: List<VoiceRosterPeer>): Boolean {
        if (peers.map { it.actorId }.toSet().size != peers.size ||
            peers.map { it.sessionId }.toSet().size != peers.size) return false
        return peers.all {
            it.actorId.isNotBlank() && it.sessionId.value.isNotBlank() && it.operationId.value.isNotBlank() &&
                it.deadlineEpochMillis >= 0 &&
                (it.participantSid == null || it.participantSid.isNotBlank()) &&
                (it.phase == VoiceRosterPhase.CONNECTED) == it.transportConfirmed &&
                (!it.transportConfirmed || it.participantSid != null)
        }
    }
}

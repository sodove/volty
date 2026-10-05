package ru.sodovaya.volty.domain.voice

/** Full original-pair evidence; unknown presence stays absent rather than becoming a zero. */
data class DirectCallRosterReplica(
    val tag: DirectCallRosterTag, val actorId: String,
    private val observation: DirectCallRosterObservation? = null,
    val retired: Boolean = false, val resyncRequired: Boolean = false,
) {
    fun receive(reply: DirectCallRosterReply): DirectCallRosterReplica {
        if (retired) return this
        if (reply is DirectCallRosterReply.Failed) return when (reply.reason) {
            VoiceRosterFailure.UNAUTHORIZED, VoiceRosterFailure.ACCESS_DENIED -> retire()
            VoiceRosterFailure.UNAVAILABLE, VoiceRosterFailure.INVALID_RESPONSE -> unknown()
        }
        val incoming = (reply as DirectCallRosterReply.Received).observation
        if (incoming.tag != tag) return this
        val previous = observation
        if (previous != null && (incoming.requestId.value <= previous.requestId.value || incoming.revision < previous.revision)) return this
        if (!valid(incoming)) return unknown()
        val sorted = incoming.copy(peers = incoming.peers.sortedBy {it.actorId})
        if (previous != null) {
            if (sorted.peers.associate {it.actorId to it.sessionId} != previous.peers.associate {it.actorId to it.sessionId}) return unknown()
            if (sorted.revision == previous.revision &&
                (sorted.peers != previous.peers || serverDeadline(sorted) != serverDeadline(previous))) return unknown()
        }
        return copy(observation = sorted, resyncRequired = false)
    }
    fun currentPeers(now: Long): List<DirectCallRosterPeer>? = observation?.takeIf {
        !retired && !resyncRequired && it.tag == tag && valid(it) && it.leaseIsFresh(now)
    }?.peers
    fun confirmedPeers(now: Long): List<DirectCallRosterPeer>? = currentPeers(now)?.filter {it.transportConfirmed}
    fun retire(): DirectCallRosterReplica = copy(observation = null, retired = true, resyncRequired = false)
    private fun unknown() = copy(resyncRequired = true)
    private fun serverDeadline(value: DirectCallRosterObservation) = value.serverTimeEpochMillis + (value.leaseDeadlineMillis - value.requestStartedMillis)
    private fun valid(value: DirectCallRosterObservation): Boolean {
        if (value.revision < 0 || value.requestStartedMillis < 0 || value.responseReceivedMillis < value.requestStartedMillis ||
            value.serverTimeEpochMillis < 0 || value.leaseDeadlineMillis <= value.requestStartedMillis) return false
        val remaining = value.leaseDeadlineMillis - value.requestStartedMillis
        if (remaining > 30_000 || value.serverTimeEpochMillis > Long.MAX_VALUE - remaining) return false
        val peers = value.peers
        return ID.matches(actorId) && ID.matches(tag.peerId) && actorId != tag.peerId && ID.matches(tag.callId) &&
            peers.size == 2 && peers.map {it.actorId}.toSet() == setOf(actorId, tag.peerId) &&
            peers.map {it.sessionId}.distinct().size == 2 && peers.all {
                ID.matches(it.sessionId.value) && (it.phase == VoiceRosterPhase.CONNECTED) == it.transportConfirmed &&
                    (!it.transportConfirmed || it.participantSid != null) &&
                    (it.participantSid == null || (it.participantSid.length in 1..256 && it.participantSid.isNotBlank() && it.participantSid.none(Char::isISOControl)))
            }
    }
    private companion object {
        val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}

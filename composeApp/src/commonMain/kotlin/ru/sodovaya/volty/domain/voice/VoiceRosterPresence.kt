package ru.sodovaya.volty.domain.voice

/** Capture this exact token for every reply, including failures without HTTP ordinals. */
class VoiceRosterRead internal constructor(val tag: VoiceRosterTag, val attempt: Long)

enum class VoiceRosterUnknown { INITIAL, DISCONNECTED, INVALID_RESPONSE, UNAVAILABLE, ACCESS_DENIED, CLOCK_RESET, CLOSED }
data class VoiceRosterPresencePeer(val peer: VoiceRosterPeer, val leaseFresh: Boolean, val connected: Boolean)
data class VoiceRosterPresenceView(
    val revision: Long?, val peers: List<VoiceRosterPresencePeer>?, val connectedCount: Int?,
    val unknown: VoiceRosterUnknown?, val retired: Boolean,
)
data class VoiceRosterPresenceDecision(val requestResync: Boolean = false)

/**
 * One subscription generation, called serially by its owner. No network, membership or
 * media effects. Persisted transport evidence plus a fresh lease still does not prove audio.
 */
class VoiceRosterPresence(private val tag: VoiceRosterTag) {
    private var replica = VoiceRosterReplica(tag)
    private var observation: VoiceRosterObservation? = null
    private var pending: VoiceRosterRead? = null
    private var nextAttempt = 0L
    private var lastOrdinal: Long? = null
    private var lastTime: Long? = null
    private var unknown: VoiceRosterUnknown? = VoiceRosterUnknown.INITIAL
    private var retired = false

    val view: VoiceRosterPresenceView get() {
        val sample = observation
        val rows = replica.currentPeers?.takeIf { !retired && unknown == null && sample != null }
            ?.map { peer ->
                val fresh = sample!!.leaseIsFresh(peer.sessionId, lastTime!!)
                VoiceRosterPresencePeer(peer, fresh, fresh && peer.transportConfirmed && peer.phase == VoiceRosterPhase.CONNECTED)
            }
        return VoiceRosterPresenceView(replica.revision, rows, rows?.count { it.connected }, unknown, retired)
    }

    fun beginRead(): VoiceRosterRead? {
        if (retired) return null
        if (nextAttempt == Long.MAX_VALUE) { retire(VoiceRosterUnknown.CLOSED); return null }
        return VoiceRosterRead(tag, nextAttempt++).also { pending = it }
    }

    fun complete(request: VoiceRosterRead, reply: VoiceRosterSnapshotReply, nowMillis: Long): VoiceRosterPresenceDecision {
        if (retired || request !== pending || request.tag != tag) return VoiceRosterPresenceDecision()
        pending = null
        if (!observeTime(nowMillis)) return VoiceRosterPresenceDecision()
        when (reply) {
            is VoiceRosterSnapshotReply.Failed -> return when (reply.reason) {
                VoiceRosterFailure.UNAUTHORIZED, VoiceRosterFailure.ACCESS_DENIED -> {
                    retire(VoiceRosterUnknown.ACCESS_DENIED); VoiceRosterPresenceDecision()
                }
                VoiceRosterFailure.UNAVAILABLE -> invalidate(VoiceRosterUnknown.UNAVAILABLE)
                VoiceRosterFailure.INVALID_RESPONSE -> invalidate(VoiceRosterUnknown.INVALID_RESPONSE)
            }
            is VoiceRosterSnapshotReply.Received -> {
                val sample = reply.observation
                if (sample.update.tag != tag || nowMillis < sample.responseReceivedMillis ||
                    lastOrdinal?.let { sample.requestId.value <= it } == true || !validCredit(sample))
                    return invalidate(VoiceRosterUnknown.INVALID_RESPONSE)
                lastOrdinal = sample.requestId.value
                val previousRevision = replica.revision
                val result = replica.receive(sample.update)
                replica = result.replica
                if (replica.currentPeers == null) {
                    observation = null; unknown = VoiceRosterUnknown.INVALID_RESPONSE
                    return VoiceRosterPresenceDecision(result.requestResync)
                }
                // An older revision cannot replace either the persisted rows or their lease credit.
                if (previousRevision != null && sample.update.revision < previousRevision)
                    return VoiceRosterPresenceDecision()
                observation = sample
                unknown = null
                return VoiceRosterPresenceDecision()
            }
        }
    }

    fun tick(nowMillis: Long) { if (!retired) observeTime(nowMillis) }

    fun disconnected(nowMillis: Long): VoiceRosterPresenceDecision {
        if (retired || !observeTime(nowMillis)) return VoiceRosterPresenceDecision()
        pending = null
        return invalidate(VoiceRosterUnknown.DISCONNECTED)
    }

    fun close() { if (!retired) retire(VoiceRosterUnknown.CLOSED) }

    private fun observeTime(now: Long): Boolean {
        if (now < 0 || lastTime?.let { now < it } == true) {
            retire(VoiceRosterUnknown.CLOCK_RESET); return false
        }
        lastTime = now
        return true
    }

    private fun invalidate(reason: VoiceRosterUnknown): VoiceRosterPresenceDecision {
        val result = replica.disconnected(tag)
        replica = result.replica
        observation = null; unknown = reason
        return VoiceRosterPresenceDecision(result.requestResync)
    }

    private fun retire(reason: VoiceRosterUnknown) {
        retired = true; pending = null; observation = null; unknown = reason
    }

    /** Do not trust a provider's locally plausible map independently of its raw rows. */
    private fun validCredit(sample: VoiceRosterObservation): Boolean = sample.update.peers.all { peer ->
        if (peer.deadlineEpochMillis < 0) return@all false
        val remaining = (peer.deadlineEpochMillis - sample.serverTimeEpochMillis).coerceAtLeast(0)
        remaining <= 30_000 && sample.requestStartedMillis <= Long.MAX_VALUE - remaining &&
            sample.peerDeadlines[peer.sessionId] == sample.requestStartedMillis + remaining
    }
}

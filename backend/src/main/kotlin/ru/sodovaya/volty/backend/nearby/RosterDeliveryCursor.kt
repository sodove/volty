package ru.sodovaya.volty.backend.nearby

import java.util.concurrent.CancellationException

sealed interface RosterDeliveryOffer {
    data class Snapshot(val snapshot: ChannelRosterSnapshot) : RosterDeliveryOffer
    data object Idle : RosterDeliveryOffer
    data object Denied : RosterDeliveryOffer
    data object Unavailable : RosterDeliveryOffer
}

/**
 * One serialized connection owner. Invoke bounded blocking ports on an I/O worker.
 * Durable outbox is the buffer; acknowledgement never deletes shared events.
 * Transport must ACK only after successful publication, and retire this owner on
 * disconnect. Reconstruction starts with a fresh complete authorized snapshot.
 * No WebSocket scheduler or production authorization implementation is supplied.
 */
class RosterDeliveryCursor(
    private val actor: String,
    private val channel: String,
    private val journal: RosterJournalReader,
    private val snapshots: RosterEndpointPort,
) {
    private var watermark: Long? = null
    private var lastVerified: ChannelRosterSnapshot? = null
    private var offered: ChannelRosterSnapshot? = null
    private var closed = false
    var acknowledgedRevision: Long? = null
        private set

    fun offer(): RosterDeliveryOffer {
        offered = null // An unsuccessful new attempt cannot authorize an old publication ACK.
        if (closed) return RosterDeliveryOffer.Denied
        return try {
            if (watermark == null) watermark = journal.revision().also { require(it >= 0) }
            val acknowledged = acknowledgedRevision
            // An earlier revision returned before authorization on journal failure.
            // Keep journal-before-snapshot ordering, but still observe revoked access.
            var journalUnavailable = false
            val events = try {
                acknowledged?.let { journal.eventsAfter(it, BATCH_SIZE) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                journalUnavailable = true
                emptyList()
            }
            var previous = acknowledged ?: 0
            if (events.size > BATCH_SIZE) journalUnavailable = true
            if (!journalUnavailable) for (event in events) {
                if (event.revision <= previous) {
                    journalUnavailable = true
                    break
                }
                previous = event.revision
            }
            // Reauthorize even when replaying an unsent offer or polling an unchanged channel.
            val snapshot = snapshots.snapshot(actor, channel) ?: run {
                close()
                return RosterDeliveryOffer.Denied
            }
            if (journalUnavailable) return RosterDeliveryOffer.Unavailable
            val floor = maxOf(watermark!!, acknowledged ?: 0, lastVerified?.revision ?: 0, previous)
            if (snapshot.channelId != channel || snapshot.revision < floor ||
                snapshot.serverTimeEpochMillis < 0 || !validPeers(snapshot.peers)) return RosterDeliveryOffer.Unavailable
            val normalized = snapshot.copy(peers = snapshot.peers.sortedBy { it.actorId })
            if (normalized.revision == lastVerified?.revision && normalized.peers != lastVerified?.peers) {
                return RosterDeliveryOffer.Unavailable
            }
            lastVerified = normalized
            if (acknowledged != null && normalized.revision == acknowledged) RosterDeliveryOffer.Idle
            else {
                offered = normalized
                RosterDeliveryOffer.Snapshot(normalized)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RosterDeliveryOffer.Unavailable
        }
    }

    fun acknowledge(revision: Long): Boolean {
        if (closed || offered?.revision != revision) return false
        acknowledgedRevision = revision
        offered = null
        return true
    }

    fun close() { closed = true; offered = null }

    private fun validPeers(peers: List<ChannelRosterPeer>): Boolean {
        if (peers.map { it.actorId }.toSet().size != peers.size || peers.map { it.sessionId }.toSet().size != peers.size) return false
        return peers.all {
            it.actorId.isNotBlank() && it.sessionId.isNotBlank() && it.operationId.isNotBlank() && it.deadlineEpochMillis >= 0 &&
                (it.participantSid == null || it.participantSid.isNotBlank()) &&
                (it.phase == RosterConnectionPhase.CONNECTED) == (it.mediaPresence == MediaPresence.CONNECTED) &&
                (it.mediaPresence != MediaPresence.CONNECTED || it.participantSid != null)
        }
    }

    private companion object { const val BATCH_SIZE = 100 }
}

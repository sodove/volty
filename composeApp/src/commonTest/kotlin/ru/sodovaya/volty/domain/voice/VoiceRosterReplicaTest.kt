package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class VoiceRosterReplicaTest {
    private val tag = VoiceRosterTag("channel", 1)
    private val peer = VoiceRosterPeer("actor", VoiceSessionId("session"), VoiceOperationId("op"),
        VoiceRosterPhase.CONNECTED, 30_000, "PA_live", true)
    private fun initial() = VoiceRosterReplica(tag)
    private fun snapshot(revision: Long, peers: List<VoiceRosterPeer> = listOf(peer), owner: VoiceRosterTag = tag) =
        VoiceRosterUpdate(owner, revision, peers)

    @Test fun newerFullSnapshotReplacesRowsAndEmptySnapshotClears() {
        val joined = initial().receive(snapshot(2))
        assertEquals(listOf(peer), joined.replica.currentPeers)
        val left = joined.replica.receive(snapshot(3, emptyList()))
        assertEquals(emptyList(), left.replica.currentPeers)
        assertEquals(3, left.replica.revision)
        assertFalse(left.requestResync)
    }

    @Test fun oldChannelGenerationAndRevisionCannotTouchCurrentRows() {
        val current = initial().receive(snapshot(4)).replica
        for (update in listOf(snapshot(3, emptyList()), snapshot(9, emptyList(), tag.copy(channelId = "old")),
            snapshot(9, emptyList(), tag.copy(generation = 0)))) {
            assertEquals(VoiceRosterResult(current, false), current.receive(update))
        }
    }

    @Test fun sameRevisionIsIdempotentAcrossRowOrderButConflictRequiresResync() {
        val other = peer.copy(actorId = "other", sessionId = VoiceSessionId("second"))
        val current = initial().receive(snapshot(4, listOf(peer, other))).replica
        assertEquals(VoiceRosterResult(current, false), current.receive(snapshot(4, listOf(other, peer))))
        val conflict = current.receive(snapshot(4, listOf(peer)))
        assertTrue(conflict.requestResync)
        assertNull(conflict.replica.currentPeers)
        assertEquals(4, conflict.replica.revision)
        assertFalse(conflict.replica.receive(snapshot(4, listOf(peer))).requestResync)
        assertEquals(current, conflict.replica.receive(snapshot(4, listOf(other, peer))).replica)
    }

    @Test fun incoherentConfirmedOrDuplicateRowsCannotEarnCurrentCount() {
        val current = initial().receive(snapshot(1)).replica
        val invalid = listOf(listOf(peer, peer.copy(sessionId = VoiceSessionId("duplicate-actor"))),
            listOf(peer, peer.copy(actorId = "other")), listOf(peer.copy(participantSid = null)),
            listOf(peer.copy(transportConfirmed = false)), listOf(peer.copy(phase = VoiceRosterPhase.CONNECTING)),
            listOf(peer.copy(deadlineEpochMillis = -1)))
        for (peers in invalid) {
            val result = current.receive(snapshot(2, peers))
            assertTrue(result.requestResync)
            assertNull(result.replica.currentPeers)
            assertEquals(1, result.replica.revision)
        }
    }

    @Test fun disconnectRequestsOneResyncAndEqualValidSnapshotRestoresReplica() {
        val current = initial().receive(snapshot(2)).replica
        assertEquals(VoiceRosterResult(current, false), current.disconnected(tag.copy(generation = 0)))
        val disconnected = current.disconnected(tag)
        assertTrue(disconnected.requestResync)
        assertNull(disconnected.replica.currentPeers)
        assertFalse(disconnected.replica.disconnected(tag).requestResync)
        assertEquals(current, disconnected.replica.receive(snapshot(2)).replica)
    }
}

package ru.sodovaya.volty.backend.nearby

import kotlin.test.*

class SessionRosterDeliveryTest {
    private val calls = mutableListOf<String>()
    private var allowed = true
    private var fail = false
    private var head = 1L
    private var snapshot = ChannelRosterSnapshot("channel", 1, 100, emptyList())
    private val reader = object : RosterJournalReader {
        override fun revision(): Long { calls += "register"; return head }
        override fun eventsAfter(revision: Long, limit: Int): List<RosterJournalEvent> {
            calls += "events:$revision"
            if (fail) error("private transport details")
            return (revision + 1..head).map { RosterJournalEvent(it, "actor", "op", "other-channel", "s$it", OperationPhase.ACTIVE, 30_000) }
        }
    }
    private fun cursor() = RosterDeliveryCursor("actor", "channel", reader, RosterEndpointPort { actor, channel ->
        assertEquals("actor", actor); assertEquals("channel", channel)
        calls += "authorize"
        snapshot.takeIf { allowed }
    })

    @Test fun registerPrecedesInitialAuthorizedSnapshotAndNoPositionMovesBeforeAck() {
        val cursor = cursor()
        assertEquals(snapshot, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot)
        assertEquals(listOf("register", "authorize"), calls)
        assertNull(cursor.acknowledgedRevision)
        assertTrue(cursor.acknowledge(1))
        assertEquals(1, cursor.acknowledgedRevision)
        assertIs<RosterDeliveryOffer.Idle>(cursor.offer())
    }

    @Test fun failedPublicationRepeatsOfferAndOldAckCannotSkipNewerOne() {
        val cursor = cursor()
        cursor.offer()
        assertEquals(snapshot, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot)
        snapshot = snapshot.copy(revision = 3); head = 3
        assertEquals(3, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot.revision)
        assertFalse(cursor.acknowledge(1))
        assertNull(cursor.acknowledgedRevision)
        assertTrue(cursor.acknowledge(3))
        assertFalse(cursor.acknowledge(3))
    }

    @Test fun readerFailureCannotAdvanceCursorAndRetryReauthorizes() {
        val cursor = cursor(); cursor.offer(); cursor.acknowledge(1)
        head = 2; snapshot = snapshot.copy(revision = 2); fail = true
        val failure = cursor.offer()
        assertIs<RosterDeliveryOffer.Unavailable>(failure)
        assertFalse(failure.toString().contains("private"))
        assertEquals(1, cursor.acknowledgedRevision)
        fail = false
        assertEquals(2, assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer()).snapshot.revision)
        assertEquals("authorize", calls.last())
        assertTrue(cursor.acknowledge(2))
    }

    @Test fun journalFailureStillChecksAccessAndPermanentlyRetiresDeniedOwner() {
        val cursor = cursor(); cursor.offer(); assertTrue(cursor.acknowledge(1))
        fail = true; allowed = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertEquals("authorize", calls.last(), "Authorization must actually be issued despite journal failure")
        assertEquals(1, cursor.acknowledgedRevision)
        assertFalse(cursor.acknowledge(1))
        allowed = true; fail = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
    }

    @Test fun oversizedJournalBatchStillChecksAccessAndCannotAdvancePosition() {
        val cursor = cursor(); cursor.offer(); assertTrue(cursor.acknowledge(1))
        head = 102; allowed = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertEquals("authorize", calls.last(), "Malformed batch must not bypass the authorization port")
        assertEquals(1, cursor.acknowledgedRevision)
        assertFalse(cursor.acknowledge(102))
        allowed = true
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
    }

    @Test fun revokedReaderCannotReplayPreviouslyOfferedPrivateRows() {
        val cursor = cursor(); cursor.offer()
        allowed = false
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertFalse(cursor.acknowledge(1))
        allowed = true
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer(), "Retired cursor cannot revive")
        assertIs<RosterDeliveryOffer.Snapshot>(cursor().offer())
    }

    @Test fun wrongChannelAndRegressingSnapshotNeverProduceDelivery() {
        val cursor = cursor(); cursor.offer(); cursor.acknowledge(1)
        head = 2
        assertIs<RosterDeliveryOffer.Unavailable>(cursor.offer(), "Snapshot older than durable batch is rejected")
        snapshot = snapshot.copy(channelId = "private-other", revision = 2)
        assertIs<RosterDeliveryOffer.Unavailable>(cursor.offer())
        assertFalse(cursor.acknowledge(2))
        assertEquals(1, cursor.acknowledgedRevision)
    }

    @Test fun reconstructedConsumerGetsFreshCompleteStateInsteadOfOldPendingOffer() {
        val abandoned = cursor(); abandoned.offer()
        snapshot = snapshot.copy(revision = 4); head = 4
        val fresh = cursor()
        assertEquals(4, assertIs<RosterDeliveryOffer.Snapshot>(fresh.offer()).snapshot.revision)
        assertNull(abandoned.acknowledgedRevision)
        assertTrue(fresh.acknowledge(4))
        assertIs<RosterDeliveryOffer.Idle>(fresh.offer())
    }

    @Test fun incoherentConfirmedRowsAndConflictingSameRevisionCannotBeOffered() {
        val peer = ChannelRosterPeer("actor", "s", "op", RosterConnectionPhase.CONNECTED, 30_000, MediaPresence.CONNECTED, null)
        snapshot = snapshot.copy(peers = listOf(peer))
        val cursor = cursor()
        assertIs<RosterDeliveryOffer.Unavailable>(cursor.offer())
        snapshot = snapshot.copy(peers = listOf(peer.copy(participantSid = "PA_live")))
        assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer())
        snapshot = snapshot.copy(peers = emptyList())
        assertIs<RosterDeliveryOffer.Unavailable>(cursor.offer())
        assertFalse(cursor.acknowledge(1), "Invalid attempt retires the previous offer")
    }

    @Test fun closeRetiresOfferAndEmptyRevisionZeroIsStillAnInitialSnapshot() {
        head = 0; snapshot = snapshot.copy(revision = 0)
        val cursor = cursor()
        assertIs<RosterDeliveryOffer.Snapshot>(cursor.offer())
        assertTrue(cursor.acknowledge(0))
        cursor.close()
        assertIs<RosterDeliveryOffer.Denied>(cursor.offer())
        assertFalse(cursor.acknowledge(0))
    }
}

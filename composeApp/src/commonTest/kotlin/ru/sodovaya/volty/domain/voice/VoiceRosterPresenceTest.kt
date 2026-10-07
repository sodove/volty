package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class VoiceRosterPresenceTest {
    private val tag = VoiceRosterTag("channel", 1)
    private val sid = VoiceSessionId("session")
    private fun peer(phase: VoiceRosterPhase = VoiceRosterPhase.CONNECTED, deadline: Long = 1_000) =
        VoiceRosterPeer("actor", sid, VoiceOperationId("op"), phase, deadline,
            if (phase == VoiceRosterPhase.CONNECTED) "sid" else null, phase == VoiceRosterPhase.CONNECTED)
    private fun sample(revision: Long = 1, ordinal: Long = 0, rows: List<VoiceRosterPeer> = listOf(peer()),
                       start: Long = 100, received: Long = 120, server: Long = 500,
                       owner: VoiceRosterTag = tag, credit: Map<VoiceSessionId, Long>? = null) =
        VoiceRosterSnapshotReply.Received(VoiceRosterObservation(VoiceRosterUpdate(owner, revision, rows),
            VoiceLeaseRequestId(ordinal), start, received, server,
            credit ?: rows.associate { it.sessionId to start + (it.deadlineEpochMillis - server).coerceAtLeast(0) }))

    @Test fun initialUnknownAndAuthoritativeEmptyAreDistinct() {
        val owner = VoiceRosterPresence(tag)
        assertNull(owner.view.connectedCount)
        assertNull(owner.view.peers)
        owner.complete(owner.beginRead()!!, sample(rows = emptyList()), 120)
        assertEquals(0, owner.view.connectedCount)
        assertEquals(emptyList(), owner.view.peers)
    }

    @Test fun sameRevisionRenameKeepsReplicaAndOriginalLeaseWhileOldRevisionCannotRepaint() {
        val owner=VoiceRosterPresence(tag)
        val first=sample(revision=3).copy(observation=sample(revision=3).observation.copy(profileNames=mapOf("actor" to "Old")))
        owner.complete(owner.beginRead()!!,first,120);assertEquals(mapOf("actor" to "Old"),owner.view.profileNames)
        val renamed=sample(revision=3,ordinal=1,start=200,received=220,server=600).let {
            it.copy(observation=it.observation.copy(profileNames=mapOf("actor" to "Саша","outsider" to "Private")))
        }
        owner.complete(owner.beginRead()!!,renamed,220)
        assertEquals(3L,owner.view.revision);assertEquals(peer(),owner.view.peers!!.single().peer)
        assertEquals(mapOf("actor" to "Саша"),owner.view.profileNames);assertEquals(1,owner.view.connectedCount)
        val old=sample(revision=2,ordinal=2,start=300,received=320,server=700).let {
            it.copy(observation=it.observation.copy(profileNames=mapOf("actor" to "Obsolete")))
        }
        owner.complete(owner.beginRead()!!,old,320);assertEquals(mapOf("actor" to "Саша"),owner.view.profileNames)
        owner.tick(600);assertTrue(owner.view.profileNames.isEmpty());assertEquals(0,owner.view.connectedCount)
    }
    @Test fun optionalInvalidNameCannotEarnRowsOrSurviveFailureAndRetirement() {
        for(name in listOf(" ","x".repeat(81),"bad\nname")) {
            val owner=VoiceRosterPresence(tag);val reply=sample().let {it.copy(observation=it.observation.copy(profileNames=mapOf("actor" to name)))}
            owner.complete(owner.beginRead()!!,reply,120);assertTrue(owner.view.profileNames.isEmpty());assertEquals(1,owner.view.connectedCount)
        }
        val owner=VoiceRosterPresence(tag);val reply=sample().let {it.copy(observation=it.observation.copy(profileNames=mapOf("actor" to "Rider")))}
        owner.complete(owner.beginRead()!!,reply,120);assertEquals("Rider",owner.view.profileNames["actor"])
        owner.complete(owner.beginRead()!!,VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE),130)
        assertTrue(owner.view.profileNames.isEmpty());assertNull(owner.view.peers)
        owner.close();assertTrue(owner.view.profileNames.isEmpty())
    }
    @Test fun onlyFreshConfirmedPeersCountAndExpiryDoesNotChangePersistedRevision() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(), 120)
        assertEquals(1, owner.view.connectedCount)
        assertTrue(owner.view.peers!!.single().leaseFresh)
        owner.tick(600)
        assertEquals(0, owner.view.connectedCount)
        assertFalse(owner.view.peers!!.single().leaseFresh)
        assertEquals(1L, owner.view.revision)
        assertEquals(peer(), owner.view.peers!!.single().peer)
        val connecting = VoiceRosterPresence(tag)
        connecting.complete(connecting.beginRead()!!, sample(rows = listOf(peer(VoiceRosterPhase.CONNECTING))), 120)
        assertEquals(0, connecting.view.connectedCount)
        assertTrue(connecting.view.peers!!.single().leaseFresh)
    }

    @Test fun latestIssuedReadOwnsFailuresAndSuccessesEvenBeforeLatestCompletion() {
        val owner = VoiceRosterPresence(tag)
        val old = owner.beginRead()!!
        val current = owner.beginRead()!!
        owner.complete(old, sample(), 120)
        assertNull(owner.view.connectedCount)
        owner.complete(current, sample(ordinal = 1), 120)
        owner.complete(old, VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.ACCESS_DENIED), 800)
        assertEquals(1, owner.view.connectedCount)
        assertFalse(owner.view.retired)
        owner.complete(current, VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.ACCESS_DENIED), 800)
        assertEquals(1, owner.view.connectedCount)
    }

    @Test fun foreignTokensAndForeignPayloadNeverBecomeCurrent() {
        val owner = VoiceRosterPresence(tag)
        val other = VoiceRosterPresence(tag.copy(generation = 2))
        owner.complete(other.beginRead()!!, sample(), 120)
        assertNull(owner.view.connectedCount)
        val result = owner.complete(owner.beginRead()!!, sample(owner = tag.copy(generation = 2)), 120)
        assertTrue(result.requestResync)
        assertNull(owner.view.connectedCount)
    }

    @Test fun sameRevisionConflictRequestsResyncOnceAndOriginalContentRestores() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(), 120)
        assertTrue(owner.complete(owner.beginRead()!!, sample(ordinal = 1, rows = emptyList()), 120).requestResync)
        assertNull(owner.view.connectedCount)
        assertFalse(owner.complete(owner.beginRead()!!, sample(ordinal = 2, rows = emptyList()), 120).requestResync)
        owner.complete(owner.beginRead()!!, sample(ordinal = 3), 120)
        assertEquals(1, owner.view.connectedCount)
    }

    @Test fun staleRevisionCannotReplaceOrRefreshAndFreshSameRevisionCanRefreshConservativeCredit() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(revision = 2), 120)
        owner.tick(600)
        owner.complete(owner.beginRead()!!, sample(revision = 1, ordinal = 1, start = 600, received = 620), 620)
        assertEquals(0, owner.view.connectedCount)
        assertEquals(2L, owner.view.revision)
        owner.complete(owner.beginRead()!!, sample(revision = 2, ordinal = 2, start = 620, received = 640), 640)
        assertEquals(1, owner.view.connectedCount)
    }

    @Test fun rollbackAfterReceiptPermanentlyRetiresEvenWhenStillAboveOriginalReceipt() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(), 120)
        owner.tick(400)
        owner.tick(300)
        assertTrue(owner.view.retired)
        assertEquals(VoiceRosterUnknown.CLOCK_RESET, owner.view.unknown)
        assertNull(owner.beginRead())
        assertNull(owner.view.connectedCount)
    }

    @Test fun denialRetiresWhileUnavailableRequiresExplicitFreshRead() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(), 120)
        assertTrue(owner.complete(owner.beginRead()!!,
            VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.UNAVAILABLE), 130).requestResync)
        assertNull(owner.view.connectedCount)
        owner.complete(owner.beginRead()!!, sample(ordinal = 1), 140)
        assertEquals(1, owner.view.connectedCount)
        owner.complete(owner.beginRead()!!, VoiceRosterSnapshotReply.Failed(VoiceRosterFailure.ACCESS_DENIED), 150)
        assertTrue(owner.view.retired)
        assertNull(owner.view.connectedCount)
        assertNull(owner.beginRead())
    }

    @Test fun incoherentLocalCreditAndNonIncreasingHttpOrdinalCannotEarnPresence() {
        val owner = VoiceRosterPresence(tag)
        assertTrue(owner.complete(owner.beginRead()!!,
            sample(credit = mapOf(sid to 700)), 120).requestResync)
        assertNull(owner.view.connectedCount)
        owner.complete(owner.beginRead()!!, sample(ordinal = 1), 120)
        assertEquals(1, owner.view.connectedCount)
        assertTrue(owner.complete(owner.beginRead()!!, sample(ordinal = 1), 130).requestResync)
        assertNull(owner.view.connectedCount)
    }

    @Test fun closureAndDisconnectionInvalidateReadsWithoutChangingMembershipOrRows() {
        val owner = VoiceRosterPresence(tag)
        owner.complete(owner.beginRead()!!, sample(), 120)
        val pending = owner.beginRead()!!
        assertTrue(owner.disconnected(130).requestResync)
        owner.complete(pending, sample(ordinal = 1), 140)
        assertNull(owner.view.connectedCount)
        owner.complete(owner.beginRead()!!, sample(ordinal = 2), 150)
        assertEquals(1, owner.view.connectedCount)
        owner.close()
        assertTrue(owner.view.retired)
        assertNull(owner.view.connectedCount)
        assertNull(owner.beginRead())
    }
}

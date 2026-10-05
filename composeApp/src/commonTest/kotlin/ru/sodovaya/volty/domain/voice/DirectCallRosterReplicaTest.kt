package ru.sodovaya.volty.domain.voice

import kotlin.test.*

class DirectCallRosterReplicaTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val peer = "20000000-0000-0000-0000-000000000002"
    private val call = "30000000-0000-0000-0000-000000000003"
    private val own = VoiceSessionId("40000000-0000-0000-0000-000000000004")
    private val other = VoiceSessionId("50000000-0000-0000-0000-000000000005")
    private val tag = DirectCallRosterTag(call, peer, 7)
    private fun observation() = DirectCallRosterObservation(tag, 8, listOf(
        DirectCallRosterPeer(actor, own, VoiceRosterPhase.CONNECTING, null, false),
        DirectCallRosterPeer(peer, other, VoiceRosterPhase.CONNECTED, "actual-sid", true)),
        VoiceLeaseRequestId(1), 100, 180, 100000, 1100)
    private fun DirectCallRosterReplica.receive(value: DirectCallRosterObservation) = receive(DirectCallRosterReply.Received(value))

    @Test fun actualPairPresenceExpiresAndDoesNotTurnRegisteredParticipantIntoConfirmedTransport() {
        val initial = DirectCallRosterReplica(tag, actor)
        assertNull(initial.currentPeers(180))
        val state = initial.receive(observation())
        assertEquals(setOf(actor, peer), assertNotNull(state.currentPeers(180)).map {it.actorId}.toSet())
        assertEquals(listOf(peer), assertNotNull(state.confirmedPeers(180)).map {it.actorId})
        assertNull(state.currentPeers(179)); assertNull(state.currentPeers(1100)); assertNull(state.confirmedPeers(1100))
    }

    @Test fun olderOrdinalRevisionAndForeignSubscriptionCannotReplaceOrExtendOriginalEvidence() {
        val original = observation(); val state = DirectCallRosterReplica(tag, actor).receive(original)
        assertEquals(2, assertNotNull(state.currentPeers(180)).size)
        for (foreign in listOf(original.copy(requestId = VoiceLeaseRequestId(0), leaseDeadlineMillis = 2100),
            original.copy(revision = 7, requestId = VoiceLeaseRequestId(2), leaseDeadlineMillis = 2100),
            original.copy(tag = tag.copy(generation = 8)), original.copy(tag = tag.copy(callId = actor))))
            assertEquals(state, state.receive(foreign))
        assertNull(state.currentPeers(1100))
    }

    @Test fun equalRevisionContradictionAndChangedOriginalSessionRequireUnknownPresence() {
        val original = observation(); val state = DirectCallRosterReplica(tag, actor).receive(original)
        assertEquals(2, assertNotNull(state.currentPeers(180)).size)
        for (contradiction in listOf(original.copy(requestId = VoiceLeaseRequestId(2), leaseDeadlineMillis = 2100),
            original.copy(requestId = VoiceLeaseRequestId(2), peers = original.peers.map {it.copy(phase = VoiceRosterPhase.CONNECTING, transportConfirmed = false)}),
            original.copy(revision = 9, requestId = VoiceLeaseRequestId(2), peers = original.peers.map {if (it.actorId == actor) it.copy(sessionId = VoiceSessionId(call)) else it}))) {
            val unknown = state.receive(contradiction)
            assertNull(unknown.currentPeers(180)); assertTrue(unknown.resyncRequired)
        }
    }

    @Test fun transportFailureWithdrawsRowsAndPermanentDenialCannotRevive() {
        val original = observation(); val state = DirectCallRosterReplica(tag, actor).receive(original)
        assertEquals(2, assertNotNull(state.currentPeers(180)).size)
        val temporary = state.receive(DirectCallRosterReply.Failed(VoiceRosterFailure.UNAVAILABLE))
        assertNull(temporary.currentPeers(180)); assertTrue(temporary.resyncRequired)
        val fresh = original.copy(requestId = VoiceLeaseRequestId(2), requestStartedMillis = 200, responseReceivedMillis = 250,
            serverTimeEpochMillis = 100100, leaseDeadlineMillis = 1100)
        assertEquals(2, assertNotNull(temporary.receive(fresh).currentPeers(250)).size)
        for (failure in listOf(VoiceRosterFailure.UNAUTHORIZED, VoiceRosterFailure.ACCESS_DENIED)) {
            val retired = state.receive(DirectCallRosterReply.Failed(failure))
            assertTrue(retired.retired); assertEquals(retired, retired.receive(fresh)); assertNull(retired.currentPeers(250))
        }
        assertEquals(state.retire(), state.retire().receive(fresh))
    }

    @Test fun deliberatelyIncoherentCompleteLookingPairNeverEarnsCurrentPresence() {
        val original = observation()
        for (invalid in listOf(original.copy(peers = original.peers.map {it.copy(transportConfirmed = true)}),
            original.copy(peers = listOf(original.peers.first(), original.peers.first())),
            original.copy(peers = original.peers.map {it.copy(actorId = call)}),
            original.copy(responseReceivedMillis = 99), original.copy(leaseDeadlineMillis = 30101),
            original.copy(peers = original.peers.map {it.copy(participantSid = " ")}))) {
            val state = DirectCallRosterReplica(tag, actor).receive(invalid)
            assertNull(state.currentPeers(180)); assertTrue(state.resyncRequired)
        }
    }

    @Test fun newActualRevisionCanUpdateTransportAndCreditWithoutChangingOriginalPairIdentity() {
        val original = observation(); val state = DirectCallRosterReplica(tag, actor).receive(original)
        assertEquals(2, assertNotNull(state.currentPeers(180)).size)
        val renewed = original.copy(revision = 9, requestId = VoiceLeaseRequestId(2), peers = original.peers.reversed().map {
            if (it.actorId == actor) it.copy(phase = VoiceRosterPhase.CONNECTED, participantSid = "own-actual-sid", transportConfirmed = true) else it
        }, leaseDeadlineMillis = 2100)
        val updated = state.receive(renewed)
        assertEquals(setOf(actor, peer), assertNotNull(updated.confirmedPeers(2000)).map {it.actorId}.toSet())
        assertEquals(listOf(own, other), assertNotNull(updated.currentPeers(2000)).map {it.sessionId})
        assertNull(updated.currentPeers(2100)); assertEquals(updated, updated.receive(original))
    }
}

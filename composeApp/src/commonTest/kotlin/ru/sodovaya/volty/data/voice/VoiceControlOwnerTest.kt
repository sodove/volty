package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControlOwnerTest {
    @Test fun normal_heartbeat_cancellation_stops_cleanly_without_claiming_remote_cleanup() = runTest {
        val port = FakePort(cleanupPending = true)
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.heartbeat(); runCurrent()
        assertEquals(listOf(tag(1)), port.heartbeats)
        port.heartbeatReply.complete(VoiceHttpReply.Ended(tag(1).generation, tag(1).sessionId, "CANCELLED", true)); runCurrent()
        assertEquals(VoicePhase.STOPPING, owner.state.value.phase)
        assertFalse(owner.state.value.wanted)
        assertEquals(listOf(tag(1)), port.disconnects)
        assertTrue(effects.any {it.effect == VoiceSessionEffect.StopEngine(tag(1).generation)})
        assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.pendingRemoteStops.value)
        owner.event(VoiceSessionEvent.Stopped(tag(1).generation)); runCurrent()
        assertEquals(VoicePhase.IDLE, owner.state.value.phase)
        assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.pendingRemoteStops.value)
    }

    @Test fun expired_heartbeat_is_still_a_failure() = runTest {
        val port = FakePort()
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.heartbeat(); runCurrent()
        port.heartbeatReply.complete(VoiceHttpReply.Ended(tag(1).generation, tag(1).sessionId, "EXPIRED", false)); runCurrent()
        assertEquals(VoicePhase.FAILED, owner.state.value.phase)
        assertEquals(listOf(tag(1)), port.disconnects)
    }

    @Test fun stale_cancelled_heartbeat_cannot_end_the_new_generation() = runTest {
        val port = FakePort()
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.heartbeat(); runCurrent()
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        port.heartbeatReply.complete(VoiceHttpReply.Ended(tag(1).generation, tag(1).sessionId, "CANCELLED", false)); runCurrent()
        assertTrue(owner.state.value.wanted)
        assertEquals(tag(2).sessionId, owner.state.value.sessionId)
        assertFalse(tag(2) in port.disconnects)
    }
    private val op1 = VoiceOperationId("one")
    private val op2 = VoiceOperationId("two")
    private fun tag(n: Long) = VoiceSessionTag(VoiceSessionId("s$n"), VoiceGeneration(n))
    private fun grant(n: Long, ordinal: Long = 0) = VoiceHttpReply.Credentials(tag(n),
        VoiceConnectionCredentials("wss://example.invalid", "secret"),
        VoiceLeaseGrant(VoiceLeaseRequestId(ordinal), 0, 1, 5000))

    @Test fun sharingReplacementDispatchRetainsOldOriginAfterPublicStateChanges() = runTest {
        val port=FakePort();val effects=mutableListOf<VoiceControlDispatch>()
        val owner=VoiceControlOwner(backgroundScope,port,effects::add)
        owner.join("channel-one",op1);runCurrent();port.joinReplies.getValue(op1).complete(grant(1));runCurrent()
        owner.event(VoiceSessionEvent.EnableSharing(op1));runCurrent()
        assertTrue(owner.state.value.sharingEnabled)
        assertEquals(tag(1),effects.last {it.effect==VoiceSessionEffect.AddNearbyDemand}.sharingOrigin)
        effects.clear();owner.join("channel-two",op2);runCurrent()
        assertEquals(VoiceGeneration(2),owner.state.value.generation)
        val removed=effects.filter {it.effect==VoiceSessionEffect.RemoveNearbyDemand || it.effect==VoiceSessionEffect.ClearSharingQueue}
        assertEquals(listOf(VoiceSessionEffect.RemoveNearbyDemand,VoiceSessionEffect.ClearSharingQueue),removed.map {it.effect})
        assertEquals(listOf(tag(1),tag(1)),removed.map {it.sharingOrigin})
        port.joinReplies.getValue(op2).complete(grant(2));runCurrent();owner.event(VoiceSessionEvent.EnableSharing(op2));runCurrent()
        assertEquals(tag(2),effects.last {it.effect==VoiceSessionEffect.AddNearbyDemand}.sharingOrigin)
        effects.clear();owner.event(VoiceSessionEvent.HidePosition);runCurrent()
        assertEquals(listOf(tag(2),tag(2)),effects.map {it.sharingOrigin})
        assertTrue(owner.state.value.wanted);assertFalse(owner.state.value.sharingEnabled)
    }

    @Test fun stopCancelsIssuedJoinAndDoesNotWaitForNetwork() = runTest {
        val port = FakePort()
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("channel-one", op1)
        runCurrent()
        assertEquals(listOf(Triple(op1, VoiceGeneration(1), "channel-one")), port.joins)
        owner.stop()
        runCurrent()
        assertEquals(listOf(op1 to VoiceGeneration(1)), port.cancels)
        assertTrue(port.joinCancelled)
        assertFalse(owner.state.value.wanted)
        assertTrue(effects.any { it.effect == VoiceSessionEffect.StopEngine(VoiceGeneration(1)) })
        assertFalse(effects.any { it.effect is VoiceSessionEffect.StartEngine })
        val sharingCleanup=effects.filter {it.effect==VoiceSessionEffect.RemoveNearbyDemand || it.effect==VoiceSessionEffect.ClearSharingQueue}
        assertEquals(2,sharingCleanup.size);assertTrue(sharingCleanup.all {it.sharingOrigin==null})
    }

    @Test fun lateGrantAfterReplacementDisconnectsOnlyItsOldSession() = runTest {
        val port = FakePort(ignoreJoinCancellation = true)
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("channel-one", op1); runCurrent()
        owner.join("channel-two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        assertEquals(tag(2).sessionId, owner.state.value.sessionId)
        assertEquals(listOf(tag(1)), port.disconnects)
        val starts = effects.filter { it.effect is VoiceSessionEffect.StartEngine }
        assertEquals(listOf(VoiceSessionEffect.StartEngine(tag(2))), starts.map { it.effect })
        assertNotNull(starts.single().credentials)
    }

    @Test fun pendingRetryKeepsOperationAndChannelWithoutStartingCapture() = runTest {
        val port = FakePort()
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("channel-one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(VoiceHttpReply.Pending(VoiceGeneration(1), VoiceSessionId("s1"), op1)); runCurrent()
        assertNull(owner.state.value.leaseDeadlineMillis)
        assertFalse(effects.any { it.effect is VoiceSessionEffect.StartEngine })
        owner.retryJoin(); runCurrent()
        assertEquals(2, port.joins.size)
        assertEquals(port.joins[0], port.joins[1])
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        assertEquals(VoicePhase.CONNECTING, owner.state.value.phase)
    }

    @Test fun staleHeartbeatCannotRenewNewGeneration() = runTest {
        val port = FakePort()
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.heartbeat(); runCurrent()
        assertEquals(listOf(tag(1)), port.heartbeats)
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        port.heartbeatReply.complete(VoiceHttpReply.Acknowledged(tag(1),
            VoiceLeaseGrant(VoiceLeaseRequestId(5), 100, 101, 30000), true)); runCurrent()
        assertEquals(5000, owner.state.value.leaseDeadlineMillis)
        assertEquals(tag(2).sessionId, owner.state.value.sessionId)
    }

    @Test fun failedCleanupSurvivesEngineStoppedAndRetriesExactOldTag() = runTest {
        val port = FakePort(disconnectFailure = true)
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.stop(); runCurrent()
        owner.event(VoiceSessionEvent.Stopped(VoiceGeneration(1))); runCurrent()
        assertEquals(VoicePhase.IDLE, owner.state.value.phase)
        assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.unconfirmedStops.value)
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        port.disconnectFailure = false
        owner.retryStops(); runCurrent()
        assertEquals(listOf(tag(1), tag(1)), port.disconnects)
        assertTrue(owner.unconfirmedStops.value.isEmpty())
        assertEquals(tag(2).sessionId, owner.state.value.sessionId)
    }

    @Test fun expiredReceiptAndInclusiveTickIssueCleanupWithoutNewCapture() = runTest {
        val port = FakePort()
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(VoiceHttpReply.Expired(tag(1))); runCurrent()
        assertFalse(owner.state.value.wanted)
        assertEquals(listOf(tag(1)), port.disconnects)
        assertFalse(effects.any { it.effect is VoiceSessionEffect.StartEngine })
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        owner.event(VoiceSessionEvent.Tick(5000)); runCurrent()
        assertEquals(listOf(tag(1), tag(2)), port.disconnects)
        assertFalse(owner.state.value.wanted)
    }

    @Test fun throwingJoinCannotLeaveAnUncancellableWantedAttempt() = runTest {
        val port = FakePort(throwJoin = true)
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        assertEquals(1, port.joins.size)
        assertEquals(VoicePhase.FAILED, owner.state.value.phase)
        assertEquals(listOf(op1 to VoiceGeneration(1)), port.cancels)
    }

    @Test fun throwingCleanupRemainsRetryableAfterNewGeneration() = runTest {
        val port = FakePort(throwDisconnect = true)
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.stop(); runCurrent()
        assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.unconfirmedStops.value)
        port.throwDisconnect = false
        owner.retryStops(); runCurrent()
        assertEquals(listOf(tag(1), tag(1)), port.disconnects)
        assertTrue(owner.unconfirmedStops.value.isEmpty())
    }

    @Test fun externalEventsCannotForgeAdmissionOrLeaseCredit() = runTest {
        val owner = VoiceControlOwner(backgroundScope, FakePort()) {}
        assertFailsWith<IllegalArgumentException> { owner.event(VoiceSessionEvent.Join(op1)) }
        assertFailsWith<IllegalArgumentException> { owner.event(VoiceSessionEvent.SessionGranted(op1, VoiceGeneration(1), tag(1).sessionId, grant(1).lease)) }
        assertFailsWith<IllegalArgumentException> { owner.event(VoiceSessionEvent.LeaseAck(tag(1), grant(1).lease)) }
        runCurrent()
        assertFalse(owner.state.value.wanted)
    }

    @Test fun terminalIntentAndEngineReleaseDoNotErasePendingRemoteCleanup() = runTest {
        val port = FakePort(cleanupPending = true)
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.stop(); runCurrent()
        owner.event(VoiceSessionEvent.Stopped(VoiceGeneration(1))); runCurrent()
        assertTrue(owner.unconfirmedStops.value.isEmpty())
        assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.pendingRemoteStops.value)
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        port.cleanupPending = false
        owner.retryStops(); runCurrent()
        assertEquals(listOf(tag(1), tag(1)), port.disconnects)
        assertTrue(owner.pendingRemoteStops.value.isEmpty())
        assertEquals(tag(2).sessionId, owner.state.value.sessionId)
    }

    @Test fun missingCleanupFieldRemainsUnknownUntilExplicitCompletion() = runTest {
        val port = FakePort()
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        owner.stop(); runCurrent()
        assertEquals(listOf(op1 to VoiceGeneration(1)), port.cancels)
        assertTrue(owner.unconfirmedStops.value.isEmpty())
        assertEquals(setOf(VoiceStopRequest.Operation(op1, VoiceGeneration(1))), owner.pendingRemoteStops.value)
        port.cleanupPending = false
        owner.retryStops(); runCurrent()
        assertEquals(listOf(op1 to VoiceGeneration(1), op1 to VoiceGeneration(1)), port.cancels)
        assertTrue(owner.pendingRemoteStops.value.isEmpty())
    }

    @Test fun scheduledOldHeartbeatCannotIssueForReplacementTag() = runTest {
        val port = FakePort(); val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent(); port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        owner.join("two", op2); runCurrent(); port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        owner.heartbeat(tag(1)); runCurrent(); assertTrue(port.heartbeats.isEmpty())
        owner.heartbeat(tag(2)); runCurrent(); assertEquals(listOf(tag(2)), port.heartbeats)
        port.heartbeatReply.complete(VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)); runCurrent()
    }
    @Test fun scheduledOldJoinRetryCannotIssueForReplacementOperation() = runTest {
        val port = FakePort(); val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(VoiceHttpReply.Pending(VoiceGeneration(1), null, op1)); runCurrent()
        owner.join("two", op2); runCurrent()
        port.joinReplies.getValue(op2).complete(VoiceHttpReply.Pending(VoiceGeneration(2), null, op2)); runCurrent()
        owner.retryJoin(op1, VoiceGeneration(1)); runCurrent(); assertEquals(2, port.joins.size)
        owner.retryJoin(op2, VoiceGeneration(2)); runCurrent()
        assertEquals(3, port.joins.size); assertEquals(Triple(op2, VoiceGeneration(2), "two"), port.joins.last())
    }

    @Test fun queuedPreviousOperationControlsCannotFollowReplacementJoin() = runTest {
        val port = FakePort(); val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        owner.join("one", op1); runCurrent()
        port.joinReplies.getValue(op1).complete(grant(1)); runCurrent()
        assertEquals(op1, owner.state.value.operationId); assertEquals(tag(1).sessionId, owner.state.value.sessionId)
        owner.join("two", op2)
        // The actor has not consumed its mailbox: producer-side state still advertises op1.
        assertEquals(op1, owner.state.value.operationId)
        owner.event(VoiceSessionEvent.SetMuted(true, op1))
        owner.event(VoiceSessionEvent.SetDeafened(true, op1))
        runCurrent(); assertEquals(op2, owner.state.value.operationId)
        port.joinReplies.getValue(op2).complete(grant(2)); runCurrent()
        assertFalse(owner.state.value.muted); assertFalse(owner.state.value.deafened)
        assertFalse(effects.any {it.effect is VoiceSessionEffect.SetMuted || it.effect is VoiceSessionEffect.SetDeafened})
        val starts = effects.map {it.effect}.filterIsInstance<VoiceSessionEffect.StartEngine>()
        assertEquals(listOf(VoiceSessionEffect.StartEngine(tag(1)), VoiceSessionEffect.StartEngine(tag(2))), starts)
        assertEquals(listOf(tag(1)), port.disconnects)
    }

    private class FakePort(val ignoreJoinCancellation: Boolean = false, var disconnectFailure: Boolean = false,
                           val throwJoin: Boolean = false, var throwDisconnect: Boolean = false,
                           var cleanupPending: Boolean? = null) : VoiceSessionControlPort {
        val joins = mutableListOf<Triple<VoiceOperationId, VoiceGeneration, String>>()
        val joinReplies = mutableMapOf<VoiceOperationId, CompletableDeferred<VoiceHttpReply>>()
        val cancels = mutableListOf<Pair<VoiceOperationId, VoiceGeneration>>()
        val disconnects = mutableListOf<VoiceSessionTag>()
        val heartbeats = mutableListOf<VoiceSessionTag>()
        val heartbeatReply = CompletableDeferred<VoiceHttpReply>()
        var joinCancelled = false
        override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
            joins += Triple(operation, generation, channel)
            if (throwJoin) error("transport failure")
            val reply = CompletableDeferred<VoiceHttpReply>().also { joinReplies[operation] = it }
            return try {
                if (ignoreJoinCancellation) withContext(NonCancellable) { reply.await() } else reply.await()
            } finally { joinCancelled = true }
        }
        override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("status must not mint credentials")
        override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply {
            heartbeats += tag
            return withContext(NonCancellable) { heartbeatReply.await() }
        }
        override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
            cancels += operation to generation
            return VoiceHttpReply.Ended(generation, null, "CANCELLED", cleanupPending)
        }
        override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
            disconnects += tag
            if (throwDisconnect) error("transport failure")
            return if (disconnectFailure) VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
            else VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", cleanupPending)
        }
    }
}

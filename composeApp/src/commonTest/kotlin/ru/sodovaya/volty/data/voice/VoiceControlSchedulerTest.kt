package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControlSchedulerTest {
    private fun tag(n: Long) = VoiceSessionTag(VoiceSessionId("s$n"), VoiceGeneration(n))
    private class Port(private val now: () -> Long) : VoiceSessionControlPort {
        var pending = false; var cleanupPending = false
        val joins = mutableListOf<Triple<VoiceOperationId, VoiceGeneration, String>>()
        val heartbeats = mutableListOf<VoiceSessionTag>(); val disconnects = mutableListOf<VoiceSessionTag>()
        override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
            joins += Triple(operation, generation, channel)
            return if (pending) VoiceHttpReply.Pending(generation, null, operation) else VoiceHttpReply.Credentials(
                VoiceSessionTag(VoiceSessionId("s${generation.value}"), generation), VoiceConnectionCredentials("wss://example.invalid", "secret"),
                VoiceLeaseGrant(VoiceLeaseRequestId(0), now(), now(), 10_000))
        }
        override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("No status polling")
        override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply { heartbeats += tag; return VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE) }
        override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration) = VoiceHttpReply.Ended(generation, null, "CANCELLED", false)
        override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
            disconnects += tag; return VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", cleanupPending)
        }
    }
    @Test fun heartbeatIsIssuedBeforeExpiryButNeverAtInclusiveExpiry() = runTest {
        var now = 0L; val port = Port { now }; val owner = VoiceControlOwner(backgroundScope, port, {})
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        owner.join("channel", VoiceOperationId("op")); runCurrent()
        pulses.send(Unit); runCurrent(); assertTrue(port.heartbeats.isEmpty())
        now = 5000; pulses.send(Unit); runCurrent(); assertEquals(listOf(tag(1)), port.heartbeats)
        now = 10_000; pulses.send(Unit); runCurrent()
        assertEquals(listOf(tag(1)), port.heartbeats); assertFalse(owner.state.value.wanted)
        assertEquals(listOf(tag(1)), port.disconnects)
    }
    @Test fun sleepClockJumpExpiresRatherThanMintingFreshCredit() = runTest {
        var now = 0L; val port = Port { now }; val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add); val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        owner.join("channel", VoiceOperationId("op")); runCurrent(); pulses.send(Unit); runCurrent()
        now = 30_000; pulses.send(Unit); runCurrent()
        assertTrue(port.heartbeats.isEmpty()); assertFalse(owner.state.value.wanted)
        assertNull(owner.state.value.leaseDeadlineMillis)
        assertTrue(effects.any { it.effect == VoiceSessionEffect.StopEngine(tag(1).generation) })
    }
    @Test fun pendingRetryReusesOperationAndCannotResurrectAfterStop() = runTest {
        var now = 0L; val port = Port { now }.also { it.pending = true }
        val owner = VoiceControlOwner(backgroundScope, port, {}); val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        val op = VoiceOperationId("stable")
        owner.join("original-channel", op); runCurrent(); pulses.send(Unit); runCurrent()
        now = 500; pulses.send(Unit); runCurrent(); assertEquals(1, port.joins.size)
        now = 1000; pulses.send(Unit); runCurrent()
        assertEquals(List(2) { Triple(op, VoiceGeneration(1), "original-channel") }, port.joins)
        owner.stop(); runCurrent(); now = 20_000; pulses.send(Unit); runCurrent()
        assertEquals(2, port.joins.size); assertFalse(owner.state.value.wanted); assertTrue(port.heartbeats.isEmpty())
    }
    @Test fun remoteCleanupRetryKeepsOldTagWhileNewSessionStaysWanted() = runTest {
        var now = 0L; val port = Port { now }.also { it.cleanupPending = true }
        val owner = VoiceControlOwner(backgroundScope, port, {}); val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        owner.join("old", VoiceOperationId("one")); runCurrent(); owner.stop(); runCurrent()
        assertTrue(owner.unconfirmedStops.value.isEmpty()); assertEquals(setOf(VoiceStopRequest.Session(tag(1))), owner.pendingRemoteStops.value)
        owner.join("new", VoiceOperationId("two")); runCurrent(); pulses.send(Unit); runCurrent()
        now = 3000; pulses.send(Unit); runCurrent()
        assertEquals(listOf(tag(1), tag(1)), port.disconnects)
        assertEquals(tag(2).sessionId, owner.state.value.sessionId); assertTrue(owner.state.value.wanted)
    }
    @Test fun localCleanupRetryKeepsOriginalGenerationAndStopsWhenCleared() = runTest {
        var now = 0L; var local = setOf(VoiceGeneration(1)); val retried = mutableListOf<VoiceGeneration>()
        val port = Port { now }; val owner = VoiceControlOwner(backgroundScope, port, {}); val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { local }, retried::add)
        owner.join("old", VoiceOperationId("one")); runCurrent(); owner.join("new", VoiceOperationId("two")); runCurrent()
        pulses.send(Unit); runCurrent(); now = 3000; pulses.send(Unit); runCurrent()
        assertEquals(listOf(VoiceGeneration(1)), retried); assertTrue(owner.state.value.wanted)
        local = emptySet(); now = 6000; pulses.send(Unit); runCurrent()
        assertEquals(listOf(VoiceGeneration(1)), retried); assertEquals(tag(2).sessionId, owner.state.value.sessionId)
    }
    @Test fun schedulerCancellationStopsIssuingWorkWithoutInventingRelease() = runTest {
        var now = 0L; val port = Port { now }; val owner = VoiceControlOwner(backgroundScope, port, {})
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val scheduler = VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        owner.join("channel", VoiceOperationId("op")); runCurrent(); pulses.send(Unit); runCurrent()
        scheduler.cancel(); runCurrent(); now = 30_000; pulses.send(Unit); runCurrent()
        assertTrue(port.heartbeats.isEmpty()); assertTrue(port.disconnects.isEmpty()); assertTrue(owner.state.value.wanted)
    }
    @Test fun clockRollbackFailsClosedWithoutRenewing() = runTest {
        var now = 1000L; val port = Port { now }; val owner = VoiceControlOwner(backgroundScope, port, {})
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        VoiceControlScheduler(backgroundScope, owner, pulses.receiveAsFlow(), { now }, { emptySet() }, {})
        owner.join("channel", VoiceOperationId("op")); runCurrent(); pulses.send(Unit); runCurrent()
        now = 900; pulses.send(Unit); runCurrent()
        assertFalse(owner.state.value.wanted); assertTrue(port.heartbeats.isEmpty()); assertEquals(listOf(tag(1)), port.disconnects)
    }
}

package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceControlProducerDrainTest {
    private val operation = VoiceOperationId("10000000-0000-0000-0000-000000000001")
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val session = VoiceSessionId("30000000-0000-0000-0000-000000000003")

    @Test fun queuedJoinCancelledBeforeItsBodyIsStillCountedAndCompletes() = runTest {
        val port = Port(holdJoin = false)
        val owner = VoiceControlOwner(backgroundScope, port) {}
        val counts = mutableListOf<Int>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            owner.inFlight.collect { counts += it }
        }
        try {
            owner.join(channel, operation); owner.stop(); runCurrent()
            assertEquals(0, port.joins)
            assertEquals(1, port.cancels)
            assertTrue(owner.unconfirmedStops.value.isEmpty())
            assertEquals(0, owner.inFlight.value)
            // The queued join and actual cancel each increment once. A body/finally counter
            // misses the first producer entirely because cancellation prevented body entry.
            assertEquals(2, counts.zipWithNext().sumOf { (before, after) -> (after - before).coerceAtLeast(0) })
        } finally { observer.cancel(); port.release.complete(Unit); runCurrent() }
    }

    @Test fun confirmedCancelDoesNotDrainStillRunningJoinProducer() = runTest {
        val port = Port(holdJoin = true)
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        try {
            owner.join(channel, operation); runCurrent()
            assertTrue(port.entered.isCompleted)
            assertEquals(1, owner.inFlight.value)
            owner.stop(); runCurrent()
            assertEquals(1, port.cancels)
            assertFalse(owner.state.value.wanted)
            assertTrue(owner.unconfirmedStops.value.isEmpty())
            assertTrue(owner.pendingRemoteStops.value.isEmpty())
            assertFalse(port.returned)
            assertEquals(1, owner.inFlight.value)
            port.release.complete(Unit); runCurrent()
            assertTrue(port.returned)
            assertEquals(1, port.disconnects)
            assertTrue(owner.unconfirmedStops.value.isEmpty())
            assertTrue(owner.pendingRemoteStops.value.isEmpty())
            assertEquals(0, owner.inFlight.value)
            assertTrue(effects.none { it.effect is VoiceSessionEffect.StartEngine })
        } finally { port.release.complete(Unit); runCurrent() }
    }

    @Test fun confirmedDisconnectDoesNotDrainCancelledHeartbeatUntilItReturns() = runTest {
        val port = Port(holdJoin = false)
        val effects = mutableListOf<VoiceControlDispatch>()
        val owner = VoiceControlOwner(backgroundScope, port, effects::add)
        try {
            owner.join(channel, operation); runCurrent()
            assertEquals(1, effects.count { it.effect is VoiceSessionEffect.StartEngine })
            assertEquals(0, owner.inFlight.value)
            owner.heartbeat(); runCurrent()
            assertTrue(port.entered.isCompleted)
            assertEquals(1, owner.inFlight.value)
            owner.stop(); runCurrent()
            assertEquals(1, port.disconnects)
            assertFalse(owner.state.value.wanted)
            assertTrue(owner.unconfirmedStops.value.isEmpty())
            assertTrue(owner.pendingRemoteStops.value.isEmpty())
            assertFalse(port.returned)
            assertEquals(1, owner.inFlight.value)
            port.release.complete(Unit); runCurrent()
            assertTrue(port.returned)
            assertEquals(0, owner.inFlight.value)
            assertFalse(owner.state.value.wanted)
            assertEquals(1, effects.count { it.effect is VoiceSessionEffect.StartEngine })
        } finally { port.release.complete(Unit); runCurrent() }
    }

    private inner class Port(private val holdJoin: Boolean) : VoiceSessionControlPort {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var returned = false
        var joins = 0
        var cancels = 0
        var disconnects = 0
        private suspend fun held() = withContext(NonCancellable) {
            entered.complete(Unit); release.await(); returned = true
        }
        override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
            joins++
            if (holdJoin) held()
            return VoiceHttpReply.Credentials(VoiceSessionTag(session, generation),
                VoiceConnectionCredentials("wss://example.test", "secret"),
                VoiceLeaseGrant(VoiceLeaseRequestId(0), 100, 100, 10000))
        }
        override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply {
            held()
            return VoiceHttpReply.Acknowledged(tag, VoiceLeaseGrant(VoiceLeaseRequestId(1), 100, 100, 10000), true)
        }
        override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("Unexpected status")
        override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
            cancels++
            return VoiceHttpReply.Ended(generation, null, "CANCELLED", false)
        }
        override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
            disconnects++
            return VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", false)
        }
    }
}

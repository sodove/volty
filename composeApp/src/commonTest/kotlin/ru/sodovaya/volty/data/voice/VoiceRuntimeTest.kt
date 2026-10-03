package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRuntimeTest {
    private class Port(val clock: () -> Long) : VoiceSessionControlPort {
        var cleanupPending = false
        val joins = mutableListOf<String>(); val disconnects = mutableListOf<VoiceSessionTag>()
        override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
            joins += channel
            return VoiceHttpReply.Credentials(VoiceSessionTag(VoiceSessionId("s${generation.value}"), generation),
                VoiceConnectionCredentials("wss://example.invalid", "secret"),
                VoiceLeaseGrant(VoiceLeaseRequestId(0), clock(), clock(), 10_000))
        }
        override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("Unexpected status")
        override suspend fun heartbeat(tag: VoiceSessionTag) = VoiceHttpReply.Failed(VoiceHttpFailure.UNAVAILABLE)
        override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration) = VoiceHttpReply.Ended(generation, null, "CANCELLED", false)
        override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
            disconnects += tag
            return VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", cleanupPending)
        }
    }
    private class Session : VoiceSdkSession {
        var connects = 0; var closes = 0; var failClose = false
        override suspend fun connect(credentials: VoiceConnectionCredentials) { connects++ }
        override suspend fun publishMicrophone(muted: Boolean) = true
        override suspend fun setMuted(muted: Boolean) = true
        override fun setDeafened(deafened: Boolean) = Unit
        override suspend fun close() { closes++; if (failClose) { failClose = false; error("private") } }
    }
    private class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        var now = 0L
        val port = Port { now }; val session = Session()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val sharing = mutableListOf<VoiceSharingEffect>()
        val runtime = VoiceRuntime(scope, dispatcher, port, VoiceSdkSessionFactory { _, _ -> session },
            pulses.receiveAsFlow(), { now }, sharing::add)
    }
    @Test fun inlineDispatcherIsRejectedBeforeTheOwnerOrSdkStarts() = runTest {
        var created = 0
        val inline = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = false
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
        }
        assertFailsWith<IllegalArgumentException> {
            VoiceRuntime(backgroundScope, inline, Port { 0 }, VoiceSdkSessionFactory { _, _ -> created++; Session() },
                Channel<Unit>().receiveAsFlow(), { 0 }, {})
        }
        assertEquals(0, created)
    }
    @Test fun disconnectReleasesIssuedSdkAndRemoteSessionWithoutMembershipAction() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("channel", VoiceOperationId("op")) }; runCurrent()
        assertEquals(listOf("channel"), f.port.joins); assertEquals(1, f.session.connects)
        backgroundScope.launch { f.runtime.disconnect() }; runCurrent()
        assertFalse(f.runtime.state.value.wanted); assertFalse(f.runtime.state.value.cleanupPending)
        assertEquals(1, f.session.closes); assertEquals(1, f.port.disconnects.size)
        assertTrue(f.runtime.unconfirmedStops.value.isEmpty()); assertTrue(f.runtime.pendingRemoteStops.value.isEmpty())
    }
    @Test fun failedLocalAndRemoteCleanupRetryIndependentlyAfterDisconnect() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("channel", VoiceOperationId("op")) }; runCurrent()
        f.session.failClose = true; f.port.cleanupPending = true
        backgroundScope.launch { f.runtime.disconnect() }; runCurrent()
        assertEquals(setOf(VoiceGeneration(1)), f.runtime.failedLocalCleanup.value)
        assertTrue(f.runtime.pendingRemoteStops.value.isNotEmpty())
        f.pulses.send(Unit); runCurrent(); f.now = 3000; f.pulses.send(Unit); runCurrent()
        assertEquals(2, f.session.closes); assertTrue(f.runtime.failedLocalCleanup.value.isEmpty())
        assertTrue(f.runtime.pendingRemoteStops.value.isNotEmpty()); assertEquals(2, f.port.disconnects.size)
        f.port.cleanupPending = false; f.now = 6000; f.pulses.send(Unit); runCurrent()
        assertTrue(f.runtime.pendingRemoteStops.value.isEmpty()); assertEquals(3, f.port.disconnects.size)
    }
    @Test fun retirementRejectsNewAdmissionButKeepsOriginalCleanupAlive() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("old", VoiceOperationId("op")) }; runCurrent()
        f.port.cleanupPending = true
        backgroundScope.launch { f.runtime.beginRetirement() }; runCurrent()
        var rejected = false
        backgroundScope.launch { rejected = !f.runtime.join("new", VoiceOperationId("new")) }; runCurrent()
        assertTrue(rejected); assertEquals(listOf("old"), f.port.joins); assertEquals(1, f.session.closes)
        f.pulses.send(Unit); runCurrent(); f.now = 3000; f.pulses.send(Unit); runCurrent()
        assertEquals(2, f.port.disconnects.size)
        assertEquals(f.port.disconnects[0], f.port.disconnects[1])
        backgroundScope.launch { f.runtime.enableSharing() }; runCurrent()
        assertFalse(VoiceSharingEffect.ADD_DEMAND in f.sharing)
    }
    @Test fun joiningDoesNotSharePositionAndExplicitHideKeepsVoice() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("channel", VoiceOperationId("op")) }; runCurrent()
        assertTrue(f.sharing.isEmpty()); assertFalse(f.runtime.state.value.sharingEnabled)
        backgroundScope.launch { f.runtime.enableSharing() }; runCurrent()
        assertEquals(listOf(VoiceSharingEffect.ADD_DEMAND), f.sharing)
        backgroundScope.launch { f.runtime.hidePosition() }; runCurrent()
        assertEquals(listOf(VoiceSharingEffect.ADD_DEMAND, VoiceSharingEffect.REMOVE_DEMAND, VoiceSharingEffect.CLEAR_QUEUE), f.sharing)
        assertTrue(f.runtime.state.value.wanted); assertEquals(0, f.session.closes)
    }
    @Test fun sleepJumpExpiresAndClosesTheActuallyConnectedSession() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("channel", VoiceOperationId("op")) }; runCurrent()
        assertEquals(1, f.session.connects)
        f.now = 30_000; f.pulses.send(Unit); runCurrent()
        assertFalse(f.runtime.state.value.wanted); assertEquals(1, f.session.closes)
        assertEquals(1, f.port.disconnects.size)
    }
    @Test fun expiredLeaseCannotEnablePositionEvenBeforeTheNextPulse() = runTest {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        backgroundScope.launch { f.runtime.join("channel", VoiceOperationId("op")) }; runCurrent()
        assertEquals(1, f.session.connects)
        f.now = 10_000
        backgroundScope.launch { f.runtime.enableSharing() }; runCurrent()
        assertTrue(f.sharing.isEmpty()); assertFalse(f.runtime.state.value.sharingEnabled)
        f.pulses.send(Unit); runCurrent()
        assertFalse(f.runtime.state.value.wanted); assertEquals(1, f.session.closes)
    }
}

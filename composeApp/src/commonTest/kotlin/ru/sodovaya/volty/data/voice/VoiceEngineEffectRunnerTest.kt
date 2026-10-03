package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEngineEffectRunnerTest {
    private val tag = VoiceSessionTag(VoiceSessionId("one"), VoiceGeneration(1))
    private val credentials = VoiceConnectionCredentials("wss://example.invalid", "secret")
    private fun state(tag: VoiceSessionTag = this.tag) = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId,
        wanted = true, phase = VoicePhase.CONNECTING, leaseDeadlineMillis = 100, lastLeaseRequestId = VoiceLeaseRequestId(0))
    private class Session(val blockConnect: Boolean = false, var failClose: Boolean = false) : VoiceSdkSession {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>(); var closes = 0
        override suspend fun connect(credentials: VoiceConnectionCredentials) { calls += "connect"; entered.complete(Unit); if (blockConnect) gate.await() }
        override suspend fun publishMicrophone(muted: Boolean): Boolean { calls += "publish:$muted"; return true }
        override suspend fun setMuted(muted: Boolean): Boolean { calls += "mute:$muted"; return true }
        override fun setDeafened(deafened: Boolean) { calls += "deafen:$deafened" }
        override suspend fun close() { closes++; if (failClose) { failClose = false; error("secret") } }
    }
    @Test fun immediateDispatcherIsRejectedBeforeAnySdkAllocation() = runTest {
        var created = 0
        val inline = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: kotlin.coroutines.CoroutineContext) = false
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = block.run()
        }
        assertFailsWith<IllegalArgumentException> {
            VoiceEngineEffectRunner(backgroundScope, inline, VoiceSdkSessionFactory { _, _ -> created++; Session() }, { state() }, { 0 }, {})
        }
        assertEquals(0, created)
    }
    @Test fun dispatchCannotAllocateNativeStackInline() = runTest {
        var created = 0; val events = mutableListOf<VoiceSessionEvent>(); val session = Session()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> created++; session }, { state() }, { 0 }, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials))
        assertEquals(0, created)
        runCurrent(); assertEquals(1, created)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(1, session.closes)
    }
    @Test fun stopBeforeAllocationStillAcknowledgesActualLocalRelease() = runTest {
        var current = state(); var created = 0; val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> created++; Session() }, { current }, { 0 }, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials))
        current = current.copy(wanted = false, phase = VoicePhase.STOPPING, cleanupPending = true)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(0, created); assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
        val cleared = VoiceSessionReducer.reduce(current, VoiceSessionEvent.Stopped(tag.generation)).state
        assertFalse(cleared.cleanupPending)
    }
    @Test fun stopDuringIssuedConnectDoesNotWaitForConnectOrPublishLate() = runTest {
        var current = state(); val events = mutableListOf<VoiceSessionEvent>(); val session = Session(blockConnect = true)
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, { current }, { 0 }, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
        assertTrue(session.entered.isCompleted)
        current = current.copy(wanted = false)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(1, session.closes); assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
        session.gate.complete(Unit); runCurrent(); assertFalse(session.calls.any { it.startsWith("publish:") })
    }
    @Test fun duplicateStartDoesNotReconnectAndSamplesLatestInitialPreferences() = runTest {
        var current = state(); var created = 0; val session = Session()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> created++; session }, { current }, { 0 }, {})
        val start = VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)
        runner.dispatch(start); current = current.copy(muted = true, deafened = true)
        runner.dispatch(start); runCurrent(); runner.dispatch(start); runCurrent()
        assertEquals(1, created); assertEquals(listOf("deafen:true", "connect", "publish:true", "mute:true"), session.calls)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
    }
    @Test fun failedCloseRemainsRetryableAndNeverPublishesFalseStopped() = runTest {
        var current = state(); val session = Session(failClose = true); val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, { current }, { 0 }, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
        current = current.copy(wanted = false)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(setOf(tag.generation), runner.failedCleanup.value); assertFalse(events.any { it is VoiceSessionEvent.Stopped })
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(2, session.closes); assertTrue(runner.failedCleanup.value.isEmpty())
        assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
    }
    @Test fun queuedExpiredGrantCannotAllocateAndFailureIsExactTagged() = runTest {
        var now = 0L; var created = 0; val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> created++; Session() }, { state() }, { now }, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); now = 100; runCurrent()
        assertEquals(0, created); assertTrue(VoiceSessionEvent.EngineFailed(tag) in events)
    }
    @Test fun oldStopAndPreferenceCannotCloseOrChangeReplacement() = runTest {
        var current = state(); val next = VoiceSessionTag(VoiceSessionId("two"), VoiceGeneration(2))
        val old = Session(blockConnect = true); val fresh = Session()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { owned, _ -> if (owned == tag) old else fresh }, { current }, { 0 }, {})
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent(); assertTrue(old.entered.isCompleted)
        current = state(next)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation)))
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(next), credentials)); runCurrent()
        assertEquals(1, old.closes); assertEquals(0, fresh.closes)
        current = current.copy(muted = true)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(next, true)))
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, true)))
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(listOf("deafen:false", "connect", "publish:true", "mute:false", "mute:true"), fresh.calls); assertEquals(0, fresh.closes)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(next.generation))); runCurrent()
    }
    @Test fun queuedPreferencesCoalesceAndRemainAcceptedDuringConnect() = runTest {
        var current = state(); val session = Session(blockConnect = true)
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, { current }, { 0 }, {})
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
        assertTrue(session.entered.isCompleted)
        current = current.copy(muted = true); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, true)))
        current = current.copy(muted = false); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, false))); runCurrent()
        assertFalse(session.calls.any { it.startsWith("mute:") })
        session.gate.complete(Unit); runCurrent()
        assertEquals(listOf("mute:false"), session.calls.filter { it.startsWith("mute:") })
        current = current.copy(deafened = true); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetDeafened(tag, true)))
        current = current.copy(deafened = false); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetDeafened(tag, false)))
        current = current.copy(deafened = true); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetDeafened(tag, true))); runCurrent()
        assertEquals(listOf("deafen:false", "deafen:true"), session.calls.filter { it.startsWith("deafen:") })
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
    }
    @Test fun boundControlOwnerStopsBlockedSdkWhileRemoteCleanupRemainsPending() = runTest {
        val session = Session(blockConnect = true); val disconnected = mutableListOf<VoiceSessionTag>()
        val sharing = mutableListOf<VoiceSessionEffect>()
        val port = object : VoiceSessionControlPort {
            override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) =
                VoiceHttpReply.Credentials(VoiceSessionTag(tag.sessionId, generation), credentials,
                    VoiceLeaseGrant(VoiceLeaseRequestId(0), 0, 0, 100))
            override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("No automatic status")
            override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply = error("No automatic heartbeat")
            override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("Granted session must disconnect by exact tag")
            override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
                disconnected += tag; return VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", cleanupPending = true)
            }
        }
        lateinit var owner: VoiceControlOwner
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, { owner.state.value }, { 0 }, { owner.event(it) })
        owner = VoiceControlOwner(backgroundScope, port) { command ->
            when (command.effect) {
                is VoiceSessionEffect.StartEngine, is VoiceSessionEffect.StopEngine,
                is VoiceSessionEffect.SetMuted, is VoiceSessionEffect.SetDeafened -> runner.dispatch(command)
                else -> sharing += command.effect
            }
        }
        owner.join("channel", VoiceOperationId("operation")); runCurrent()
        assertTrue(session.entered.isCompleted); assertTrue(owner.state.value.wanted)
        owner.stop(); runCurrent()
        assertFalse(session.gate.isCompleted); assertEquals(1, session.closes)
        assertEquals(listOf(tag), disconnected)
        assertFalse(owner.state.value.wanted); assertFalse(owner.state.value.cleanupPending)
        assertEquals(setOf(VoiceStopRequest.Session(tag)), owner.pendingRemoteStops.value)
        assertTrue(VoiceSessionEffect.ClearSharingQueue in sharing)
        assertFalse(session.calls.any { it.startsWith("publish:") })
    }
}

package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceNativeProducerDrainTest {
    private val tag = VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(1))
    private val credentials = VoiceConnectionCredentials("wss://example.invalid", "secret")
    private fun state() = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId,
        wanted = true, phase = VoicePhase.CONNECTING, leaseDeadlineMillis = 5000, lastLeaseRequestId = VoiceLeaseRequestId(0))

    @Test fun stoppedAckCannotHideCancelledButIncompleteConnectProducer() = runTest {
        var current = state(); val session = Session(holdConnect = true)
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory {_, _ -> session}, {current}, {100}, events::add)
        try {
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
            assertTrue(session.entered.isCompleted)
            assertEquals(1, runner.inFlight.value)
            current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
            assertEquals(1, session.closes)
            assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
            assertFalse(session.returned)
            assertEquals(1, runner.inFlight.value)
            session.release.complete(Unit); runCurrent()
            assertTrue(session.returned)
            assertEquals(0, session.publishes)
            assertEquals(0, runner.inFlight.value)
        } finally {session.release.complete(Unit); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()}
    }

    @Test fun stoppedAckCannotHideCancelledButIncompleteNativePreferenceProducer() = runTest {
        var current = state(); val session = Session()
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory {_, _ -> session}, {current}, {100}, events::add)
        try {
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
            assertEquals(1, session.publishes); assertEquals(0, runner.inFlight.value)
            session.holdMute = true
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, true))); runCurrent()
            assertTrue(session.entered.isCompleted)
            assertEquals(1, runner.inFlight.value)
            current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
            assertEquals(1, session.closes)
            assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
            assertFalse(session.returned)
            assertEquals(1, runner.inFlight.value)
            session.release.complete(Unit); runCurrent()
            assertTrue(session.returned)
            assertEquals(0, runner.inFlight.value)
        } finally {session.release.complete(Unit); runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()}
    }

    @Test fun producerCancelledBeforeItsBodyStartsStillReleasesItsFlightRecord() = runTest {
        var current = state(); var created = 0
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory {_, _ -> created++; Session()}, {current}, {100}, events::add)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials))
        assertEquals(1, runner.inFlight.value)
        assertEquals(0, created)
        current = current.copy(wanted = false)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(0, created)
        assertEquals(0, runner.inFlight.value)
        assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
    }

    @Test fun switchingChannelsWaitsForOldConnectEvenAfterSuccessfulStop() = runTest {
        assertReplacementWaits(holdConnect = true)
    }

    @Test fun switchingChannelsWaitsForOldMuteEvenAfterSuccessfulStop() = runTest {
        assertReplacementWaits(holdConnect = false)
    }

    private suspend fun TestScope.assertReplacementWaits(holdConnect: Boolean) {
        var current = state()
        val old = Session(holdConnect); val next = Session()
        val nextTag = VoiceSessionTag(VoiceSessionId("replacement"), VoiceGeneration(2))
        val allocated = mutableListOf<VoiceSessionTag>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { candidate, _ -> allocated += candidate; if (candidate == tag) old else next }, {current}, {100}, {})
        try {
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
            if (!holdConnect) {
                old.holdMute = true
                runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, true))); runCurrent()
            }
            assertTrue(old.entered.isCompleted)
            current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
            assertEquals(1, old.closes)
            assertFalse(old.returned)
            assertEquals(1, runner.inFlight.value)
            current = state().copy(generation = nextTag.generation, sessionId = nextTag.sessionId)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(nextTag), credentials)); runCurrent()
            assertEquals(listOf(tag), allocated)
            assertEquals(0, next.publishes)
            old.release.complete(Unit); runCurrent()
            assertTrue(old.returned)
            assertEquals(listOf(tag, nextTag), allocated)
            assertEquals(1, next.publishes)
            assertEquals(0, runner.inFlight.value)
        } finally {
            old.release.complete(Unit); current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation)))
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(nextTag.generation))); runCurrent()
        }
    }

    @Test fun thirdSwitchStillWaitsForOldestProducerAfterIntermediateStartIsCancelled() = runTest {
        var current = state(); val old = Session(holdConnect = true)
        val middle = VoiceSessionTag(VoiceSessionId("middle"), VoiceGeneration(2))
        val newest = VoiceSessionTag(VoiceSessionId("newest"), VoiceGeneration(3))
        val allocated = mutableListOf<VoiceSessionTag>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { candidate, _ -> allocated += candidate; if (candidate == tag) old else Session() }, {current}, {100}, {})
        try {
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
            assertTrue(old.entered.isCompleted)
            current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
            current = state().copy(generation = middle.generation, sessionId = middle.sessionId)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(middle), credentials)); runCurrent()
            current = current.copy(wanted = false)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(middle.generation))); runCurrent()
            current = state().copy(generation = newest.generation, sessionId = newest.sessionId)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(newest), credentials)); runCurrent()
            assertEquals(listOf(tag), allocated)
            assertFalse(old.returned)
            assertEquals(2, runner.inFlight.value)
            old.release.complete(Unit); runCurrent()
            assertTrue(old.returned)
            assertEquals(listOf(tag, newest), allocated)
            assertEquals(0, runner.inFlight.value)
        } finally {
            old.release.complete(Unit); current = current.copy(wanted = false)
            for (t in listOf(tag, middle, newest)) runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(t.generation)))
            runCurrent()
        }
    }

    private class Session(private val holdConnect: Boolean = false) : VoiceSdkSession {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false; var holdMute = false; var publishes = 0; var closes = 0
        private suspend fun hold() = withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true}
        override suspend fun connect(credentials: VoiceConnectionCredentials) {if (holdConnect) hold()}
        override suspend fun publishMicrophone(muted: Boolean): Boolean {publishes++; return true}
        override suspend fun setMuted(muted: Boolean): Boolean {if (holdMute) hold(); return true}
        override fun setDeafened(deafened: Boolean) = Unit
        override suspend fun close() {closes++}
    }
}

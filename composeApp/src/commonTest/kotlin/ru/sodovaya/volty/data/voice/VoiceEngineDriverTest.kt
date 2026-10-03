package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.voice.VoiceGeneration
import ru.sodovaya.volty.domain.voice.VoiceSessionEffect
import ru.sodovaya.volty.domain.voice.VoiceSessionId
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.voice.VoiceSessionEvent

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEngineDriverTest {
    private val credentials = VoiceConnectionCredentials("https://example.invalid", "secret-test-token")
    private fun tag(number: Long) = VoiceSessionTag(VoiceSessionId("s$number"), VoiceGeneration(number))
    private fun kotlinx.coroutines.test.TestScope.driver(factory: VoiceSdkSessionFactory) =
        VoiceEngineDriver(backgroundScope, factory, authorized = { true })

    @Test
    fun stopDuringConnectPreventsLatePublicationAndClosesOnce() = runTest {
        val session = FakeSession(blockConnect = true)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent()
        assertTrue(session.enteredConnect.isCompleted)
        assertTrue(driver.stop(tag(1).generation).isSuccess)
        session.connectGate.complete(Unit)
        runCurrent()
        assertEquals(0, session.publishCalls)
        assertEquals(1, session.closeCalls)
        assertTrue(start.isCancelled)
    }

    @Test
    fun replacingSuspendedStartCannotCloseNewSessionOnOldCancellation() = runTest {
        val one = FakeSession(blockConnect = true)
        val two = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { tag, _ -> if (tag == tag(1)) one else two })
        val old = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent()
        assertTrue(one.enteredConnect.isCompleted)
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(1, one.closeCalls)
        assertEquals(1, two.publishCalls)
        assertEquals(0, two.closeCalls)
        driver.stop(tag(2).generation)
        assertEquals(1, two.closeCalls)
    }

    @Test
    fun publicationFalseReturnsTypedFailureAndReleasesOwnResources() = runTest {
        val session = FakeSession(publishResult = false)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val result = driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials)
        assertEquals(VoiceEngineError.PUBLICATION, (result.exceptionOrNull() as VoiceEngineException).code)
        assertEquals(1, session.publishCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun wrongGenerationStopLeavesCurrentResourcesOpen() = runTest {
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        assertEquals(1, session.publishCalls)
        driver.stop(tag(1).generation)
        assertEquals(0, session.closeCalls)
        driver.stop(tag(2).generation)
    }

    @Test
    fun initialDeafenPrecedesConnectAndMutePrecedesPublication() = runTest {
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true, deafened = true), credentials).isSuccess)
        assertEquals(listOf("deafen:true", "connect", "publish:true", "mute:true"), session.calls)
        driver.stop(tag(1).generation)
    }

    @Test
    fun credentialsNeverExposeServerOrTokenThroughToString() {
        assertFalse(credentials.toString().contains(credentials.token))
        assertFalse(credentials.toString().contains(credentials.serverUrl))
    }

    @Test
    fun callbackWithWrongTagCannotPublishIntoCurrentEventStream() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        emit(VoiceSessionEvent.Published(tag(1)))
        emit(VoiceSessionEvent.EngineFailed(tag(1)))
        emit(VoiceSessionEvent.Failed(tag(1).generation))
        emit(VoiceSessionEvent.LeaseAck(tag(2), ru.sodovaya.volty.domain.voice.VoiceLeaseGrant(ru.sodovaya.volty.domain.voice.VoiceLeaseRequestId(1), 20_000, 20_000, 30_000)))
        emit(VoiceSessionEvent.Stopped(tag(2).generation))
        runCurrent()
        assertTrue(events.isEmpty())
        emit(VoiceSessionEvent.Published(tag(2)))
        runCurrent()
        assertEquals(listOf<VoiceSessionEvent>(VoiceSessionEvent.Published(tag(2))), events)
        emit(VoiceSessionEvent.Failed(tag(2).generation)); runCurrent()
        assertEquals(listOf<VoiceSessionEvent>(VoiceSessionEvent.Published(tag(2)), VoiceSessionEvent.EngineFailed(tag(2))), events)
        driver.stop(tag(2).generation)
    }

    @Test
    fun failedCloseDoesNotAcknowledgeStoppedAndCanBeRetriedByGeneration() = runTest {
        val session = FakeSession(closeFailures = 1)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        val failure = driver.stop(tag(1).generation)
        assertEquals(VoiceEngineError.CLEANUP, (failure.exceptionOrNull() as VoiceEngineException).code)
        assertTrue(events.isEmpty())
        assertTrue(driver.stop(tag(1).generation).isSuccess)
        assertEquals(2, session.closeCalls)
        assertEquals(listOf<VoiceSessionEvent>(VoiceSessionEvent.Stopped(tag(1).generation)), events)
    }

    @Test fun muteWhileConnectPendingIsAcceptedAndCannotBeOverwrittenAtPublication() = runTest {
        val session = FakeSession(blockConnect = true)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent()
        assertTrue(session.enteredConnect.isCompleted)
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        session.connectGate.complete(Unit); runCurrent()
        assertTrue(start.await().isSuccess)
        assertEquals(listOf("deafen:false", "connect", "publish:true", "mute:true"), session.calls)
        driver.stop(tag(1).generation)
    }

    @Test fun initialUnmutedIntentStillPublishesMutedBeforeApplyingCurrentIntent() = runTest {
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        assertEquals(listOf("deafen:false", "connect", "publish:true", "mute:false"), session.calls)
        driver.stop(tag(1).generation)
    }

    @Test fun muteChangedDuringPublicationIsReconciledAfterTrackExists() = runTest {
        val session = FakeSession(blockPublication = true)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent(); assertTrue(session.enteredPublication.isCompleted)
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        assertEquals(listOf("deafen:false", "connect", "publish:true"), session.calls)
        session.publicationGate.complete(Unit); runCurrent()
        assertTrue(start.await().isSuccess)
        assertEquals("mute:true", session.calls.last())
        driver.stop(tag(1).generation)
    }

    @Test fun queuedMuteCannotFinishWithOlderIntentAfterSuspendedSdkControl() = runTest {
        val session = FakeSession(blockFirstMute = true)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent(); assertTrue(session.enteredMute.isCompleted)
        val change = async { driver.setMuted(tag(1), true) }
        runCurrent(); assertFalse(change.isCompleted)
        session.muteGate.complete(Unit); runCurrent()
        assertTrue(start.await().isSuccess); assertTrue(change.await().isSuccess)
        assertEquals(listOf("mute:false", "mute:true", "mute:true"), session.calls.filter { it.startsWith("mute:") })
        driver.stop(tag(1).generation)
    }

    @Test fun oldGenerationMuteCannotChangeReplacementIntent() = runTest {
        val old = FakeSession(blockConnect = true); val fresh = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { tag, _ -> if (tag == tag(1)) old else fresh })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent(); assertTrue(old.enteredConnect.isCompleted)
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        runCurrent()
        assertTrue(start.isCancelled)
        assertTrue(driver.setMuted(tag(1), true).isFailure)
        assertEquals(listOf("deafen:false", "connect", "publish:true", "mute:false"), fresh.calls)
        driver.stop(tag(2).generation)
    }

    @Test fun failureApplyingInitialMuteReleasesStackAndReportsTypedFailure() = runTest {
        val session = FakeSession(muteResult = false)
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        val result = driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials)
        assertEquals(VoiceEngineError.MUTE, (result.exceptionOrNull() as VoiceEngineException).code)
        assertEquals(1, session.closeCalls)
        assertEquals(listOf("deafen:false", "connect", "publish:true", "mute:false"), session.calls)
    }

    @Test fun deafenDuringOldResourceReleaseIsAcceptedBeforeNewAllocation() = runTest {
        val old = FakeSession(blockClose = true); val fresh = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { tag, _ -> if (tag == tag(1)) old else fresh })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials) }
        runCurrent(); assertTrue(old.enteredClose.isCompleted)
        assertEquals(0, fresh.publishCalls)
        assertTrue(driver.setDeafened(tag(2), true).isSuccess)
        old.closeGate.complete(Unit); runCurrent()
        assertTrue(start.await().isSuccess)
        assertEquals(listOf("deafen:true", "connect", "publish:true", "mute:false"), fresh.calls)
        driver.stop(tag(2).generation)
    }

    @Test fun expiredWhileOldCloseWaitsCannotAllocateReplacement() = runTest {
        val old = FakeSession(blockClose = true); var allowed = true; var created = 0
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> created++; old }, authorized = { allowed })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials) }
        runCurrent(); assertTrue(old.enteredClose.isCompleted); assertEquals(1, created)
        allowed = false; old.closeGate.complete(Unit); runCurrent()
        assertEquals(VoiceEngineError.AUTHORIZATION, (start.await().exceptionOrNull() as VoiceEngineException).code)
        assertEquals(1, created); assertEquals(1, old.closeCalls)
    }
    @Test fun expiredDuringConnectCannotPublishMicrophone() = runTest {
        var allowed = true; val session = FakeSession(blockConnect = true)
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session }, authorized = { allowed })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent(); assertTrue(session.enteredConnect.isCompleted)
        allowed = false; session.connectGate.complete(Unit); runCurrent()
        assertTrue(start.await().isFailure); assertEquals(0, session.publishCalls); assertEquals(1, session.closeCalls)
    }
    @Test fun expiredDuringPublicationNeverAppliesUnmute() = runTest {
        var allowed = true; val session = FakeSession(blockPublication = true)
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session }, authorized = { allowed })
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent(); assertTrue(session.enteredPublication.isCompleted)
        allowed = false; session.publicationGate.complete(Unit); runCurrent()
        assertTrue(start.await().isFailure); assertFalse(session.calls.any { it.startsWith("mute:") }); assertEquals(1, session.closeCalls)
    }
    @Test fun deniedBeforeStartAllocatesNothing() = runTest {
        var created = 0
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> created++; FakeSession() }, authorized = { false })
        val result = driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials)
        assertEquals(VoiceEngineError.AUTHORIZATION, (result.exceptionOrNull() as VoiceEngineException).code)
        assertEquals(0, created)
    }
    @Test fun laterControlAfterExpiryCannotIssueUnmuteOrUndeafen() = runTest {
        var allowed = true; val session = FakeSession()
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session }, authorized = { allowed })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true, deafened = true), credentials).isSuccess)
        val issued = session.calls.toList(); allowed = false
        assertTrue(driver.setMuted(tag(1), false).isFailure); assertTrue(driver.setDeafened(tag(1), false).isFailure)
        assertEquals(issued, session.calls)
        assertTrue(driver.stop(tag(1).generation).isSuccess)
    }
    @Test fun deniedStaleStartCannotRetireCurrentlyAuthorizedStack() = runTest {
        val session = FakeSession(); var created = 0
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> created++; session }, authorized = { it == tag(2) })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isFailure)
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2).copy(sessionId = VoiceSessionId("foreign"))), credentials).isFailure)
        assertEquals(1, created); assertEquals(0, session.closeCalls)
        assertTrue(driver.setMuted(tag(2), true).isSuccess)
        assertTrue(driver.stop(tag(2).generation).isSuccess); assertEquals(1, session.closeCalls)
    }

    private class FakeSession(
        private val blockConnect: Boolean = false,
        private val publishResult: Boolean = true,
        private var closeFailures: Int = 0,
        private val blockPublication: Boolean = false,
        private val blockFirstMute: Boolean = false,
        private val muteResult: Boolean = true,
        private val blockClose: Boolean = false,
    ) : VoiceSdkSession {
        val enteredConnect = CompletableDeferred<Unit>()
        val connectGate = CompletableDeferred<Unit>()
        val enteredPublication = CompletableDeferred<Unit>()
        val publicationGate = CompletableDeferred<Unit>()
        val enteredMute = CompletableDeferred<Unit>()
        val muteGate = CompletableDeferred<Unit>()
        val enteredClose = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var publishCalls = 0
        var closeCalls = 0
        private var publicationReady = false
        override suspend fun connect(credentials: VoiceConnectionCredentials) {
            calls += "connect"
            enteredConnect.complete(Unit)
            if (blockConnect) connectGate.await()
        }
        override suspend fun publishMicrophone(muted: Boolean): Boolean {
            calls += "publish:$muted"
            publishCalls++
            enteredPublication.complete(Unit)
            if (blockPublication) publicationGate.await()
            publicationReady = publishResult
            return publishResult
        }
        override suspend fun setMuted(muted: Boolean): Boolean {
            if (!publicationReady) return false
            if (!enteredMute.isCompleted) {
                enteredMute.complete(Unit)
                if (blockFirstMute) muteGate.await()
            }
            calls += "mute:$muted"
            return muteResult
        }
        override fun setDeafened(deafened: Boolean) { calls += "deafen:$deafened" }
        override suspend fun close() {
            closeCalls++
            enteredClose.complete(Unit)
            if (blockClose) closeGate.await()
            if (closeFailures > 0) {
                closeFailures--
                error("secret-test-token")
            }
        }
    }
}

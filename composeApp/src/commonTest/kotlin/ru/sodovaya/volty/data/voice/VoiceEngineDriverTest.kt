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

    @Test
    fun stopDuringConnectPreventsLatePublicationAndClosesOnce() = runTest {
        val session = FakeSession(blockConnect = true)
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session })
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
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { tag, _ -> if (tag == tag(1)) one else two })
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
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session })
        val result = driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials)
        assertEquals(VoiceEngineError.PUBLICATION, (result.exceptionOrNull() as VoiceEngineException).code)
        assertEquals(1, session.publishCalls)
        assertEquals(1, session.closeCalls)
    }

    @Test
    fun wrongGenerationStopLeavesCurrentResourcesOpen() = runTest {
        val session = FakeSession()
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        assertEquals(1, session.publishCalls)
        driver.stop(tag(1).generation)
        assertEquals(0, session.closeCalls)
        driver.stop(tag(2).generation)
    }

    @Test
    fun initialDeafenPrecedesConnectAndMutePrecedesPublication() = runTest {
        val session = FakeSession()
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true, deafened = true), credentials).isSuccess)
        assertEquals(listOf("deafen:true", "connect", "publish:true"), session.calls)
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
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)), credentials).isSuccess)
        emit(VoiceSessionEvent.Published(tag(1)))
        emit(VoiceSessionEvent.LeaseAck(tag(2), 20_000))
        emit(VoiceSessionEvent.Stopped(tag(2).generation))
        runCurrent()
        assertTrue(events.isEmpty())
        emit(VoiceSessionEvent.Published(tag(2)))
        runCurrent()
        assertEquals(listOf<VoiceSessionEvent>(VoiceSessionEvent.Published(tag(2))), events)
        driver.stop(tag(2).generation)
    }

    @Test
    fun failedCloseDoesNotAcknowledgeStoppedAndCanBeRetriedByGeneration() = runTest {
        val session = FakeSession(closeFailures = 1)
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session })
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

    private class FakeSession(
        private val blockConnect: Boolean = false,
        private val publishResult: Boolean = true,
        private var closeFailures: Int = 0,
    ) : VoiceSdkSession {
        val enteredConnect = CompletableDeferred<Unit>()
        val connectGate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var publishCalls = 0
        var closeCalls = 0
        override suspend fun connect(credentials: VoiceConnectionCredentials) {
            calls += "connect"
            enteredConnect.complete(Unit)
            if (blockConnect) connectGate.await()
        }
        override suspend fun publishMicrophone(muted: Boolean): Boolean {
            calls += "publish:$muted"
            publishCalls++
            return publishResult
        }
        override suspend fun setMuted(muted: Boolean) = true
        override fun setDeafened(deafened: Boolean) { calls += "deafen:$deafened" }
        override suspend fun close() {
            closeCalls++
            if (closeFailures > 0) {
                closeFailures--
                error("secret-test-token")
            }
        }
    }
}

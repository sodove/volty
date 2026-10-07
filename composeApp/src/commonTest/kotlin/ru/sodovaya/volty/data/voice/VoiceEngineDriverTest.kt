package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.voice.VoiceInputPreference
import ru.sodovaya.volty.domain.voice.VoiceOutputPreference
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

    @Test fun audioPairIssuesOnlyToPublishedAuthorizedExactHandleAndKeepsMute() = runTest {
        var allowed=true
        val session=FakeSession()
        val driver=VoiceEngineDriver(backgroundScope,VoiceSdkSessionFactory {_,_->session},{allowed})
        assertEquals(VoiceAudioChangeResult.STALE,driver.configureAudio(tag(1),pair()))
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1),muted=true,deafened=true),credentials).isSuccess)
        val before=session.calls.toList()
        assertEquals(VoiceAudioChangeResult.REQUESTED,driver.configureAudio(tag(1),pair()))
        assertEquals(listOf(pair()),session.audioPairs);assertEquals(before,session.calls)
        assertEquals(VoiceAudioChangeResult.STALE,driver.configureAudio(tag(2),pair()))
        allowed=false;assertEquals(VoiceAudioChangeResult.STALE,driver.configureAudio(tag(1),pair()))
        assertEquals(1,session.audioPairs.size);assertEquals(0,session.closeCalls)
        assertTrue(driver.stop(tag(1).generation).isSuccess)
    }
    @Test fun latestQueuedAudioPairSkipsIntermediateIntentWithoutChangingOutputImplicitly() = runTest {
        val session=FakeSession(blockAudio=true);val driver=driver(VoiceSdkSessionFactory {_,_->session})
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)),credentials).isSuccess)
        val first=async {driver.configureAudio(tag(1),pair())};runCurrent();assertTrue(session.enteredAudio.isCompleted)
        val intermediate=async {driver.configureAudio(tag(1),pair(VoiceInputPreference.HEADSET))};runCurrent()
        val latest=async {driver.configureAudio(tag(1),pair(VoiceInputPreference.AUTO))};runCurrent()
        try {
            assertEquals(listOf(pair()),session.audioPairs)
            session.audioGate.complete(Unit);runCurrent()
            assertEquals(VoiceAudioChangeResult.STALE,first.await());assertEquals(VoiceAudioChangeResult.STALE,intermediate.await())
            assertEquals(VoiceAudioChangeResult.REQUESTED,latest.await())
            assertEquals(listOf(pair(),pair(VoiceInputPreference.AUTO)),session.audioPairs)
        } finally {session.audioGate.complete(Unit);first.cancel();intermediate.cancel();latest.cancel();driver.stop(tag(1).generation)}
    }
    @Test fun heldOldAudioResultCannotAcknowledgeReplacementOrExpiredHandle() = runTest {
        var allowed=true
        val old=FakeSession(blockAudio=true,resistAudioCancellation=true);val fresh=FakeSession()
        val driver=VoiceEngineDriver(backgroundScope,VoiceSdkSessionFactory {t,_->if(t==tag(1))old else fresh},{allowed})
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)),credentials).isSuccess)
        val pending=async {driver.configureAudio(tag(1),pair())};runCurrent();assertTrue(old.enteredAudio.isCompleted)
        try {
            assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(2)),credentials).isSuccess)
            old.audioGate.complete(Unit);runCurrent()
            assertEquals(VoiceAudioChangeResult.STALE,pending.await());assertTrue(fresh.audioPairs.isEmpty());assertEquals(0,fresh.closeCalls)
            assertEquals(VoiceAudioChangeResult.REQUESTED,driver.configureAudio(tag(2),pair()))
            val expired=async {driver.configureAudio(tag(2),pair())};allowed=false;runCurrent()
            assertEquals(VoiceAudioChangeResult.STALE,expired.await());assertEquals(1,fresh.audioPairs.size)
        } finally {old.audioGate.complete(Unit);pending.cancel();driver.stop(tag(2).generation)}
    }
    @Test fun audioUnavailableReconnectAndExceptionNeverStopCurrentSession() = runTest {
        val session=FakeSession();val driver=driver(VoiceSdkSessionFactory {_,_->session})
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)),credentials).isSuccess)
        for(result in listOf(VoiceAudioChangeResult.UNAVAILABLE,VoiceAudioChangeResult.REQUIRES_RECONNECT)) {
            session.audioResult=result;assertEquals(result,driver.configureAudio(tag(1),pair()))
        }
        session.audioThrows=true;assertEquals(VoiceAudioChangeResult.FAILED,driver.configureAudio(tag(1),pair()))
        assertEquals(0,session.closeCalls);assertTrue(driver.setMuted(tag(1),true).isSuccess)
        driver.stop(tag(1).generation)
    }
    @Test fun audioCancellationAndTimeoutDoNotFabricateRequestSuccess() = runTest {
        val session=FakeSession(blockAudio=true);val driver=driver(VoiceSdkSessionFactory {_,_->session})
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)),credentials).isSuccess)
        val cancelled=async {driver.configureAudio(tag(1),pair())};runCurrent();assertTrue(session.enteredAudio.isCompleted)
        try {
            cancelled.cancel();runCurrent();assertTrue(cancelled.isCancelled)
            val timed=async {driver.configureAudio(tag(1),pair())};runCurrent()
            advanceTimeBy(5_000);runCurrent();assertEquals(VoiceAudioChangeResult.FAILED,timed.await())
            assertEquals(2,session.audioPairs.size);assertEquals(0,session.closeCalls)
        } finally {session.audioGate.complete(Unit);cancelled.cancel();driver.stop(tag(1).generation)}
    }
    @Test fun audioLeaseExpiresDuringIssuedNativeRequestAndLateResultIsStale() = runTest {
        var allowed=true;val session=FakeSession(blockAudio=true)
        val driver=VoiceEngineDriver(backgroundScope,VoiceSdkSessionFactory {_,_->session},{allowed})
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)),credentials).isSuccess)
        val pending=async {driver.configureAudio(tag(1),pair())};runCurrent()
        try {
            assertTrue(session.enteredAudio.isCompleted);assertEquals(listOf(pair()),session.audioPairs)
            allowed=false;session.audioGate.complete(Unit);runCurrent()
            assertEquals(VoiceAudioChangeResult.STALE,pending.await());assertEquals(0,session.closeCalls)
            driver.stop(tag(1).generation);assertEquals(1,session.closeCalls)
        } finally {session.audioGate.complete(Unit);pending.cancel();driver.stop(tag(1).generation)}
    }
    private fun pair(input:VoiceInputPreference=VoiceInputPreference.PHONE)=
        VoiceAudioPreferences(input,VoiceOutputPreference.HEADSET)

    @Test fun muteIntentWaitsForRestoredOwnedPublication() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        assertEquals(listOf(false), session.muteAttempts)
        session.publicationAvailable = false // Deliberately missing although the handle started successfully.
        emit(VoiceSessionEvent.Reconnecting(tag(1))); runCurrent()
        assertTrue(events.contains(VoiceSessionEvent.Reconnecting(tag(1))))
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        assertTrue(driver.setMuted(tag(1), false).isSuccess)
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        assertEquals(listOf(false), session.muteAttempts)
        emit(VoiceSessionEvent.Published(tag(2))); runCurrent()
        assertEquals(listOf(false), session.muteAttempts)
        session.publicationAvailable = true
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertEquals(listOf(false, true), session.muteAttempts)
        assertEquals(1, session.publishCalls) // SDK restoration never allocates another app microphone.
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertEquals(listOf(false, true), session.muteAttempts)
        driver.stop(tag(1).generation)
    }

    @Test fun restoredPublicationCannotApplyMuteAfterAuthorizationRetirement() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        var authorized = true
        val session = FakeSession()
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, callback -> emit = callback; session },
            authorized = { authorized })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        session.publicationAvailable = false
        emit(VoiceSessionEvent.Reconnecting(tag(1))); runCurrent()
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        authorized = false
        session.publicationAvailable = true
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertEquals(listOf(false), session.muteAttempts)
        driver.stop(tag(1).generation)
    }

    @Test fun restoredPublicationMuteRejectionPublishesOwnedFailure() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        session.publicationAvailable = false
        emit(VoiceSessionEvent.Unpublished(tag(1))); runCurrent()
        assertTrue(events.contains(VoiceSessionEvent.Unpublished(tag(1))))
        assertTrue(driver.setMuted(tag(1), true).isSuccess)
        session.publicationAvailable = true
        session.rejectMute = true
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertEquals(listOf(false, true), session.muteAttempts)
        assertEquals(listOf(VoiceSessionEvent.EngineFailed(tag(1))), events.filterIsInstance<VoiceSessionEvent.EngineFailed>())
        driver.stop(tag(1).generation)
    }

    @Test fun queuedMuteParksWhenReconnectRemovesPublicationBeforeControlRuns() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        val session = FakeSession(blockAudio = true)
        val driver = driver(VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials).isSuccess)
        val audio = async { driver.configureAudio(tag(1), pair()) }
        runCurrent()
        assertTrue(session.enteredAudio.isCompleted)
        val mute = async { driver.setMuted(tag(1), true) }
        runCurrent()
        assertEquals(listOf(false), session.muteAttempts)
        session.publicationAvailable = false
        emit(VoiceSessionEvent.Unpublished(tag(1))); runCurrent()
        session.audioGate.complete(Unit); runCurrent()
        assertEquals(VoiceAudioChangeResult.STALE, audio.await())
        assertTrue(mute.await().isSuccess)
        assertEquals(listOf(false), session.muteAttempts)
        session.publicationAvailable = true
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertEquals(listOf(false, true), session.muteAttempts)
        driver.stop(tag(1).generation)
    }

    @Test fun earlyPublishedCallbackCannotEnableMicrophoneBeforeInitialAcknowledgement() = runTest {
        lateinit var emit: (VoiceSessionEvent) -> Unit
        val session = FakeSession(blockPublication = true)
        val driver = driver(VoiceSdkSessionFactory { _, callback -> emit = callback; session })
        val events = mutableListOf<VoiceSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { driver.events.collect { events += it } }
        val start = async { driver.start(VoiceSessionEffect.StartEngine(tag(1)), credentials) }
        runCurrent()
        assertTrue(session.enteredPublication.isCompleted)
        emit(VoiceSessionEvent.Published(tag(1))); runCurrent()
        assertTrue(driver.setMuted(tag(1), false).isSuccess)
        assertTrue(session.muteAttempts.isEmpty())
        assertTrue(events.filterIsInstance<VoiceSessionEvent.Published>().isEmpty())
        session.publicationGate.complete(Unit); runCurrent()
        assertTrue(start.await().isSuccess)
        assertEquals(listOf(false), session.muteAttempts)
        assertEquals(listOf(VoiceSessionEvent.Published(tag(1))), events.filterIsInstance<VoiceSessionEvent.Published>())
        driver.stop(tag(1).generation)
    }

    @Test fun permissionRetiredWhileNativeMuteWaitsCannotOpenMicrophone() = runTest {
        var authorized = true
        val session = FakeSession()
        val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, _ -> session }, { authorized })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true), credentials).isSuccess)
        session.blockNextMute = true
        val pending = async { driver.setMuted(tag(1), false) }
        runCurrent(); assertTrue(session.enteredMuteBoundary.isCompleted)
        assertEquals(listOf(true), session.muteAttempts)
        authorized = false
        session.muteBoundaryGate.complete(Unit); runCurrent()
        assertTrue(pending.await().isFailure)
        assertEquals(listOf(true), session.muteAttempts)
        driver.stop(tag(1).generation)
    }

    @Test fun newerMuteIntentWhileNativeUnmuteWaitsCannotBrieflyOpenMicrophone() = runTest {
        val session = FakeSession()
        val driver = driver(VoiceSdkSessionFactory { _, _ -> session })
        assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true), credentials).isSuccess)
        session.blockNextMute = true
        val unmute = async { driver.setMuted(tag(1), false) }
        runCurrent(); assertTrue(session.enteredMuteBoundary.isCompleted)
        val mute = async { driver.setMuted(tag(1), true) }; runCurrent()
        assertEquals(listOf(true), session.muteAttempts)
        session.muteBoundaryGate.complete(Unit); runCurrent()
        assertTrue(unmute.await().isSuccess); assertTrue(mute.await().isSuccess)
        assertTrue(session.muteAttempts.all { it })
        driver.stop(tag(1).generation)
    }

    @Test fun closedRecoveryAcknowledgementAppliesLatestDriverIntentOnlyWhileAuthorized() = runTest {
        for (revokeBeforeAcknowledgement in listOf(false, true)) {
            var authorized = true
            lateinit var emit: (VoiceSessionEvent) -> Unit
            val session = FakeSession()
            var allocations = 0; var restores = 0; var acknowledgements = 0
            val enteredRestore = CompletableDeferred<Unit>()
            val restoreGate = CompletableDeferred<Unit>()
            val driver = VoiceEngineDriver(backgroundScope, VoiceSdkSessionFactory { _, callback ->
                allocations++; emit = callback; session
            }, { authorized })
            val recovery = VoicePublicationRecovery(backgroundScope, object : VoicePublicationRecoveryPort {
                override suspend fun restoreClosed(): Boolean {
                    restores++
                    session.calls += "restore:true"
                    enteredRestore.complete(Unit)
                    restoreGate.await()
                    session.publicationAvailable = true
                    return true
                }
                override fun hasOwnedPublication(): Boolean = session.publicationAvailable
            }, {
                acknowledgements++
                emit(VoiceSessionEvent.Published(tag(1)))
            }, { emit(VoiceSessionEvent.EngineFailed(tag(1))) })
            assertTrue(driver.start(VoiceSessionEffect.StartEngine(tag(1), muted = true), credentials).isSuccess)
            session.publicationAvailable = false
            emit(VoiceSessionEvent.Reconnecting(tag(1))); recovery.reconnecting()
            emit(VoiceSessionEvent.Unpublished(tag(1))); recovery.unpublished(true)
            emit(VoiceSessionEvent.TransportConnected(tag(1))); recovery.connected(); runCurrent()
            assertTrue(enteredRestore.isCompleted)
            assertTrue(driver.setMuted(tag(1), false).isSuccess)
            assertEquals(listOf(true), session.muteAttempts)
            assertFalse(recovery.sdkPublished())
            if (revokeBeforeAcknowledgement) authorized = false
            restoreGate.complete(Unit); runCurrent()
            assertEquals(1, acknowledgements); assertEquals(1, restores)
            assertEquals(if (revokeBeforeAcknowledgement) listOf(true) else listOf(true, false), session.muteAttempts)
            assertEquals(1, allocations); assertEquals(1, session.publishCalls)
            assertTrue(session.calls.contains("restore:true"))
            recovery.close(); driver.stop(tag(1).generation)
        }
    }

    private class FakeSession(
        private val blockConnect: Boolean = false,
        private val publishResult: Boolean = true,
        private var closeFailures: Int = 0,
        private val blockPublication: Boolean = false,
        private val blockFirstMute: Boolean = false,
        private val muteResult: Boolean = true,
        private val blockClose: Boolean = false,
        private val blockAudio:Boolean=false,
        private val resistAudioCancellation:Boolean=false,
    ) : VoiceSdkSession {
        val enteredConnect = CompletableDeferred<Unit>()
        val connectGate = CompletableDeferred<Unit>()
        val enteredPublication = CompletableDeferred<Unit>()
        val publicationGate = CompletableDeferred<Unit>()
        val enteredMute = CompletableDeferred<Unit>()
        val muteGate = CompletableDeferred<Unit>()
        val enteredClose = CompletableDeferred<Unit>()
        val closeGate = CompletableDeferred<Unit>()
        val audioPairs=mutableListOf<VoiceAudioPreferences>()
        val enteredAudio=CompletableDeferred<Unit>()
        val audioGate=CompletableDeferred<Unit>()
        var audioResult=VoiceAudioChangeResult.REQUESTED
        var audioThrows=false
        override suspend fun configureAudio(preferences:VoiceAudioPreferences):VoiceAudioChangeResult {
            audioPairs+=preferences;enteredAudio.complete(Unit)
            if(blockAudio) {
                if(resistAudioCancellation) withContext(NonCancellable) {audioGate.await()} else audioGate.await()
            }
            if(audioThrows) error("private-test-token")
            return audioResult
        }
        val calls = mutableListOf<String>()
        var publishCalls = 0
        var closeCalls = 0
        var publicationAvailable = false
        var rejectMute = false
        val muteAttempts = mutableListOf<Boolean>()
        var blockNextMute = false
        val enteredMuteBoundary = CompletableDeferred<Unit>()
        val muteBoundaryGate = CompletableDeferred<Unit>()
        private suspend fun awaitMuteBoundary() {
            if (blockNextMute) {
                blockNextMute = false
                enteredMuteBoundary.complete(Unit)
                muteBoundaryGate.await()
            }
        }
        override suspend fun setMutedIfCurrent(muted: Boolean, current: () -> Boolean): VoiceMuteApplyResult {
            awaitMuteBoundary()
            if (!current()) return VoiceMuteApplyResult.SUPERSEDED
            return if (setMuted(muted)) VoiceMuteApplyResult.APPLIED else VoiceMuteApplyResult.REJECTED
        }
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
            publicationAvailable = publishResult
            return publishResult
        }
        override suspend fun setMuted(muted: Boolean): Boolean {
            awaitMuteBoundary()
            muteAttempts += muted
            if (!publicationAvailable) return false
            if (!enteredMute.isCompleted) {
                enteredMute.complete(Unit)
                if (blockFirstMute) muteGate.await()
            }
            calls += "mute:$muted"
            return muteResult && !rejectMute
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

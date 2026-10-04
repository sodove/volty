package ru.sodovaya.volty.data.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEngineAccountPermissionTest {
    private val tag = VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(1))
    private val credentials = VoiceConnectionCredentials("wss://example.invalid", "secret")
    // Deliberately still-earned state: account retirement alone must fence this projection.
    private fun state() = VoiceSessionState(generation = tag.generation, sessionId = tag.sessionId,
        wanted = true, phase = VoicePhase.CONNECTING, leaseDeadlineMillis = 5000, lastLeaseRequestId = VoiceLeaseRequestId(0))
    private fun TestScope.repository() = DefaultSocialRepository(Transport(), Store(), backgroundScope)

    @Test fun queuedNativeStartDoesNotAllocateForRetiredRealAccountWithEarnedState() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        var created = 0
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> created++; Session() }, ::state, {100}, events::add,
            accountPermission = {authority.job.isActive})
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials))
        repository.logout(); runCurrent()
        assertFalse(authority.job.isActive)
        assertEquals(0, created)
        assertTrue(VoiceSessionEvent.EngineFailed(tag) in events)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
    }

    @Test fun issuedNonCooperativeConnectCannotPublishWhenAccountRetires() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val session = Session(held = true)
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, ::state, {100}, events::add,
            accountPermission = {authority.job.isActive})
        try {
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
            assertTrue(session.entered.isCompleted)
            repository.logout()
            assertFalse(session.returned)
            session.release.complete(Unit); runCurrent()
            assertTrue(session.returned)
            assertEquals(0, session.publishes)
            assertEquals(1, session.closes)
            assertTrue(VoiceSessionEvent.EngineFailed(tag) in events)
        } finally {
            session.release.complete(Unit)
            runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        }
    }

    @Test fun retiredAccountCannotChangeNativePreferencesButCloseFailureStillRetries() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val session = Session()
        val events = mutableListOf<VoiceSessionEvent>()
        val runner = VoiceEngineEffectRunner(backgroundScope, StandardTestDispatcher(testScheduler),
            VoiceSdkSessionFactory { _, _ -> session }, ::state, {100}, events::add,
            accountPermission = {authority.job.isActive})
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StartEngine(tag), credentials)); runCurrent()
        assertEquals(1, session.publishes)
        val muted = session.mutes; val deafened = session.deafens
        repository.logout()
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetMuted(tag, true)))
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.SetDeafened(tag, true))); runCurrent()
        assertEquals(muted, session.mutes); assertEquals(deafened, session.deafens)
        session.failClose = true
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(1, session.closes)
        assertEquals(setOf(tag.generation), runner.failedCleanup.value)
        assertFalse(VoiceSessionEvent.Stopped(tag.generation) in events)
        runner.dispatch(VoiceControlDispatch(VoiceSessionEffect.StopEngine(tag.generation))); runCurrent()
        assertEquals(2, session.closes)
        assertEquals(emptySet(), runner.failedCleanup.value)
        assertTrue(VoiceSessionEvent.Stopped(tag.generation) in events)
    }

    @Test fun composedRuntimeForwardsAccountGateBeforeLateGrantCanAllocateNativeStack() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false; var created = 0; var cancels = 0; var disconnects = 0
        val port = object : VoiceSessionControlPort {
            override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply {
                entered.complete(Unit)
                withContext(NonCancellable) {release.await()}
                return VoiceHttpReply.Credentials(tag, credentials, VoiceLeaseGrant(VoiceLeaseRequestId(0), 100, 100, 5000)).also {returned = true}
            }
            override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("Unexpected status")
            override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply = error("Unexpected heartbeat")
            override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
                cancels++; return VoiceHttpReply.Ended(generation, null, "CANCELLED", false)
            }
            override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply {
                disconnects++; return VoiceHttpReply.Ended(tag.generation, tag.sessionId, "CANCELLED", false)
            }
        }
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = VoiceRuntime(backgroundScope, StandardTestDispatcher(testScheduler), port,
            VoiceSdkSessionFactory { _, _ -> created++; Session() }, pulses.receiveAsFlow(), {100}, {},
            accountPermission = {authority.job.isActive})
        try {
            backgroundScope.launch {runtime.join("channel", VoiceOperationId("operation"))}; runCurrent()
            assertTrue(entered.isCompleted)
            repository.logout()
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertEquals(0, created)
            assertFalse(runtime.state.value.wanted)
            assertFalse(runtime.state.value.cleanupPending)
            assertEquals(0, cancels)
            assertEquals(1, disconnects)
            assertEquals(emptySet(), runtime.unconfirmedStops.value)
        } finally {release.complete(Unit); runtime.beginRetirement(); pulses.close(); runCurrent()}
    }

    private class Session(private val held: Boolean = false) : VoiceSdkSession {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false; var publishes = 0; var closes = 0; var mutes = 0; var deafens = 0; var failClose = false
        override suspend fun connect(credentials: VoiceConnectionCredentials) {
            entered.complete(Unit)
            if (held) withContext(NonCancellable) {release.await()}
            returned = true
        }
        override suspend fun publishMicrophone(muted: Boolean): Boolean {publishes++; return true}
        override suspend fun setMuted(muted: Boolean): Boolean {mutes++; return true}
        override fun setDeafened(deafened: Boolean) {deafens++}
        override suspend fun close() {closes++; if (failClose) {failClose = false; error("close failed")}}
    }
    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) {value = credentials}
        override suspend fun clear() {value = null}
    }
    private class Transport : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token", "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId("actor"), "Rider", SessionTokenState.ACTIVE, true))
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
    }
}

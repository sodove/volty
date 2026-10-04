package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoicePreparedAudioAdmissionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val first = VoiceOperationId("30000000-0000-0000-0000-000000000003")
    private val second = VoiceOperationId("30000000-0000-0000-0000-000000000005")
    private val phone = VoiceAudioPreferences(VoiceInputPreference.PHONE, VoiceOutputPreference.HEADSET)
    private val headset = VoiceAudioPreferences(VoiceInputPreference.HEADSET, VoiceOutputPreference.HEADSET)

    @Test fun noJoinOrNativeAllocationUntilActualAudioPreferencesAreReady() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val runtime = f.start()
            assertFalse(runtime.join(channel, first)); runCurrent()
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            val joining = async { runtime.joinWithAudio(channel, first, readPreferences = { entered.complete(Unit); release.await(); phone }) }
            runCurrent(); assertTrue(entered.isCompleted); assertFalse(joining.isCompleted)
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            release.complete(Unit); runCurrent()
            assertTrue(joining.await()); assertEquals(1, f.requests)
            assertEquals(listOf(phone), f.allocated)
            assertEquals(phone, assertNotNull(f.audio.latestAllocation.value).diagnostics.state.value.preferences)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun disconnectWhileReadingCannotReconnectFromLatePreferences() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val runtime = f.start()
            val joining = async { runtime.joinWithAudio(channel, first, readPreferences = { entered.complete(Unit); release.await(); phone }) }
            runCurrent(); assertTrue(entered.isCompleted)
            runtime.disconnect(); runCurrent()
            release.complete(Unit); runCurrent()
            assertFalse(joining.await())
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            assertFalse(runtime.state.value.wanted)
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun newerJoinPreparationSupersedesOlderHeldRead() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val runtime = f.start()
            val older = async { runtime.joinWithAudio(channel, first, readPreferences = { entered.complete(Unit); release.await(); phone }) }
            runCurrent(); assertTrue(entered.isCompleted)
            assertTrue(runtime.joinWithAudio(channel, second, readPreferences = {headset})); runCurrent()
            assertEquals(listOf(headset), f.allocated)
            assertEquals(second, runtime.state.value.operationId)
            release.complete(Unit); runCurrent()
            assertFalse(older.await())
            assertEquals(1, f.requests)
            assertEquals(second, runtime.state.value.operationId)
            assertEquals(headset, assertNotNull(f.audio.latestAllocation.value).diagnostics.state.value.preferences)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun logoutWhileReadingDeniesLatePreparedJoin() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val runtime = f.start()
            val joining = async { runtime.joinWithAudio(channel, first, readPreferences = { entered.complete(Unit); release.await(); phone }) }
            runCurrent(); assertTrue(entered.isCompleted)
            val authority = assertNotNull(f.repository.authenticatedAccount.value)
            f.repository.logout(); runCurrent()
            assertFalse(authority.job.isActive)
            release.complete(Unit); runCurrent()
            assertFalse(joining.await())
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            assertTrue(runtime.retired.value)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun cancelledCallerCannotJoinAfterNoncooperativePreferenceReadReturns() = runTest {
        val f = Fixture(this); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var returned = false
        try {
            val runtime = f.start()
            val joining = async { runtime.joinWithAudio(channel, first, readPreferences = {
                withContext(NonCancellable) { entered.complete(Unit); release.await(); returned = true; phone }
            }) }
            runCurrent(); assertTrue(entered.isCompleted)
            joining.cancel(); runCurrent(); assertFalse(returned)
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertFailsWith<CancellationException> {joining.await()}
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
            assertFalse(runtime.retired.value)
        } finally {release.complete(Unit); f.close()}
    }

    @Test fun storageFailureCannotEarnAudioReadinessOrIssueJoin() = runTest {
        val f = Fixture(this)
        try {
            val runtime = f.start()
            assertFailsWith<IllegalStateException> {
                runtime.joinWithAudio(channel, first, readPreferences = {error("Storage unavailable")})
            }
            assertFalse(f.audio.ready)
            assertFalse(runtime.join(channel, first)); runCurrent()
            assertEquals(0, f.requests); assertTrue(f.allocated.isEmpty())
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
            assertTrue(runtime.joinWithAudio(channel, second, readPreferences = {phone})); runCurrent()
            assertEquals(listOf(phone), f.allocated)
            assertEquals(1, f.requests)
        } finally {f.close()}
    }

    private inner class Fixture(val test: TestScope) {
        val repository = DefaultSocialRepository(Transport(), Store(), test.backgroundScope)
        var requests = 0
        val allocated = mutableListOf<VoiceAudioPreferences>()
        val audio = VoiceSessionAudioFactory({error("Unprepared supplier must never be read")}, requiresPreparation = true) {_, preferences, _, _ ->
            allocated += preferences; Session()
        }
        val client = HttpClient(MockEngine) {engine {
            dispatcher = StandardTestDispatcher(test.testScheduler)
            addHandler {
                requests++
                val operation = it.url.encodedPath.removeSuffix("/join").substringAfterLast('/')
                respond(if (it.url.encodedPath.endsWith("/join"))
                    """{"phase":"CONNECTING","operationId":"$operation","sessionId":"40000000-0000-0000-0000-000000000004","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"secret","roomEpoch":"room"}}"""
                else """{"phase":"CANCELLED","sessionId":"40000000-0000-0000-0000-000000000004","cleanupPending":false}""")
            }
        }}
        val coordinator = VoiceAccountCoordinator(client, "https://example.test/v2/nearby",
            StandardTestDispatcher(test.testScheduler), MutableSharedFlow<Unit>(), {100})
        suspend fun start(): AuthenticatedVoiceRuntime {
            test.runCurrent(); assertIs<SocialResult.Success<Unit>>(repository.login(LoginRequest("rider", "password")))
            return coordinator.acquire(assertNotNull(repository.authenticatedAccount.value), audio, {})
        }
        suspend fun close() {coordinator.retireAll(); test.runCurrent(); client.close()}
    }
    private class Session : VoiceSdkSession {
        override suspend fun connect(credentials: VoiceConnectionCredentials) = Unit
        override suspend fun publishMicrophone(muted: Boolean) = true
        override suspend fun setMuted(muted: Boolean) = true
        override fun setDeafened(deafened: Boolean) = Unit
        override suspend fun close() = Unit
    }
    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) {value = credentials}
        override suspend fun clear() {value = null}
    }
    private inner class Transport : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token", "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
    }
}

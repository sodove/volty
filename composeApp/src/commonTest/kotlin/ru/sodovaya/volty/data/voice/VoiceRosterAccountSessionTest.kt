package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRosterAccountSessionTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val session = "30000000-0000-0000-0000-000000000003"
    private val operation = "40000000-0000-0000-0000-000000000004"
    private val tag = VoiceRosterTag(channel, 7)
    private val root = "https://example.test/v2/nearby"
    private fun snapshot() = """{"channelId":"$channel","revision":8,"serverTimeEpochMillis":100000,"peers":[{"actorId":"$actor","sessionId":"$session","operationId":"$operation","phase":"CONNECTED","deadlineEpochMillis":101000,"mediaPresence":"CONNECTED","participantSid":"PA_live"}]}"""

    @Test
    fun logoutClearsEarnedRosterBeforeHeldProducerOrAccountChildCompletes() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                headers += it.headers[HttpHeaders.Authorization]
                if (headers.size == 2) withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    respond(snapshot()).also { returned = true }
                } else respond(snapshot())
            }
        } }
        val old = VoiceRosterAccountSession(authority, client, root, tag, StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
        val held = CoroutineScope(backgroundScope.coroutineContext + authority.job).launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { release.await() }
        }
        var fresh: VoiceRosterAccountSession? = null
        try {
            assertEquals(null, old.state.value.connectedCount)
            runCurrent()
            assertEquals(1, old.state.value.connectedCount)
            assertEquals(actor, old.state.value.peers?.single()?.peer?.actorId)
            assertTrue(old.refresh()); runCurrent()
            assertTrue(entered.isCompleted)
            repository.logout(); runCurrent()
            assertTrue(authority.job.isCancelled)
            assertFalse(authority.job.isCompleted)
            assertTrue(old.state.value.retired)
            assertEquals(null, old.state.value.connectedCount)
            assertEquals(null, old.state.value.peers)
            assertFalse(old.refresh())
            assertFailsWith<CancellationException> { old.port.snapshot(tag) }
            repository.login(LoginRequest("rider", "password"))
            val current = assertNotNull(repository.authenticatedAccount.value)
            fresh = VoiceRosterAccountSession(current, client, root, tag.copy(generation = 8),
                StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            runCurrent()
            assertEquals(1, fresh.state.value.connectedCount)
            assertEquals(listOf("Bearer token-1", "Bearer token-1", "Bearer token-2"), headers.filterNotNull())
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertEquals(null, old.state.value.connectedCount)
            assertTrue(old.state.value.retired)
            assertEquals(1, fresh.state.value.connectedCount)
            assertTrue(current.job.isActive)
            assertFalse(old.cleanupFailed.value)
        } finally { release.complete(Unit); fresh?.retire(); old.retire(); held.join(); pulses.close(); client.close(); runCurrent() }
    }

    @Test
    fun refreshUsesAuthoritativeTokenAndCancelledCallerRetirementPreservesLogin() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { headers += it.headers[HttpHeaders.Authorization]; respond(snapshot()) }
        } }
        val old = VoiceRosterAccountSession(authority, client, root, tag, StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
        var fresh: VoiceRosterAccountSession? = null
        try {
            runCurrent(); assertEquals(1, old.state.value.connectedCount)
            assertEquals<SocialResult<List<FriendSummary>>>(SocialResult.Success(emptyList()), repository.listFriends())
            assertSame(authority, repository.authenticatedAccount.value)
            assertTrue(old.refresh()); runCurrent()
            assertEquals(1, old.state.value.connectedCount)
            val caller = Job().apply { cancel() }
            var returned = false
            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                old.retire(); returned = true
            }
            runCurrent()
            assertTrue(returned)
            assertTrue(old.state.value.retired)
            assertEquals(null, old.state.value.connectedCount)
            assertTrue(authority.job.isActive)
            assertSame(authority, repository.authenticatedAccount.value)
            assertFailsWith<CancellationException> { old.port.snapshot(tag) }
            fresh = VoiceRosterAccountSession(authority, client, root, tag.copy(generation = 8),
                StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            runCurrent(); assertEquals(1, fresh.state.value.connectedCount)
            assertEquals(listOf("Bearer token-1", "Bearer refreshed", "Bearer refreshed"), headers.filterNotNull())
        } finally { fresh?.retire(); old.retire(); pulses.close(); client.close(); runCurrent() }
    }

    @Test
    fun accessDeniedRetiresConsumerTransportWithoutRetiringAuthentication() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        var issued = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                issued++
                if (issued == 1) respond("", HttpStatusCode.Forbidden) else respond(snapshot())
            }
        } }
        val old = VoiceRosterAccountSession(authority, client, root, tag,
            StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
        var fresh: VoiceRosterAccountSession? = null
        try {
            runCurrent()
            assertEquals(1, issued)
            assertTrue(old.state.value.retired)
            assertEquals(null, old.state.value.connectedCount)
            assertFailsWith<CancellationException> { old.port.snapshot(tag) }
            assertEquals(1, issued)
            assertFalse(old.refresh())
            assertTrue(authority.job.isActive)
            fresh = VoiceRosterAccountSession(authority, client, root, tag.copy(generation = 8),
                StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            runCurrent()
            assertEquals(1, fresh.state.value.connectedCount)
            assertEquals(2, issued)
            assertFalse(old.cleanupFailed.value)
        } finally { fresh?.retire(); old.retire(); pulses.close(); client.close(); runCurrent() }
    }

    @Test
    fun invalidConstructionAllocatesNoAccountChildrenAndDoesNotIssueHttp() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val children = authority.job.children.toList()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond(snapshot()) } } }
        try {
            assertFailsWith<IllegalArgumentException> {
                VoiceRosterAccountSession(authority, client, "http://example.test", tag,
                    StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            }
            assertFailsWith<IllegalArgumentException> {
                VoiceRosterAccountSession(authority, client, root, VoiceRosterTag("bad", 1),
                    StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            }
            assertFailsWith<IllegalArgumentException> {
                VoiceRosterAccountSession(authority, client, root, tag,
                    UnconfinedTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            }
            assertEquals(children, authority.job.children.toList())
            assertEquals(0, issued)
            repository.logout(); runCurrent()
            assertFailsWith<IllegalStateException> {
                VoiceRosterAccountSession(authority, client, root, tag,
                    StandardTestDispatcher(testScheduler), pulses.receiveAsFlow(), {100})
            }
            assertEquals(0, issued)
        } finally { pulses.close(); client.close() }
    }

    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private class Transport(private val actor: String) : SocialTransport by UnavailableSocialTransport() {
        var ordinal = 0
        override suspend fun login(request: LoginRequest) = SocialResult.Success(
            SessionCredentials("token-${++ordinal}", "refresh-$ordinal", Long.MAX_VALUE),
        )
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(
            SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true),
        )
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
        override suspend fun refreshSession(refreshToken: String) = SocialResult.Success(
            SessionCredentials("refreshed", "refresh-next", Long.MAX_VALUE),
        )
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> =
            if (accessToken == "refreshed") SocialResult.Success(emptyList()) else SocialResult.Failure(SocialFailure.Unauthorized)
    }
}

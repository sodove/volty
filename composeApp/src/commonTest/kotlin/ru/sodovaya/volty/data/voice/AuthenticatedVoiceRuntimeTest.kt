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
class AuthenticatedVoiceRuntimeTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val operation = VoiceOperationId("30000000-0000-0000-0000-000000000003")
    private val session = "40000000-0000-0000-0000-000000000004"
    private val root = "https://example.test/v2/nearby"
    private class Wire {
        var pending = false
        val headers = mutableListOf<String?>(); val paths = mutableListOf<String>()
    }
    private fun TestScope.client(wire: Wire) = HttpClient(MockEngine) {engine {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler {
            wire.headers += it.headers[HttpHeaders.Authorization]; wire.paths += it.url.encodedPath
            respond(if (it.url.encodedPath.endsWith("/join"))
                """{"phase":"CONNECTING","operationId":"${operation.value}","sessionId":"$session","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"participant-secret","roomEpoch":"room"}}"""
            else """{"phase":"CANCELLED","sessionId":"$session","cleanupPending":${wire.pending}}""")
        }
    }}
    private fun TestScope.repository() = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)

    @Test fun logoutRetiresAutomaticallyButOriginalNativeAndRemoteRetriesOutliveHeldAuthChild() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val wire = Wire(); val client = client(wire); val sdk = Session()
        val pulses = Channel<Unit>(Channel.UNLIMITED); var now = 100L
        val runtime = AuthenticatedVoiceRuntime.create(authority, client, root,
            StandardTestDispatcher(testScheduler), VoiceSdkSessionFactory {_, _ -> sdk},
            pulses.receiveAsFlow(), {now}, {}, mayAllocateAudio = {true})
        val release = CompletableDeferred<Unit>()
        val held = authority.scope().launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {release.await()}
        }
        try {
            assertTrue(runtime.join(channel, operation)); runCurrent()
            assertEquals(1, sdk.connects); assertEquals(1, sdk.publishes)
            sdk.failClose = true; wire.pending = true
            repository.logout(); runCurrent()
            assertTrue(authority.job.isCancelled); assertFalse(authority.job.isCompleted)
            assertTrue(runtime.retired.value); assertFalse(runtime.state.value.wanted)
            assertEquals(1, sdk.closes)
            assertEquals(setOf(VoiceGeneration(1)), runtime.failedLocalCleanup.value)
            assertTrue(runtime.pendingRemoteStops.value.isNotEmpty())
            assertFalse(runtime.join(channel, VoiceOperationId(actor)))
            pulses.send(Unit); runCurrent() // Arm cleanup cadence before advancing the clock.
            wire.pending = false; now = 3100; pulses.send(Unit); runCurrent()
            assertEquals(2, sdk.closes)
            assertFalse(runtime.state.value.cleanupPending)
            assertEquals(emptySet(), runtime.failedLocalCleanup.value)
            assertEquals(emptySet(), runtime.unconfirmedStops.value)
            assertEquals(emptySet(), runtime.pendingRemoteStops.value)
            assertFalse(authority.job.isCompleted)
            assertEquals(listOf("Bearer token", "Bearer token", "Bearer token"), wire.headers.filterNotNull())
            assertEquals(1, wire.paths.count {it.endsWith("/join")})
            assertFalse(runtime.retirementFailed.value)
        } finally {release.complete(Unit); runtime.retire(); held.join(); pulses.close(); client.close(); runCurrent()}
    }

    @Test fun cancelledCallerDisconnectClosesVoiceWhileAccountRemainsAuthenticated() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val wire = Wire(); val client = client(wire); val sdk = Session()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = AuthenticatedVoiceRuntime.create(authority, client, root,
            StandardTestDispatcher(testScheduler), VoiceSdkSessionFactory {_, _ -> sdk},
            pulses.receiveAsFlow(), {100}, {}, mayAllocateAudio = {true})
        try {
            assertTrue(runtime.join(channel, operation)); runCurrent()
            val caller = Job().apply {cancel()}; var returned = false
            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.disconnect(); returned = true
            }
            runCurrent()
            assertTrue(returned); assertEquals(1, sdk.closes)
            assertFalse(runtime.state.value.wanted); assertFalse(runtime.state.value.cleanupPending)
            assertFalse(runtime.retired.value)
            assertTrue(authority.job.isActive)
            assertSame(authority, repository.authenticatedAccount.value)
            assertEquals(2, wire.paths.size)
            assertTrue(wire.paths.last().endsWith("/disconnect"))
        } finally {runtime.retire(); pulses.close(); client.close(); runCurrent()}
    }

    @Test fun foregroundDisconnectSubmissionUsesOriginalRootDespiteCancelledCaller() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val wire = Wire(); val client = client(wire); val sdk = Session()
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        val runtime = AuthenticatedVoiceRuntime.create(authority, client, root,
            StandardTestDispatcher(testScheduler), VoiceSdkSessionFactory {_, _ -> sdk},
            pulses.receiveAsFlow(), {100}, {}, mayAllocateAudio = {true})
        try {
            assertTrue(runtime.join(channel, operation)); runCurrent()
            val caller = Job().apply {cancel()}; var returned = false
            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.requestDisconnect(); returned = true
            }
            runCurrent()
            assertTrue(returned); assertEquals(1, sdk.closes)
            assertFalse(runtime.disconnectFailed.value)
            assertFalse(runtime.state.value.wanted); assertFalse(runtime.retired.value)
            assertTrue(authority.job.isActive)
            assertEquals(2, wire.paths.size)
            assertTrue(wire.paths.last().endsWith("/disconnect"))
        } finally {runtime.retire(); pulses.close(); client.close(); runCurrent()}
    }

    @Test fun globalNativeGateDeniesJoinBeforeHttpAndSdkButDoesNotPreventRetirement() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val wire = Wire(); val client = client(wire); val sdk = Session()
        val pulses = Channel<Unit>(Channel.UNLIMITED); var allowed = false; var created = 0
        val runtime = AuthenticatedVoiceRuntime.create(authority, client, root,
            StandardTestDispatcher(testScheduler), VoiceSdkSessionFactory {_, _ -> created++; sdk},
            pulses.receiveAsFlow(), {100}, {}, mayAllocateAudio = {allowed})
        try {
            assertFalse(runtime.join(channel, operation)); runCurrent()
            assertEquals(0, created); assertTrue(wire.paths.isEmpty())
            allowed = true
            assertTrue(runtime.join(channel, operation)); runCurrent()
            assertEquals(1, created)
            allowed = false
            val caller = Job().apply {cancel()}; var returned = false
            CoroutineScope(coroutineContext + caller).launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.retire(); returned = true
            }
            runCurrent()
            assertTrue(returned); assertTrue(runtime.retired.value)
            assertEquals(1, sdk.closes); assertTrue(authority.job.isActive)
            assertFalse(runtime.join(channel, VoiceOperationId(actor)))
        } finally {runtime.retire(); pulses.close(); client.close(); runCurrent()}
    }

    @Test fun invalidQueuedDispatcherDoesNotAllocateRootChildrenOrIssueHttp() = runTest {
        val repository = repository(); runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val children = backgroundScope.coroutineContext[Job]!!.children.toList()
        val wire = Wire(); val client = client(wire); var created = 0
        val pulses = Channel<Unit>(Channel.UNLIMITED)
        try {
            assertFailsWith<IllegalArgumentException> {
                AuthenticatedVoiceRuntime.create(authority, client, root,
                    UnconfinedTestDispatcher(testScheduler), VoiceSdkSessionFactory {_, _ -> created++; Session()},
                    pulses.receiveAsFlow(), {100}, {}, mayAllocateAudio = {true})
            }
            assertEquals(children, backgroundScope.coroutineContext[Job]!!.children.toList())
            assertTrue(wire.paths.isEmpty()); assertEquals(0, created)
        } finally {pulses.close(); client.close()}
    }

    private class Session : VoiceSdkSession {
        var connects = 0; var publishes = 0; var closes = 0; var failClose = false
        override suspend fun connect(credentials: VoiceConnectionCredentials) {connects++}
        override suspend fun publishMicrophone(muted: Boolean): Boolean {publishes++; return true}
        override suspend fun setMuted(muted: Boolean) = true
        override fun setDeafened(deafened: Boolean) = Unit
        override suspend fun close() {closes++; if (failClose) {failClose = false; error("close failed")}}
    }
    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) {value = credentials}
        override suspend fun clear() {value = null}
    }
    private class Transport(private val actor: String) : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token", "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
    }
}

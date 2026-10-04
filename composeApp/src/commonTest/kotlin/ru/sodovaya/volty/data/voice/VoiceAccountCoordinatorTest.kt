package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceAccountCoordinatorTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val channel = "20000000-0000-0000-0000-000000000002"
    private val first = VoiceOperationId("30000000-0000-0000-0000-000000000003")
    private val second = VoiceOperationId("30000000-0000-0000-0000-000000000005")
    private val root = "https://example.test/v2/nearby"

    @Test fun replacementWaitsForActualOldProducerCompletionEvenAfterSuccessfulClose() = runTest {
        val f = Fixture(this); val oldSdk = Session(holdConnect = true); val freshSdk = Session()
        try {
            runCurrent(); f.login()
            val old = f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(oldSdk), {})
            assertTrue(old.join(channel, first)); runCurrent()
            assertTrue(oldSdk.entered.isCompleted)
            f.repository.logout(); runCurrent()
            assertEquals(1, oldSdk.closes)
            assertFalse(old.state.value.cleanupPending)
            assertEquals(1, old.nativeInFlight.value)
            f.login()
            val fresh = f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(freshSdk), {})
            assertFalse(fresh.join(channel, second)); runCurrent()
            assertEquals(0, freshSdk.connects)
            assertEquals(2, f.wire.headers.size)
            oldSdk.release.complete(Unit); runCurrent()
            assertTrue(oldSdk.returned); assertEquals(0, old.nativeInFlight.value)
            assertEquals(0, oldSdk.publishes)
            assertTrue(fresh.join(channel, second)); runCurrent()
            assertEquals(1, freshSdk.connects); assertEquals(1, freshSdk.publishes)
            assertEquals(listOf("Bearer token-1", "Bearer token-1", "Bearer token-2"), f.wire.headers.filterNotNull())
            assertFalse(old.join(channel, second))
        } finally {oldSdk.release.complete(Unit); f.close()}
    }

    @Test fun replacementWaitsForFailedNativeCloseRetryInsteadOfServerStopAlone() = runTest {
        val f = Fixture(this); val oldSdk = Session(); val freshSdk = Session()
        try {
            runCurrent(); f.login()
            val old = f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(oldSdk), {})
            assertTrue(old.join(channel, first)); runCurrent()
            oldSdk.failClose = true
            f.repository.logout(); runCurrent()
            assertEquals(1, oldSdk.closes)
            assertTrue(old.failedLocalCleanup.value.isNotEmpty())
            assertTrue(old.pendingRemoteStops.value.isEmpty())
            f.login()
            val fresh = f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(freshSdk), {})
            assertFalse(fresh.join(channel, second)); runCurrent()
            assertEquals(0, freshSdk.connects)
            f.pulses.emit(Unit); runCurrent()
            f.now = 3100; f.pulses.emit(Unit); runCurrent()
            assertEquals(2, oldSdk.closes)
            assertTrue(old.failedLocalCleanup.value.isEmpty())
            assertTrue(fresh.join(channel, second)); runCurrent()
            assertEquals(1, freshSdk.connects)
        } finally {f.close()}
    }

    @Test fun duplicatePendingAccountAcquisitionSharesOneRuntimeAndNativeOwner() = runTest {
        val f = Fixture(this); val sdk = Session(); var created = 0
        try {
            runCurrent(); f.login()
            val authority = assertNotNull(f.repository.authenticatedAccount.value)
            val factory = VoiceSdkSessionFactory {_, _ -> created++; sdk}
            val one = async {f.coordinator.acquire(authority, factory, {})}
            val two = async {f.coordinator.acquire(authority, factory, {})}
            runCurrent()
            assertSame(one.await(), two.await())
            assertTrue(one.await().join(channel, first)); runCurrent()
            assertEquals(1, created); assertEquals(1, sdk.connects)
            assertEquals(1, f.wire.headers.size)
        } finally {f.close()}
    }

    @Test fun cancelledAcquisitionWaiterCannotAbandonRootOwnedConstructionOrCancelLogin() = runTest {
        val transport = Transport(expired = true)
        val f = Fixture(this, transport); val sdk = Session()
        try {
            runCurrent(); f.login()
            val authority = assertNotNull(f.repository.authenticatedAccount.value)
            val waiter = backgroundScope.async {f.coordinator.acquire(authority, factory(sdk), {})}
            runCurrent(); assertTrue(transport.entered.isCompleted)
            waiter.cancel(); runCurrent()
            assertTrue(waiter.isCancelled)
            assertTrue(authority.job.isActive)
            val nextWaiter = async {f.coordinator.acquire(authority, factory(sdk), {})}
            runCurrent(); assertFalse(nextWaiter.isCompleted)
            transport.release.complete(Unit); runCurrent()
            assertTrue(transport.returned)
            val runtime = nextWaiter.await()
            assertSame(runtime, f.coordinator.acquire(authority, factory(sdk), {}))
            assertTrue(runtime.join(channel, first)); runCurrent()
            assertEquals(1, sdk.connects)
            assertEquals(listOf("refresh-1"), transport.refreshes)
            assertEquals(listOf("Bearer refreshed"), f.wire.headers.filterNotNull())
            assertSame(authority, f.repository.authenticatedAccount.value)
        } finally {transport.release.complete(Unit); f.close()}
    }

    @Test fun delayedObsoleteBuilderNeverRetiresTheNewerSelectedRuntime() = runTest {
        // A queued serial dispatcher need not execute builders in registration order.
        val delegate = StandardTestDispatcher(testScheduler)
        val reverse = object : CoroutineDispatcher() {
            private val pending = mutableListOf<Runnable>()
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                pending += block
                delegate.dispatch(context, Runnable {pending.removeAt(pending.lastIndex).run()})
            }
        }
        val f = Fixture(this, dispatcher = reverse); val oldSdk = Session(); val freshSdk = Session()
        try {
            runCurrent(); f.login()
            val ui = CoroutineScope(backgroundScope.coroutineContext + reverse).async(start = CoroutineStart.UNDISPATCHED) {
                val authority = assertNotNull(f.repository.authenticatedAccount.value)
                val old = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {f.coordinator.acquire(authority, factory(oldSdk), {})}
                }
                f.repository.logout(); f.login()
                val next = async(start = CoroutineStart.UNDISPATCHED) {
                    f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(freshSdk), {})
                }
                val fresh = next.await()
                assertFalse(fresh.retired.value)
                assertTrue(old.await().isFailure)
                assertFalse(fresh.retired.value)
                assertTrue(fresh.join(channel, second))
            }
            runCurrent(); ui.await(); runCurrent()
            assertEquals(0, oldSdk.connects); assertEquals(1, freshSdk.connects)
            assertEquals(listOf("Bearer token-2"), f.wire.headers.filterNotNull())
        } finally {f.close()}
    }

    @Test fun foregroundLossFencesHeldConnectWhileOriginalDisconnectRemainsAllowed() = runTest {
        var permission = false; var ids = 0
        val gate = VoiceForegroundGate({permission}, {"50000000-0000-0000-0000-${(++ids).toString().padStart(12, '0')}"})
        val f = Fixture(this, nativeEnvironment = {gate.allows(it)}); val sdk = Session(holdConnect = true)
        try {
            runCurrent(); f.login()
            val runtime = f.coordinator.acquire(assertNotNull(f.repository.authenticatedAccount.value), factory(sdk), {})
            assertFalse(runtime.join(channel, first)); runCurrent()
            assertTrue(f.wire.headers.isEmpty())
            val ticket = gate.request(runtime) {backgroundScope.launch {runtime.disconnect()}}
            val service = Any()
            assertFalse(gate.acknowledge(ticket, service))
            permission = true
            assertTrue(gate.acknowledge(ticket, service))
            assertTrue(runtime.join(channel, first)); runCurrent()
            assertTrue(sdk.entered.isCompleted)
            assertTrue(gate.destroyed(service)); runCurrent()
            assertEquals(1, sdk.closes)
            assertFalse(runtime.state.value.wanted)
            assertFalse(runtime.retired.value)
            assertTrue(assertNotNull(f.repository.authenticatedAccount.value).job.isActive)
            sdk.release.complete(Unit); runCurrent()
            assertTrue(sdk.returned); assertEquals(0, sdk.publishes)
            assertFalse(runtime.join(channel, second))
            assertEquals(listOf("Bearer token-1", "Bearer token-1"), f.wire.headers.filterNotNull())
        } finally {sdk.release.complete(Unit); f.close()}
    }

    private fun factory(session: Session) = VoiceSdkSessionFactory {_, _ -> session}
    private class Wire {val headers = mutableListOf<String?>()}
    private fun TestScope.client(wire: Wire) = HttpClient(MockEngine) {engine {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler {
            val path = it.url.encodedPath; val bearer = it.headers[HttpHeaders.Authorization]
            wire.headers += bearer
            val session = if (bearer == "Bearer token-1") "40000000-0000-0000-0000-000000000004" else "40000000-0000-0000-0000-000000000006"
            val operation = path.removeSuffix("/join").substringAfterLast('/')
            val stoppedSession = path.removeSuffix("/disconnect").substringAfterLast('/')
            respond(if (path.endsWith("/join"))
                """{"phase":"CONNECTING","operationId":"$operation","sessionId":"$session","remainingLeaseMillis":10000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"participant-secret","roomEpoch":"room"}}"""
            else """{"phase":"CANCELLED","sessionId":"$stoppedSession","cleanupPending":false}""")
        }
    }}
    private inner class Fixture(val test: TestScope, transport: Transport = Transport(),
                                dispatcher: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler),
                                nativeEnvironment: (AuthenticatedVoiceRuntime) -> Boolean = {true}) {
        val repository = DefaultSocialRepository(transport, Store(), test.backgroundScope)
        val wire = Wire(); val client = test.client(wire)
        val pulses = MutableSharedFlow<Unit>(extraBufferCapacity = 16); var now = 100L
        val coordinator = VoiceAccountCoordinator(client, root, dispatcher, pulses, {now}, nativeEnvironment)
        suspend fun login() {assertIs<SocialResult.Success<Unit>>(repository.login(LoginRequest("rider", "password")))}
        suspend fun close() {coordinator.retireAll(); test.runCurrent(); client.close()}
    }
    private class Session(private val holdConnect: Boolean = false) : VoiceSdkSession {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var connects = 0; var publishes = 0; var closes = 0; var returned = false; var failClose = false
        override suspend fun connect(credentials: VoiceConnectionCredentials) {
            connects++
            if (holdConnect) withContext(NonCancellable) {entered.complete(Unit); release.await(); returned = true}
        }
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
    private inner class Transport(private val expired: Boolean = false) : SocialTransport by UnavailableSocialTransport() {
        private var ordinal = 0
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val refreshes = mutableListOf<String>(); var returned = false
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token-${++ordinal}", "refresh-$ordinal", if (expired) 0 else Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
        override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
            refreshes += refreshToken
            return withContext(NonCancellable) {
                entered.complete(Unit); release.await()
                SocialResult.Success(SessionCredentials("refreshed", "refresh-next", Long.MAX_VALUE)).also {returned = true}
            }
        }
    }
}

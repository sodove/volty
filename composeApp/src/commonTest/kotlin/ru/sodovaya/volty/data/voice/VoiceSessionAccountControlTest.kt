package ru.sodovaya.volty.data.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.voice.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionAccountControlTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val operation = VoiceOperationId("20000000-0000-0000-0000-000000000002")
    private val channel = "30000000-0000-0000-0000-000000000003"
    private val session = VoiceSessionId("40000000-0000-0000-0000-000000000004")
    private val generation = VoiceGeneration(7)
    private val tag = VoiceSessionTag(session, generation)
    private val root = "https://example.test/v2/nearby"
    private fun grant() = """{"phase":"CONNECTING","operationId":"${operation.value}","sessionId":"${session.value}","remainingLeaseMillis":5000,"credentials":{"serverUrl":"wss://sfu.example.test","participantToken":"participant-secret","roomEpoch":"room"}}"""
    private fun ended() = """{"phase":"CANCELLED","operationId":"${operation.value}","sessionId":"${session.value}","cleanupPending":false}"""

    @Test fun logoutRetainsOnlyOriginalIntentCleanupAndNeverUsesReplacementToken() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { headers += it.headers[HttpHeaders.Authorization]; respond(if (it.url.encodedPath.endsWith("/join")) grant() else ended()) }
        } }
        val old = VoiceSessionAccountControl.create(authority, client, root) {100}
        var fresh: VoiceSessionAccountControl? = null
        try {
            assertEquals(tag, assertIs<VoiceHttpReply.Credentials>(old.join(operation, generation, channel)).tag)
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.join(operation, generation, actor))
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.join(operation, generation, channel, true))
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.status(operation, VoiceGeneration(8)))
            assertEquals(1, headers.size)
            repository.logout()
            assertFailsWith<CancellationException> { old.join(operation, generation, channel) }
            assertFailsWith<CancellationException> { old.status(operation, generation) }
            assertFailsWith<CancellationException> { old.heartbeat(tag) }
            assertEquals(false, assertIs<VoiceHttpReply.Ended>(old.disconnect(tag)).cleanupPending)
            assertEquals(2, headers.size)
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.cancel(operation, VoiceGeneration(8)))
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.disconnect(tag.copy(generation = VoiceGeneration(8))))
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.ACCESS_DENIED), old.cancel(VoiceOperationId(channel), generation))
            assertEquals(2, headers.size)
            repository.login(LoginRequest("rider", "password"))
            val current = assertNotNull(repository.authenticatedAccount.value)
            fresh = VoiceSessionAccountControl.create(current, client, root) {100}
            assertIs<VoiceHttpReply.Credentials>(fresh.join(operation, generation, channel))
            assertIs<VoiceHttpReply.Ended>(old.cancel(operation, generation))
            assertEquals(listOf("Bearer token-1", "Bearer token-1", "Bearer token-2", "Bearer token-1"), headers.filterNotNull())
            assertTrue(current.job.isActive)
            old.close()
            assertIs<VoiceHttpReply.Credentials>(fresh.join(operation, generation, channel))
            assertEquals("Bearer token-2", headers.last())
        } finally { fresh?.close(); old.close(); client.close() }
    }

    @Test fun cancelBeforeLateJoinReplySurvivesLogoutWithoutPublishingGrant() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                headers += it.headers[HttpHeaders.Authorization]
                if (it.url.encodedPath.endsWith("/join")) withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); respond(grant()).also { returned = true }
                } else respond(ended())
            }
        } }
        val old = VoiceSessionAccountControl.create(authority, client, root) {100}
        val pending = async { old.join(operation, generation, channel) }
        try {
            runCurrent(); assertTrue(entered.isCompleted)
            repository.logout()
            assertIs<VoiceHttpReply.Ended>(old.cancel(operation, generation))
            assertFalse(returned)
            assertFalse(pending.isCompleted)
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertFailsWith<CancellationException> { pending.await() }
            assertIs<VoiceHttpReply.Ended>(old.disconnect(tag))
            assertEquals(listOf("Bearer token-1", "Bearer token-1", "Bearer token-1"), headers.filterNotNull())
        } finally { release.complete(Unit); pending.cancelAndJoin(); old.close(); client.close() }
    }

    @Test fun refreshedTokenIsRetainedButUnauthorizedCleanupIsNotStopAcknowledgement() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                headers += it.headers[HttpHeaders.Authorization]
                when {
                    it.url.encodedPath.endsWith("/join") -> respond(grant())
                    it.url.encodedPath.endsWith("/cancel") -> respond("", HttpStatusCode.Unauthorized)
                    else -> respond("""{"phase":"CONNECTED","sessionId":"${session.value}","remainingLeaseMillis":5000}""")
                }
            }
        } }
        val old = VoiceSessionAccountControl.create(authority, client, root) {100}
        try {
            assertIs<VoiceHttpReply.Credentials>(old.join(operation, generation, channel))
            assertIs<SocialResult.Success<List<FriendSummary>>>(repository.listFriends())
            assertIs<VoiceHttpReply.Acknowledged>(old.heartbeat(tag))
            old.retireAdmission()
            assertFailsWith<CancellationException> { old.heartbeat(tag) }
            assertEquals(VoiceHttpReply.Failed(VoiceHttpFailure.UNAUTHORIZED), old.cancel(operation, generation))
            assertEquals(listOf("Bearer token-1", "Bearer refreshed", "Bearer refreshed"), headers.filterNotNull())
            assertTrue(authority.job.isActive)
            old.close()
            assertFailsWith<CancellationException> { old.cancel(operation, generation) }
            assertEquals(3, headers.size)
        } finally { old.close(); client.close() }
    }

    @Test fun ownerRecordsIntentBeforeQueuedJoinProducerCanBeCancelled() = runTest {
        val remembered = mutableListOf<Pair<VoiceOperationId, VoiceGeneration>>()
        var joins = 0
        var cancellations = 0
        val port = object : VoiceSessionControlPort {
            override fun rememberJoinIntent(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean) {
                remembered += operation to generation
            }
            override suspend fun join(operation: VoiceOperationId, generation: VoiceGeneration, channel: String, replace: Boolean): VoiceHttpReply { joins++; error("Producer must not enter") }
            override suspend fun status(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply = error("Unexpected status")
            override suspend fun heartbeat(tag: VoiceSessionTag): VoiceHttpReply = error("Unexpected heartbeat")
            override suspend fun disconnect(tag: VoiceSessionTag): VoiceHttpReply = error("Unexpected disconnect")
            override suspend fun cancel(operation: VoiceOperationId, generation: VoiceGeneration): VoiceHttpReply {
                assertEquals(listOf(operation to generation), remembered)
                cancellations++
                return VoiceHttpReply.Ended(generation, null, "CANCELLED", false)
            }
        }
        val owner = VoiceControlOwner(backgroundScope, port) {}
        owner.join(channel, operation)
        owner.stop()
        runCurrent()
        assertEquals(0, joins)
        assertEquals(1, cancellations)
        assertEquals(listOf(operation to VoiceGeneration(1)), remembered)
        assertFalse(owner.state.value.wanted)
        assertEquals(emptySet(), owner.unconfirmedStops.value)
        assertEquals(emptySet(), owner.pendingRemoteStops.value)
    }

    @Test fun queuedOwnerCancellationUsesActualHttpWithCredentialsCapturedBeforeAnyJoin() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent(); repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val paths = mutableListOf<String>()
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                paths += it.url.encodedPath
                headers += it.headers[HttpHeaders.Authorization]
                respond(ended())
            }
        } }
        val control = VoiceSessionAccountControl.create(authority, client, root) {100}
        try {
            assertTrue(paths.isEmpty())
            val owner = VoiceControlOwner(backgroundScope, control) {}
            owner.join(channel, operation)
            owner.stop()
            // Both intents already queued. Withdraw auth before the join producer can run.
            repository.logout()
            runCurrent()
            assertEquals(listOf("/v2/nearby/operations/${operation.value}/cancel"), paths)
            assertEquals(listOf("Bearer token-1"), headers.filterNotNull())
            assertFalse(authority.job.isActive)
            assertFalse(owner.state.value.wanted)
            assertEquals(emptySet(), owner.unconfirmedStops.value)
            assertEquals(emptySet(), owner.pendingRemoteStops.value)
        } finally { control.close(); client.close() }
    }

    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private class Transport(private val actor: String) : SocialTransport by UnavailableSocialTransport() {
        private var ordinal = 0
        override suspend fun login(request: LoginRequest) = SocialResult.Success(SessionCredentials("token-${++ordinal}", "refresh-$ordinal", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true))
        override suspend fun logout(accessToken: String) = SocialResult.Success(Unit)
        override suspend fun refreshSession(refreshToken: String) = SocialResult.Success(SessionCredentials("refreshed", "refresh-next", Long.MAX_VALUE))
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> =
            if (accessToken == "refreshed") SocialResult.Success(emptyList()) else SocialResult.Failure(SocialFailure.Unauthorized)
    }
}

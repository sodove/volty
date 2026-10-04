package ru.sodovaya.volty.data.nearby

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.data.social.*
import ru.sodovaya.volty.domain.social.*
import ru.sodovaya.volty.domain.nearby.community.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TrustedNearbyHttpAccountTest {
    private val actor = "10000000-0000-0000-0000-000000000001"
    private val root = "https://example.test/v2/nearby"

    @Test
    fun authoritativeRefreshUpdatesHeaderAndConsumerRetirementDoesNotRetireLogin() = runTest {
        val transport = Transport(actor)
        val repository = DefaultSocialRepository(transport, Store(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("rider", "password"))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine { dispatcher = StandardTestDispatcher(testScheduler); addHandler {
            headers += it.headers[HttpHeaders.Authorization]
            respond("""{"items":[]}""")
        } } }
        val first = NearbyAccountHttpSession(authority, client, root)
        val second = NearbyAccountHttpSession(authority, client, root)
        try {
            assertEquals<CommunityResult<List<CommunitySummary>>>(CommunityResult.Ready(emptyList()), first.communities.memberships())
            assertEquals<SocialResult<List<FriendSummary>>>(SocialResult.Success(emptyList()), repository.listFriends())
            assertSame(authority, repository.authenticatedAccount.value)
            assertEquals<CommunityResult<List<CommunitySummary>>>(CommunityResult.Ready(emptyList()), second.communities.memberships())
            assertFailsWith<IllegalStateException> { second.refresh(NearbyAccountCredentials(actor, "spoof")) }
            first.retire()
            assertFailsWith<CancellationException> { first.communities.memberships() }
            assertTrue(authority.job.isActive)
            assertSame(authority, repository.authenticatedAccount.value)
            assertEquals("refreshed", authority.bearer())
            assertEquals<CommunityResult<List<CommunitySummary>>>(CommunityResult.Ready(emptyList()), second.communities.memberships())
            assertEquals(listOf("Bearer token-1", "Bearer refreshed", "Bearer refreshed"), headers.filterNotNull())
        } finally { first.retire(); second.retire(); client.close(); runCurrent() }
    }

    @Test
    fun lateResponseAndNewRequestsFromOldAuthorityAreDeniedAfterSameActorRelogin() = runTest {
        val repository = DefaultSocialRepository(Transport(actor), Store(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("rider", "password"))
        val previous = assertNotNull(repository.authenticatedAccount.value)
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var returned = false
        val headers = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                headers += it.headers[HttpHeaders.Authorization]
                if (headers.size == 1) withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    respond("""{"items":[]}""").also { returned = true }
                } else respond("""{"items":[]}""")
            }
        } }
        val old = NearbyAccountHttpSession(previous, client, root)
        val request = async { old.communities.memberships() }
        var fresh: NearbyAccountHttpSession? = null
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            repository.login(LoginRequest("rider", "password"))
            assertTrue(previous.job.isCancelled)
            val current = assertNotNull(repository.authenticatedAccount.value)
            assertNotSame(previous, current)
            fresh = NearbyAccountHttpSession(current, client, root)
            assertEquals<CommunityResult<List<CommunitySummary>>>(CommunityResult.Ready(emptyList()), fresh.communities.memberships())
            assertFailsWith<CancellationException> { old.communities.memberships() }
            release.complete(Unit)
            runCurrent()
            assertTrue(returned)
            assertFailsWith<CancellationException> { request.await() }
            assertEquals(listOf("Bearer token-1", "Bearer token-2"), headers.filterNotNull())
            assertTrue(current.job.isActive)
        } finally { release.complete(Unit); old.retire(); fresh?.retire(); client.close(); runCurrent() }
    }

    @Test
    fun noncanonicalLegacyActorCannotCreateNearbyConsumer() = runTest {
        val repository = DefaultSocialRepository(Transport("u1"), Store(), backgroundScope)
        runCurrent()
        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("legacy", "password")))
        val authority = assertNotNull(repository.authenticatedAccount.value)
        var issued = 0
        val client = HttpClient(MockEngine) { engine { addHandler { issued++; respond("") } } }
        try {
            assertFailsWith<IllegalArgumentException> { NearbyAccountHttpSession(authority, client, root) }
            assertEquals(0, issued)
            assertTrue(authority.job.isActive)
            assertSame(authority, repository.authenticatedAccount.value)
        } finally { client.close() }
    }

    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private class Transport(private val actor: String) : SocialTransport by UnavailableSocialTransport() {
        private var loginOrdinal = 0
        override suspend fun login(request: LoginRequest) = SocialResult.Success(
            SessionCredentials("token-${++loginOrdinal}", "refresh-$loginOrdinal", Long.MAX_VALUE),
        )
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(
            SocialSession.Authenticated(SocialUserId(actor), "Rider", SessionTokenState.ACTIVE, true),
        )
        override suspend fun refreshSession(refreshToken: String) = SocialResult.Success(
            SessionCredentials("refreshed", "refresh-next", Long.MAX_VALUE),
        )
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> =
            if (accessToken == "refreshed") SocialResult.Success(emptyList())
            else SocialResult.Failure(SocialFailure.Unauthorized)
    }
}

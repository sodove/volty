package ru.sodovaya.volty.data.social

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SocialRefreshGenerationIsolationTest {
    @Test
    fun newAccountRefreshDoesNotWaitForRetiredAccountsHeldTransport() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val newStarted = CompletableDeferred<Unit>()
        val refreshTokens = mutableListOf<String>()
        val writes = mutableListOf<String>()
        val transport = object : SocialTransport by UnavailableSocialTransport() {
            override suspend fun login(request: LoginRequest) = SocialResult.Success(
                SessionCredentials(request.email, "refresh-${request.email}", if (request.email == "old") Long.MAX_VALUE else 0),
            )
            override suspend fun getProfile(accessToken: String) = SocialResult.Success(
                SocialSession.Authenticated(SocialUserId(accessToken), accessToken, SessionTokenState.ACTIVE, true),
            )
            override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> =
                SocialResult.Failure(SocialFailure.Unauthorized)

            override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
                refreshTokens += refreshToken
                if (refreshToken == "refresh-old") return withContext(NonCancellable) {
                    oldStarted.complete(Unit)
                    oldRelease.await()
                    SocialResult.Success(SessionCredentials("old-fresh", "old-next", Long.MAX_VALUE)).also {
                        oldReturned.complete(Unit)
                    }
                }
                newStarted.complete(Unit)
                return SocialResult.Success(SessionCredentials("new-fresh", "new-next", Long.MAX_VALUE))
            }
        }
        val store = object : SocialCredentialStore {
            var value: SocialCredentials? = null
            override suspend fun read() = value
            override suspend fun write(credentials: SocialCredentials) {
                writes += credentials.accessToken
                value = credentials
            }
            override suspend fun clear() { value = null }
        }
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        repository.login(LoginRequest("old", "password"))
        val oldAccount = assertNotNull(repository.authenticatedAccount.value)
        val oldRequest = async { repository.listFriends() }
        var newToken: Deferred<String?>? = null
        try {
            runCurrent()
            assertTrue(oldStarted.isCompleted)
            assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("new", "password")))
            val account = assertNotNull(repository.authenticatedAccount.value)
            assertTrue(oldAccount.job.isCancelled)
            assertFalse(oldAccount.job.isCompleted)
            newToken = async { account.bearer() }
            runCurrent()
            assertTrue(newStarted.isCompleted, "New generation must issue refresh while old transport is still held")
            assertFalse(oldReturned.isCompleted)
            assertEquals("new-fresh", newToken.await())
            assertEquals(listOf("refresh-old", "refresh-new"), refreshTokens)
            assertEquals(listOf("old", "new", "new-fresh"), writes)
            assertSame(account, repository.authenticatedAccount.value)
        } finally {
            oldRelease.complete(Unit)
            runCurrent()
            oldRequest.join()
            newToken?.join()
        }
        assertTrue(oldReturned.isCompleted)
        assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), oldRequest.await())
        assertEquals("new-fresh", store.value?.accessToken)
        assertEquals(listOf("old", "new", "new-fresh"), writes)
    }
}

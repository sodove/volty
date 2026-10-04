package ru.sodovaya.volty.data.social

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SocialAuthenticatedAccountTest {
    @Test
    fun logoutCancelsActualAccountBeforeHeldChildOrRemoteLogoutCompletes() = runTest {
        val transport = AccountTransport()
        val repository = DefaultSocialRepository(transport, MemoryStore(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("same", "password"))
        val previous = assertNotNull(repository.authenticatedAccount.value)
        assertEquals(SocialUserId("same"), previous.userId)
        assertEquals("same", previous.bearer())
        val childRelease = CompletableDeferred<Unit>()
        val childEnteredCleanup = CompletableDeferred<Unit>()
        val child = CoroutineScope(backgroundScope.coroutineContext + previous.job).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    childEnteredCleanup.complete(Unit)
                    childRelease.await()
                }
            }
        }
        transport.holdLogout = true
        val logout = async { repository.logout() }
        try {
            runCurrent()
            assertTrue(transport.logoutIssued.isCompleted)
            assertTrue(childEnteredCleanup.isCompleted)
            assertTrue(previous.job.isCancelled)
            assertFalse(previous.job.isCompleted)
            assertEquals(null, previous.bearer())
            assertEquals(null, repository.authenticatedAccount.value)
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertFalse(logout.isCompleted)

            repository.login(LoginRequest("same", "password"))
            val current = assertNotNull(repository.authenticatedAccount.value)
            assertNotSame(previous, current)
            assertNotSame(previous.job, current.job)
            assertTrue(current.job.isActive)
            assertEquals("same", current.bearer())
            assertEquals(null, previous.bearer())
            assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
            transport.logoutRelease.complete(Unit)
            runCurrent()
            assertEquals(SocialResult.Success(Unit), logout.await())
            assertSame(current, repository.authenticatedAccount.value)
        } finally {
            childRelease.complete(Unit)
            transport.logoutRelease.complete(Unit)
            runCurrent()
            child.join()
            logout.cancel()
        }
    }

    @Test
    fun ownedRefreshRetainsLifetimeAndForeignProfileCannotChangePrincipal() = runTest {
        val transport = AccountTransport()
        val repository = DefaultSocialRepository(transport, MemoryStore(), backgroundScope)
        runCurrent()
        repository.login(LoginRequest("same", "password"))
        val account = assertNotNull(repository.authenticatedAccount.value)
        transport.rejectFriendsOnce = true
        assertEquals<SocialResult<List<FriendSummary>>>(SocialResult.Success(emptyList()), repository.listFriends())
        assertEquals(listOf("same", "refreshed"), transport.friendTokens)
        assertEquals(listOf("refresh-same"), transport.refreshTokens)
        assertSame(account, repository.authenticatedAccount.value)
        assertEquals(SocialUserId("same"), account.userId)
        assertEquals("refreshed", account.bearer())

        transport.profileUser = "foreign"
        val result = repository.getProfile()
        assertTrue(result is SocialResult.Failure)
        assertEquals(SocialUserId("same"), (repository.session.value as SocialSession.Authenticated).userId)
        assertSame(account, repository.authenticatedAccount.value)
        assertEquals("refreshed", account.bearer())
        assertEquals(listOf("refresh-same"), transport.refreshTokens, "An actor mismatch is not a 401 refresh trigger")
    }

    private class MemoryStore : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }

    private class AccountTransport : SocialTransport by UnavailableSocialTransport() {
        var profileUser = "same"
        var holdLogout = false
        var rejectFriendsOnce = false
        val logoutIssued = CompletableDeferred<Unit>()
        val logoutRelease = CompletableDeferred<Unit>()
        val friendTokens = mutableListOf<String>()
        val refreshTokens = mutableListOf<String>()
        override suspend fun login(request: LoginRequest) = SocialResult.Success(
            SessionCredentials(request.email, "refresh-${request.email}", Long.MAX_VALUE),
        )
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(
            SocialSession.Authenticated(SocialUserId(profileUser), "Rider", SessionTokenState.ACTIVE, true),
        )
        override suspend fun logout(accessToken: String): SocialResult<Unit> {
            logoutIssued.complete(Unit)
            if (holdLogout) logoutRelease.await()
            return SocialResult.Success(Unit)
        }
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> {
            friendTokens += accessToken
            if (rejectFriendsOnce) {
                rejectFriendsOnce = false
                return SocialResult.Failure(SocialFailure.Unauthorized)
            }
            return SocialResult.Success(emptyList())
        }
        override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
            refreshTokens += refreshToken
            return SocialResult.Success(SessionCredentials("refreshed", "refresh-next", Long.MAX_VALUE))
        }
    }
}

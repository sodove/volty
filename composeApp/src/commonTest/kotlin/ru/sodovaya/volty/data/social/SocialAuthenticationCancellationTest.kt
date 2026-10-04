package ru.sodovaya.volty.data.social

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SocialAuthenticationCancellationTest {
    @Test
    fun cancellingOneRequestCannotDiscardAlreadyRotatedAccountCredentials() = runTest {
        val transport = HeldTransport().apply { holdOld = false }
        val store = Store()
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        repository.login(LoginRequest("old", "password"))
        val account = assertNotNull(repository.authenticatedAccount.value)
        store.holdWrite = true
        val request = async { repository.listFriends() }
        try {
            runCurrent()
            assertTrue(store.writeEntered.isCompleted)
            assertEquals(listOf("refresh-old"), transport.refreshTokens)
            request.cancel()
            store.writeRelease.complete(Unit)
            runCurrent()
            request.join()
            assertTrue(store.writeReturned.isCompleted)
            assertEquals(2, store.writes)
            assertEquals("rotated", store.value?.accessToken)
            assertSame(account, repository.authenticatedAccount.value)
            assertTrue(account.job.isActive)
            assertEquals("rotated", account.bearer())
            assertEquals(listOf("old"), transport.friendTokens, "Cancelled caller cannot issue the retry")
            assertEquals(0, store.clears)
        } finally {
            store.writeRelease.complete(Unit)
            request.cancel()
            runCurrent()
            request.join()
        }
    }

    @Test
    fun cancellationWithdrawsPendingLoginBeforeNoncooperativeProfileCompletes() = runTest {
        val transport = HeldTransport()
        val store = Store()
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        val login = async { repository.login(LoginRequest("old", "password")) }
        try {
            runCurrent()
            assertTrue(transport.oldEntered.isCompleted)
            login.cancel()
            runCurrent()
            assertFalse(login.isCompleted)
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertEquals(null, repository.authenticatedAccount.value)
            assertEquals(null, store.value)
        } finally {
            transport.oldRelease.complete(Unit)
            runCurrent()
            login.join()
        }
        assertTrue(transport.oldReturned.isCompleted)
        assertEquals(SocialSession.LoggedOut, repository.session.value)
        assertEquals(0, store.writes)
    }

    @Test
    fun cancelledOldLoginCleanupCannotRetireReplacementAccount() = runTest {
        val transport = HeldTransport()
        val store = Store()
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        val login = async { repository.login(LoginRequest("old", "password")) }
        try {
            runCurrent()
            assertTrue(transport.oldEntered.isCompleted)
            login.cancel()
            runCurrent()
            assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("new", "password")))
            val replacement = assertNotNull(repository.authenticatedAccount.value)
            transport.oldRelease.complete(Unit)
            runCurrent()
            login.join()
            assertTrue(transport.oldReturned.isCompleted)
            assertSame(replacement, repository.authenticatedAccount.value)
            assertTrue(replacement.job.isActive)
            assertEquals("new", replacement.bearer())
            assertEquals("new", store.value?.accessToken)
        } finally {
            transport.oldRelease.complete(Unit)
            runCurrent()
            login.cancel()
        }
    }

    @Test
    fun cancelledSupersededWriteRollsBackBeforeNewPendingLoginCleanup() = runTest {
        val transport = HeldTransport().apply { holdOld = false; holdNew = true }
        val store = Store().apply { holdWrite = true }
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        val old = async { repository.login(LoginRequest("old", "password")) }
        var next: Deferred<SocialResult<Unit>>? = null
        try {
            runCurrent()
            assertTrue(store.writeEntered.isCompleted)
            next = async { repository.login(LoginRequest("new", "password")) }
            runCurrent()
            assertTrue(transport.newEntered.isCompleted)
            old.cancel()
            runCurrent()
            store.writeRelease.complete(Unit)
            runCurrent()
            assertTrue(store.writeReturned.isCompleted)
            assertEquals(1, store.writes)
            assertFalse(next.isCompleted)
            assertEquals(SocialSession.Authenticating, repository.session.value)
            assertEquals(null, store.value, "New login remains pending, so its cleanup cannot mask the stale write")
            assertEquals(1, store.clears)
            assertEquals(null, repository.authenticatedAccount.value)
        } finally {
            store.writeRelease.complete(Unit)
            transport.oldRelease.complete(Unit)
            transport.newRelease.complete(Unit)
            next?.cancel()
            runCurrent()
            old.join()
            next?.join()
        }
    }

    private class HeldTransport : SocialTransport by UnavailableSocialTransport() {
        var holdOld = true
        var holdNew = false
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        val oldReturned = CompletableDeferred<Unit>()
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val refreshTokens = mutableListOf<String>()
        val friendTokens = mutableListOf<String>()
        override suspend fun login(request: LoginRequest) = SocialResult.Success(
            SessionCredentials(request.email, "refresh-${request.email}", Long.MAX_VALUE),
        )
        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> {
            if (accessToken == "old" && holdOld) return withContext(NonCancellable) {
                oldEntered.complete(Unit)
                oldRelease.await()
                SocialResult.Success(profile(accessToken)).also { oldReturned.complete(Unit) }
            }
            if (accessToken == "new" && holdNew) return withContext(NonCancellable) {
                newEntered.complete(Unit)
                newRelease.await()
                SocialResult.Success(profile(accessToken))
            }
            return SocialResult.Success(profile(accessToken))
        }
        override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
            refreshTokens += refreshToken
            return SocialResult.Success(SessionCredentials("rotated", "refresh-rotated", Long.MAX_VALUE))
        }
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> {
            friendTokens += accessToken
            return if (accessToken == "old") SocialResult.Failure(SocialFailure.Unauthorized)
            else SocialResult.Success(emptyList())
        }
        private fun profile(actor: String) = SocialSession.Authenticated(
            SocialUserId(actor), actor, SessionTokenState.ACTIVE, true,
        )
    }

    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        var writes = 0
        var clears = 0
        var holdWrite = false
        val writeEntered = CompletableDeferred<Unit>()
        val writeRelease = CompletableDeferred<Unit>()
        val writeReturned = CompletableDeferred<Unit>()
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) {
            if (holdWrite) withContext(NonCancellable) {
                writeEntered.complete(Unit)
                writeRelease.await()
                value = credentials
                writes++
                writeReturned.complete(Unit)
            } else { value = credentials; writes++ }
        }
        override suspend fun clear() {
            // A real store may suspend: synchronous clear would conceal caller cancellation.
            yield()
            clears++
            value = null
        }
    }
}

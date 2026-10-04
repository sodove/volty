package ru.sodovaya.volty.data.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.SessionTokenState

class SocialAccountRestorationOwnershipTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun queuedStartupRestoreDoesNotReadOrRepublishAfterAnExplicitLogin() = runTest {
        val store = TrackingCredentialStore(SocialCredentials("saved", "saved-refresh", Long.MAX_VALUE))
        val profileTokens = mutableListOf<String>()
        val transport = object : SocialTransport by UnavailableSocialTransport() {
            override suspend fun login(request: LoginRequest) =
                SocialResult.Success(SessionCredentials("new", "new-refresh", Long.MAX_VALUE))
            override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> {
                profileTokens += accessToken
                return SocialResult.Success(activeProfile)
            }
        }
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("rider@example.com", "password")))
        assertEquals(emptyList(), store.readSnapshots)
        runCurrent()
        assertEquals(emptyList(), store.readSnapshots, "Restore must retain the startup generation captured before dispatch")
        assertEquals(listOf("new"), profileTokens)
        assertEquals(activeProfile, repository.session.value)
        assertEquals(SocialCredentials("new", "new-refresh", Long.MAX_VALUE), store.value)
    }
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun logoutOwnsSessionWhenRestoreProfileReturnsLate() = runTest {
        val saved = SocialCredentials("saved-access", "saved-refresh", Long.MAX_VALUE)
        val store = TrackingCredentialStore(saved)
        val transport = HeldRestoreProfileTransport()
        val repository = DefaultSocialRepository(transport, store, backgroundScope)

        try {
            runCurrent()

            assertTrue(transport.profileStarted.isCompleted)
            assertEquals(listOf("saved-access"), transport.profileTokens)

            assertEquals(SocialResult.Success(Unit), repository.logout())
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertEquals(null, store.value)

            transport.releaseProfile.complete(Unit)
            runCurrent()

            assertTrue(transport.profileReturned.isCompleted)
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertEquals(null, store.value)
            assertEquals(emptyList(), store.writes)
        } finally {
            transport.releaseProfile.complete(Unit)
            runCurrent()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun logoutOwnsSessionWhenRefreshReturnsLateAndDoesNotRetryFriends() = runTest {
        val store = TrackingCredentialStore()
        val transport = HeldRefreshTransport()
        val repository = DefaultSocialRepository(transport, store, backgroundScope)

        runCurrent()
        assertEquals(listOf<SocialCredentials?>(null), store.readSnapshots)

        assertEquals(
            SocialResult.Success(Unit),
            repository.login(LoginRequest("rider@example.com", "correct horse")),
        )
        assertEquals(activeProfile, repository.session.value)

        val friendsResult = async { repository.listFriends() }
        try {
            runCurrent()

            assertTrue(transport.refreshStarted.isCompleted)
            assertEquals(listOf("access"), transport.friendTokens)
            assertEquals(listOf("original-refresh"), transport.refreshTokens)

            assertEquals(SocialResult.Success(Unit), repository.logout())
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertEquals(null, store.value)

            transport.releaseRefresh.complete(Unit)
            runCurrent()

            assertTrue(transport.refreshReturned.isCompleted)
            assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), friendsResult.await())
            assertEquals(listOf("access"), transport.friendTokens)
            assertEquals(listOf("original-refresh"), transport.refreshTokens)
            assertEquals(null, store.value)
            assertEquals(SocialSession.LoggedOut, repository.session.value)
            assertEquals(
                listOf(SocialCredentials("access", "original-refresh", Long.MAX_VALUE)),
                store.writes,
            )
        } finally {
            transport.releaseRefresh.complete(Unit)
            runCurrent()
        }
    }

    private class TrackingCredentialStore(initial: SocialCredentials? = null) : SocialCredentialStore {
        var value: SocialCredentials? = initial
        val writes = mutableListOf<SocialCredentials>()
        val readSnapshots = mutableListOf<SocialCredentials?>()

        override suspend fun read(): SocialCredentials? {
            val snapshot = value
            readSnapshots += snapshot
            return snapshot
        }

        override suspend fun write(credentials: SocialCredentials) {
            writes += credentials
            value = credentials
        }

        override suspend fun clear() {
            value = null
        }
    }

    private class HeldRestoreProfileTransport : SocialTransport by UnavailableSocialTransport() {
        val profileStarted = CompletableDeferred<Unit>()
        val releaseProfile = CompletableDeferred<Unit>()
        val profileReturned = CompletableDeferred<Unit>()
        val profileTokens = mutableListOf<String>()

        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> {
            profileTokens += accessToken
            profileStarted.complete(Unit)
            withContext(NonCancellable) { releaseProfile.await() }
            val result = SocialResult.Success(activeProfile)
            profileReturned.complete(Unit)
            return result
        }

        override suspend fun logout(accessToken: String): SocialResult<Unit> = SocialResult.Success(Unit)
    }

    private class HeldRefreshTransport : SocialTransport by UnavailableSocialTransport() {
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val refreshReturned = CompletableDeferred<Unit>()
        val refreshTokens = mutableListOf<String>()
        val friendTokens = mutableListOf<String>()

        override suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> =
            SocialResult.Success(SessionCredentials("access", "original-refresh", Long.MAX_VALUE))

        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> =
            SocialResult.Success(activeProfile)

        override suspend fun logout(accessToken: String): SocialResult<Unit> = SocialResult.Success(Unit)

        override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
            refreshTokens += refreshToken
            refreshStarted.complete(Unit)
            withContext(NonCancellable) { releaseRefresh.await() }
            val result = SocialResult.Success(SessionCredentials("fresh-access", "fresh-refresh", Long.MAX_VALUE))
            refreshReturned.complete(Unit)
            return result
        }

        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> {
            friendTokens += accessToken
            return SocialResult.Failure(SocialFailure.Unauthorized)
        }
    }

    private companion object {
        val activeProfile = SocialSession.Authenticated(
            userId = SocialUserId("00000000-0000-4000-8000-000000000001"),
            displayName = "Rider",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = true,
        )
    }
}

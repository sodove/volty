package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SocialLiveRefreshOwnershipTest {
    @Test
    fun oldHandshakeRejectionCannotRefreshReplacementAccount() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val rejectionIssued = CompletableDeferred<Unit>()
        val tokens = mutableListOf<String?>()
        val refreshes = mutableListOf<String>()
        val transport = object : SocialTransport by UnavailableSocialTransport() {
            override suspend fun login(request: LoginRequest) = SocialResult.Success(
                SessionCredentials(request.email, "refresh-${request.email}", Long.MAX_VALUE),
            )

            override suspend fun getProfile(accessToken: String) = SocialResult.Success(
                SocialSession.Authenticated(SocialUserId(accessToken), accessToken, SessionTokenState.ACTIVE, true),
            )

            override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
                refreshes += refreshToken
                return SocialResult.Failure(SocialFailure.Forbidden)
            }

            override fun observeGroup(
                groupId: RideGroupId,
                accessTokenProvider: suspend () -> String?,
            ): Flow<SocialLiveEvent> = flow {
                tokens += accessTokenProvider()
                started.complete(Unit)
                release.await()
                rejectionIssued.complete(Unit)
                emit(SocialLiveEvent.Failure(SocialFailure.Unauthorized))
            }
        }
        val store = object : SocialCredentialStore {
            var value: SocialCredentials? = null
            override suspend fun read() = value
            override suspend fun write(credentials: SocialCredentials) { value = credentials }
            override suspend fun clear() { value = null }
        }
        val repository = DefaultSocialRepository(transport, store, backgroundScope)
        runCurrent()
        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("old", "password")))
        val collection = async { repository.observeGroup(RideGroupId("group")).toList() }
        try {
            runCurrent()
            assertTrue(started.isCompleted)
            assertEquals<List<String?>>(listOf("old"), tokens)
            assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("new", "password")))
            release.complete(Unit)
            runCurrent()
            assertTrue(rejectionIssued.isCompleted)
            assertEquals(emptyList(), refreshes)
            assertEquals<List<SocialLiveEvent>>(
                listOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true)),
                collection.await(),
            )
            assertEquals("new", store.value?.accessToken)
            assertEquals(SocialUserId("new"), (repository.session.value as SocialSession.Authenticated).userId)
        } finally {
            release.complete(Unit)
            runCurrent()
            collection.cancel()
        }
    }
}

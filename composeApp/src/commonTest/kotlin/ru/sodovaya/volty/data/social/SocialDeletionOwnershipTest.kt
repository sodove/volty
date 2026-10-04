package ru.sodovaya.volty.data.social

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.sodovaya.volty.domain.social.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SocialDeletionOwnershipTest {
    private class Store : SocialCredentialStore {
        var value: SocialCredentials? = null
        override suspend fun read() = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }
    private fun profile(token: String) = SocialSession.Authenticated(
        SocialUserId(if (token == "old") "10000000-0000-0000-0000-000000000001" else "20000000-0000-0000-0000-000000000002"),
        token, SessionTokenState.ACTIVE,
    )
    private fun transport(delete: suspend (String) -> SocialResult<Unit>) = object : SocialTransport by UnavailableSocialTransport() {
        override suspend fun login(request: LoginRequest) =
            SocialResult.Success(SessionCredentials(request.email.substringBefore('@'), "refresh", Long.MAX_VALUE))
        override suspend fun getProfile(accessToken: String) = SocialResult.Success(profile(accessToken))
        override suspend fun deleteAccount(accessToken: String) = delete(accessToken)
    }

    @Test fun oldAccountDeletionCannotClearTheReplacementAccount() = runTest {
        val release = CompletableDeferred<Unit>()
        val issued = mutableListOf<String>()
        var returned = false
        val store = Store()
        val repository = DefaultSocialRepository(transport { token ->
            issued += token
            withContext(NonCancellable) { release.await() }
            SocialResult.Success(Unit).also { returned = true }
        }, store, backgroundScope)
        runCurrent()
        repository.login(LoginRequest("old@example.com", "password"))
        val deletion = async { repository.deleteAccount() }
        try {
            runCurrent()
            assertEquals(listOf("old"), issued)
            assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("new@example.com", "password")))
            release.complete(Unit); runCurrent()
            assertTrue(returned)
            assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), deletion.await())
            assertEquals(profile("new"), repository.session.value)
            assertEquals("new", store.value?.accessToken)
        } finally { release.complete(Unit); runCurrent() }
    }

    @Test fun deletingTheCurrentAccountStillReturnsSuccessAndClearsLocalCredentials() = runTest {
        val issued = mutableListOf<String>()
        val store = Store()
        val repository = DefaultSocialRepository(transport { token -> issued += token; SocialResult.Success(Unit) }, store, backgroundScope)
        runCurrent()
        repository.login(LoginRequest("old@example.com", "password"))
        assertEquals(SocialResult.Success(Unit), repository.deleteAccount())
        assertEquals(listOf("old"), issued)
        assertEquals(SocialSession.LoggedOut, repository.session.value)
        assertNull(store.value)
    }
}

package ru.sodovaya.volty.data.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.TelemetryShareProfile

@OptIn(ExperimentalCoroutinesApi::class)
class SocialLiveOwnershipTest {
    @Test
    fun flowCreatedForPreviousAccountCannotBorrowCurrentAccountsToken() = runTest {
        val transport = ControlledLiveTransport()
        val repository = DefaultSocialRepository(transport, InMemoryCredentials(), backgroundScope)
        runCurrent()

        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("a")))
        val oldAccountFlow = repository.observeGroup(GROUP_ID)
        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("b")))

        val events = mutableListOf<SocialLiveEvent>()
        val collection = async { oldAccountFlow.collect(events::add) }
        try {
            runCurrent()
            collection.await()

            assertEquals<List<SocialLiveEvent>>(listOf(terminalUnauthorized()), events)
            assertFalse("access-b" in transport.observedLiveTokens)
            assertFalse(transport.producerEvents.any { it is SocialLiveEvent.ShareRevoked })
        } finally {
            transport.releaseBeforeToken.complete(Unit)
            transport.releaseHeldEvent.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun accountChangeWhileUpstreamWaitsBeforeTokenCallbackReturnsNoToken() = runTest {
        val transport = ControlledLiveTransport().apply { pauseBeforeToken = true }
        val repository = DefaultSocialRepository(transport, InMemoryCredentials(), backgroundScope)
        runCurrent()
        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("a")))

        val events = mutableListOf<SocialLiveEvent>()
        val collection = async { repository.observeGroup(GROUP_ID).collect(events::add) }
        try {
            runCurrent()
            assertTrue(transport.observeFlowStarted.isCompleted)
            assertFalse(transport.tokenCallbackResult.isCompleted)

            assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("b")))
            transport.releaseBeforeToken.complete(Unit)
            runCurrent()

            assertTrue(transport.tokenCallbackResult.isCompleted, "The transport must actually invoke the released token callback")
            assertEquals(null, transport.tokenCallbackResult.await())
            assertEquals(listOf<String?>(null), transport.observedLiveTokens)
            assertFalse("access-b" in transport.observedLiveTokens)
            assertEquals<List<SocialLiveEvent>>(listOf(terminalUnauthorized()), events)
            assertTrue(transport.producerEvents.isEmpty(), "A null token must not reach the fake live producer")
            collection.await()
        } finally {
            transport.releaseBeforeToken.complete(Unit)
            transport.releaseHeldEvent.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun staleRevocationCannotClearNewAccountSharingOrEscapeOldSubscription() = runTest {
        val transport = ControlledLiveTransport().apply {
            heldRevocationUserId = SocialUserId("actor-b")
        }
        val repository = DefaultSocialRepository(transport, InMemoryCredentials(), backgroundScope)
        runCurrent()
        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("a")))

        val events = mutableListOf<SocialLiveEvent>()
        val collection = async { repository.observeGroup(GROUP_ID).collect(events::add) }
        try {
            runCurrent()
            assertTrue(transport.observeFlowStarted.isCompleted)
            assertEquals("access-a", transport.tokenCallbackResult.await())
            assertTrue(transport.heldEventWaiting.isCompleted)

            assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("b")))
            val newSharing = SharingSession(NEW_GROUP_ID, TelemetryShareProfile.RIDE, Long.MAX_VALUE)
            transport.nextSharing = newSharing
            assertEquals(
                SocialResult.Success(newSharing),
                repository.startSharing(
                    ShareSessionRequest(
                        groupId = NEW_GROUP_ID,
                        profile = TelemetryShareProfile.RIDE,
                        ttlMillis = 60_000L,
                        startedAtEpochMillis = 1_000L,
                    ),
                ),
            )
            assertEquals(newSharing, repository.activeSharing.value)

            transport.releaseHeldEvent.complete(Unit)
            runCurrent()
            collection.await()

            assertEquals<List<String>>(listOf("access-a"), transport.observedLiveTokens.filterNotNull())
            assertEquals(listOf("access-b"), transport.sharingTokens)
            assertEquals<List<SocialLiveEvent>>(
                listOf(SocialLiveEvent.ShareRevoked(SocialUserId("actor-b"))),
                transport.producerEvents,
            )
            assertEquals<List<SocialLiveEvent>>(listOf(terminalUnauthorized()), events)
            assertEquals(newSharing, repository.activeSharing.value)
        } finally {
            transport.releaseBeforeToken.complete(Unit)
            transport.releaseHeldEvent.complete(Unit)
            runCurrent()
        }
    }

    private class InMemoryCredentials : SocialCredentialStore {
        private var value: SocialCredentials? = null

        override suspend fun read(): SocialCredentials? = value
        override suspend fun write(credentials: SocialCredentials) {
            value = credentials
        }
        override suspend fun clear() {
            value = null
        }
    }

    private class ControlledLiveTransport : SocialTransport by UnavailableSocialTransport() {
        private val credentialsByEmail = mapOf(
            "a@example.test" to SessionCredentials("access-a", "refresh-a", Long.MAX_VALUE),
            "b@example.test" to SessionCredentials("access-b", "refresh-b", Long.MAX_VALUE),
        )

        var pauseBeforeToken = false
        var heldRevocationUserId: SocialUserId? = null
        var nextSharing: SharingSession? = null
        val observeFlowStarted = CompletableDeferred<Unit>()
        val releaseBeforeToken = CompletableDeferred<Unit>()
        val tokenCallbackResult = CompletableDeferred<String?>()
        val heldEventWaiting = CompletableDeferred<Unit>()
        val releaseHeldEvent = CompletableDeferred<Unit>()
        val observedLiveTokens = mutableListOf<String?>()
        val producerEvents = mutableListOf<SocialLiveEvent>()
        val sharingTokens = mutableListOf<String>()

        override suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> =
            SocialResult.Success(credentialsByEmail.getValue(request.email))

        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> =
            SocialResult.Success(profile(if (accessToken == "access-a") "a" else "b"))

        override fun observeGroup(
            groupId: RideGroupId,
            accessTokenProvider: suspend () -> String?,
        ): Flow<SocialLiveEvent> = flow {
            observeFlowStarted.complete(Unit)
            if (pauseBeforeToken) releaseBeforeToken.await()

            val token = accessTokenProvider()
            observedLiveTokens += token
            tokenCallbackResult.complete(token)

            val revokedUser = heldRevocationUserId
            if (revokedUser != null) {
                heldEventWaiting.complete(Unit)
                releaseHeldEvent.await()
                val event = SocialLiveEvent.ShareRevoked(revokedUser)
                producerEvents += event
                emit(event)
            } else if (token != null) {
                val event = SocialLiveEvent.ShareRevoked(SocialUserId("wire-$token"))
                producerEvents += event
                emit(event)
            }
        }

        override suspend fun startSharing(
            accessToken: String,
            request: ShareSessionRequest,
        ): SocialResult<SharingSession> {
            sharingTokens += accessToken
            return SocialResult.Success(
                nextSharing ?: SharingSession(request.groupId, request.profile, Long.MAX_VALUE),
            )
        }
    }

    private companion object {
        val GROUP_ID = RideGroupId("group-old")
        val NEW_GROUP_ID = RideGroupId("group-new")

        fun loginRequest(account: String) = LoginRequest("$account@example.test", "password-$account")

        fun profile(account: String) = SocialSession.Authenticated(
            userId = SocialUserId("actor-$account"),
            displayName = "Actor $account",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = true,
        )

        fun terminalUnauthorized() = SocialLiveEvent.Failure(SocialFailure.Unauthorized, terminal = true)
    }
}

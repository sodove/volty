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
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.TelemetryShareProfile

@OptIn(ExperimentalCoroutinesApi::class)
class SocialAccountPublicationOwnershipTest {
    @Test
    fun delayedProfileFromPreviousAccountCannotReplaceCurrentSession() = runTest {
        val transport = ControlledSocialTransport()
        val credentials = InMemorySocialCredentialStore()
        val repository = DefaultSocialRepository(transport, credentials, backgroundScope)
        runCurrent()

        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("a")))
        transport.holdNextProfile("access-a")

        val oldProfile = async { repository.getProfile() }
        try {
            runCurrent()
            assertTrue(transport.heldProfileStarted.isCompleted)

            assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("b")))

            transport.releaseHeldProfile.complete(Unit)
            runCurrent()
            assertTrue(transport.heldProfileReturned.isCompleted)

            assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), oldProfile.await())
            assertEquals(profile("b"), repository.session.value)
            assertEquals("access-b", credentials.value?.accessToken)
            assertEquals(listOf("access-a"), transport.heldProfileTokens)
        } finally {
            transport.releaseHeldProfile.complete(Unit)
            runCurrent()
        }
    }

    @Test
    fun delayedSharingStartFromPreviousAccountCannotPublishActiveSharing() = runTest {
        val transport = ControlledSocialTransport()
        val credentials = InMemorySocialCredentialStore()
        val repository = DefaultSocialRepository(transport, credentials, backgroundScope)
        runCurrent()

        assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("a")))
        transport.holdNextSharing("access-a")

        val oldStart = async {
            repository.startSharing(
                ShareSessionRequest(
                    groupId = RideGroupId("group-1"),
                    profile = TelemetryShareProfile.RIDE,
                    ttlMillis = 60_000L,
                    startedAtEpochMillis = 1_000L,
                ),
            )
        }
        try {
            runCurrent()
            assertTrue(transport.heldSharingStarted.isCompleted)

            assertEquals(SocialResult.Success(Unit), repository.login(loginRequest("b")))
            assertEquals(null, repository.activeSharing.value)

            transport.releaseHeldSharing.complete(Unit)
            runCurrent()
            assertTrue(transport.heldSharingReturned.isCompleted)

            assertEquals(SocialResult.Failure(SocialFailure.Unauthorized), oldStart.await())
            assertEquals(null, repository.activeSharing.value)
            assertEquals(profile("b"), repository.session.value)
            assertEquals("access-b", credentials.value?.accessToken)
            assertEquals(listOf("access-a"), transport.heldSharingTokens)
        } finally {
            transport.releaseHeldSharing.complete(Unit)
            runCurrent()
        }
    }

    private class InMemorySocialCredentialStore : SocialCredentialStore {
        var value: SocialCredentials? = null

        override suspend fun read(): SocialCredentials? = value
        override suspend fun write(credentials: SocialCredentials) {
            value = credentials
        }
        override suspend fun clear() {
            value = null
        }
    }

    private class ControlledSocialTransport : SocialTransport by UnavailableSocialTransport() {
        private val credentialsByEmail = mapOf(
            "a@example.test" to SessionCredentials("access-a", "refresh-a", Long.MAX_VALUE),
            "b@example.test" to SessionCredentials("access-b", "refresh-b", Long.MAX_VALUE),
        )
        private val profilesByToken = mapOf(
            "access-a" to profile("a"),
            "access-b" to profile("b"),
        )
        private var heldProfileToken: String? = null
        private var heldSharingToken: String? = null

        val heldProfileStarted = CompletableDeferred<Unit>()
        val releaseHeldProfile = CompletableDeferred<Unit>()
        val heldProfileReturned = CompletableDeferred<Unit>()
        val heldProfileTokens = mutableListOf<String>()

        val heldSharingStarted = CompletableDeferred<Unit>()
        val releaseHeldSharing = CompletableDeferred<Unit>()
        val heldSharingReturned = CompletableDeferred<Unit>()
        val heldSharingTokens = mutableListOf<String>()

        override suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> =
            SocialResult.Success(credentialsByEmail.getValue(request.email))

        fun holdNextProfile(accessToken: String) {
            heldProfileToken = accessToken
        }

        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> {
            if (accessToken == heldProfileToken) {
                heldProfileToken = null
                heldProfileTokens += accessToken
                heldProfileStarted.complete(Unit)
                return withContext(NonCancellable) {
                    releaseHeldProfile.await()
                    SocialResult.Success(profilesByToken.getValue(accessToken)).also {
                        heldProfileReturned.complete(Unit)
                    }
                }
            }
            return SocialResult.Success(profilesByToken.getValue(accessToken))
        }

        fun holdNextSharing(accessToken: String) {
            heldSharingToken = accessToken
        }

        override suspend fun startSharing(
            accessToken: String,
            request: ShareSessionRequest,
        ): SocialResult<SharingSession> {
            if (accessToken == heldSharingToken) {
                heldSharingToken = null
                heldSharingTokens += accessToken
                heldSharingStarted.complete(Unit)
                return withContext(NonCancellable) {
                    releaseHeldSharing.await()
                    SocialResult.Success(
                        SharingSession(request.groupId, request.profile, Long.MAX_VALUE),
                    ).also {
                        heldSharingReturned.complete(Unit)
                    }
                }
            }
            return SocialResult.Success(SharingSession(request.groupId, request.profile, Long.MAX_VALUE))
        }
    }

    private companion object {
        fun loginRequest(account: String) = LoginRequest("$account@example.test", "password-$account")

        fun profile(account: String) = SocialSession.Authenticated(
            userId = SocialUserId("actor-$account"),
            displayName = "Actor $account",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = true,
        )
    }
}

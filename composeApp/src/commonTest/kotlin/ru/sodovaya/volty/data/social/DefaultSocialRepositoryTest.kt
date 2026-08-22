package ru.sodovaya.volty.data.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.ProfileUpdate
import ru.sodovaya.volty.domain.social.RegistrationRequest
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionCredentials
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialTransport
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials

class DefaultSocialRepositoryTest {
    @Test
    fun successfulLoginPersistsTokensOnlyAfterProfileIsKnown() = runTest {
        val transport = FakeTransport()
        val credentials = FakeCredentials()
        val repository = DefaultSocialRepository(transport, credentials)

        assertEquals(
            SocialResult.Success(Unit),
            repository.login(LoginRequest("rider@example.com", "correct horse")),
        )
        assertEquals(
            SocialSession.Authenticated(
                userId = ru.sodovaya.volty.domain.social.SocialUserId("u1"),
                displayName = "Rider",
                tokenState = ru.sodovaya.volty.domain.social.SessionTokenState.ACTIVE,
                emailVerified = true,
            ),
            repository.session.value,
        )
        assertEquals(SocialCredentials("access", "refresh", 10_000L), credentials.value)
    }

    @Test
    fun profileFailureClearsTokensAndDoesNotLeaveHalfAuthenticatedSession() = runTest {
        val transport = FakeTransport(profile = SocialResult.Failure(SocialFailure.Unauthorized))
        val credentials = FakeCredentials()
        val repository = DefaultSocialRepository(transport, credentials)

        assertEquals(
            SocialResult.Failure(SocialFailure.Unauthorized),
            repository.login(LoginRequest("rider@example.com", "wrong")),
        )
        assertEquals(SocialSession.LoggedOut, repository.session.value)
        assertNull(credentials.value)
    }

    @Test
    fun logoutRevokesLocalSessionEvenWhenServerLogoutFails() = runTest {
        val transport = FakeTransport(logout = SocialResult.Failure(SocialFailure.Network("offline")))
        val credentials = FakeCredentials(SocialCredentials("access", "refresh", Long.MAX_VALUE))
        val repository = DefaultSocialRepository(transport, credentials)

        assertEquals(SocialResult.Failure(SocialFailure.Network("offline")), repository.logout())
        assertEquals(SocialSession.LoggedOut, repository.session.value)
        assertNull(credentials.value)
    }

    @Test
    fun unauthorizedRequestRefreshesTokenAndRetriesOnce() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("access", "refresh", Long.MAX_VALUE),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
            rejectFirstGroupsRequest = true,
        )
        val credentials = FakeCredentials()
        val repository = DefaultSocialRepository(transport, credentials)

        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("rider@example.com", "correct horse")))
        assertEquals(SocialResult.Success(emptyList()), repository.listGroups())
        assertEquals(listOf("access", "fresh"), transport.groupRequestTokens)
        assertEquals(SocialCredentials("fresh", "fresh-refresh", Long.MAX_VALUE), credentials.value)
    }

    @Test
    fun voiceJoinRefreshesTokenAndRetriesOnce() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("access", "refresh", Long.MAX_VALUE),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
            rejectFirstVoiceJoinRequest = true,
        )
        val credentials = FakeCredentials()
        val repository = DefaultSocialRepository(transport, credentials)

        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("rider@example.com", "correct horse")))
        assertEquals(
            SocialResult.Success(
                VoiceRoomCredentials(
                    provider = "livekit",
                    serverUrl = "wss://voice.example.test",
                    roomId = "room-1",
                    participantToken = "lk-token",
                    expiresAtEpochMillis = 10_000L,
                ),
            ),
            repository.joinVoice(RideGroupId("group-1")),
        )
        assertEquals(listOf("access", "fresh"), transport.voiceJoinTokens)
        assertEquals(SocialCredentials("fresh", "fresh-refresh", Long.MAX_VALUE), credentials.value)
    }

    @Test
    fun voiceLeaveIsIdempotentAfterTheSessionIsGone() = runTest {
        val repository = DefaultSocialRepository(FakeTransport(), FakeCredentials())

        assertEquals(
            SocialResult.Success(Unit),
            repository.leaveVoice(RideGroupId("group-1")),
        )
    }

    private class FakeCredentials(initial: SocialCredentials? = null) : SocialCredentialStore {
        var value: SocialCredentials? = initial
        override suspend fun read(): SocialCredentials? = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }

    private class FakeTransport(
        private val profile: SocialResult<SocialSession.Authenticated> = SocialResult.Success(
            SocialSession.Authenticated(
                userId = ru.sodovaya.volty.domain.social.SocialUserId("u1"),
                displayName = "Rider",
                tokenState = ru.sodovaya.volty.domain.social.SessionTokenState.ACTIVE,
                emailVerified = true,
            )
        ),
        private val logout: SocialResult<Unit> = SocialResult.Success(Unit),
        private val loginCredentials: SessionCredentials =
            SessionCredentials("access", "refresh", expiresAtEpochMillis = 10_000L),
        private val refreshedCredentials: SessionCredentials = loginCredentials,
        private val rejectFirstGroupsRequest: Boolean = false,
        private val rejectFirstVoiceJoinRequest: Boolean = false,
    ) : SocialTransport {
        val groupRequestTokens = mutableListOf<String>()
        val voiceJoinTokens = mutableListOf<String>()

        override suspend fun register(request: RegistrationRequest) = loginResult()
        override suspend fun login(request: LoginRequest) = loginResult()
        override suspend fun refreshSession(refreshToken: String) = SocialResult.Success(refreshedCredentials)
        override suspend fun logout(accessToken: String) = logout
        override suspend fun getProfile(accessToken: String) = profile
        override suspend fun verifyEmail(token: String) = SocialResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String) = SocialResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String) = SocialResult.Success(Unit)
        override suspend fun deleteAccount(accessToken: String) = SocialResult.Success(Unit)
        override suspend fun updateProfile(accessToken: String, request: ProfileUpdate) = profile
        override suspend fun listFriends(accessToken: String): SocialResult<List<FriendSummary>> = SocialResult.Success(emptyList())
        override suspend fun sendFriendRequest(accessToken: String, request: FriendRequest) = SocialResult.Success(Unit)
        override suspend fun respondToFriendRequest(accessToken: String, friendshipId: String, accept: Boolean) = SocialResult.Success(Unit)
        override suspend fun listGroups(accessToken: String): SocialResult<List<RideGroup>> {
            groupRequestTokens += accessToken
            return if (rejectFirstGroupsRequest && groupRequestTokens.size == 1) {
                SocialResult.Failure(SocialFailure.Unauthorized)
            } else {
                SocialResult.Success(emptyList())
            }
        }
        override suspend fun createGroup(accessToken: String, name: String) = SocialResult.Failure(SocialFailure.Network())
        override suspend fun joinGroup(accessToken: String, inviteCode: String) = SocialResult.Failure(SocialFailure.Network())
        override suspend fun leaveGroup(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)
        override suspend fun joinVoice(accessToken: String, groupId: RideGroupId): SocialResult<VoiceRoomCredentials> {
            voiceJoinTokens += accessToken
            return if (rejectFirstVoiceJoinRequest && voiceJoinTokens.size == 1) {
                SocialResult.Failure(SocialFailure.Unauthorized)
            } else {
                SocialResult.Success(
                    VoiceRoomCredentials(
                        provider = "livekit",
                        serverUrl = "wss://voice.example.test",
                        roomId = "room-1",
                        participantToken = "lk-token",
                        expiresAtEpochMillis = 10_000L,
                    ),
                )
            }
        }
        override suspend fun leaveVoice(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)
        override fun observeGroup(accessToken: String, groupId: RideGroupId): Flow<SocialLiveEvent> = emptyFlow()
        override suspend fun startSharing(accessToken: String, request: ShareSessionRequest) = SocialResult.Failure(SocialFailure.Network())
        override suspend fun publishSharingUpdate(accessToken: String, groupId: RideGroupId, update: ru.sodovaya.volty.domain.social.ParticipantShareUpdate) = SocialResult.Success(Unit)
        override suspend fun stopSharing(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)

        private fun loginResult() = SocialResult.Success(loginCredentials)
    }
}

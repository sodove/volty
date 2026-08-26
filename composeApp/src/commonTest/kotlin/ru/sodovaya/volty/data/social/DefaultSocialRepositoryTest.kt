package ru.sodovaya.volty.data.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.social.ParticipantShareUpdate
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus

class DefaultSocialRepositoryTest {
    @Test
    fun subscriptionTerminationDoesNotEscapeTransportCatchOrReconnect() = runTest {
        val transport = CatchingTerminalTransport()
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))

        val events = mutableListOf<SocialLiveEvent>()
        repository.observeGroup(RideGroupId("group-1")).collect { event: SocialLiveEvent -> events.add(event) }

        assertEquals<List<SocialLiveEvent>>(
            listOf(SocialLiveEvent.SubscriptionTerminated("not_member")),
            events,
        )
        assertEquals(1, transport.observeCalls)
        assertEquals(0, transport.refreshCalls)
    }
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
    fun restoreRefreshesWhenStoredAccessTokenIsRejectedEvenIfLocalExpiryLooksValid() = runTest {
        val transport = FakeTransport(
            profile = SocialResult.Failure(SocialFailure.Unauthorized),
            refreshedProfile = SocialResult.Success(authenticatedProfile()),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
        )
        val credentials = FakeCredentials(SocialCredentials("stale", "refresh", Long.MAX_VALUE))
        val repository = DefaultSocialRepository(transport, credentials)

        withTimeout(2_000L) {
            while (repository.session.value !is SocialSession.Authenticated) delay(10L)
        }

        assertEquals(SocialCredentials("fresh", "fresh-refresh", Long.MAX_VALUE), credentials.value)
        assertEquals(listOf("stale", "fresh"), transport.profileTokens)
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

    @Test
    fun liveReconnectRequestsTheCurrentRefreshedTokenInsteadOfReusingLoginToken() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("stale", "refresh", 0L),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
        )
        val repository = DefaultSocialRepository(transport, FakeCredentials())

        assertEquals(SocialResult.Success(Unit), repository.login(LoginRequest("rider@example.com", "correct horse")))
        repository.observeGroup(RideGroupId("group-1")).first()

        assertEquals(listOf("fresh"), transport.liveGroupTokens)
    }

    @Test
    fun liveAuthFailureRefreshesOnceBeforeReconnecting() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("access", "refresh", Long.MAX_VALUE),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
            liveEvents = listOf(
                listOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized)),
                listOf(SocialLiveEvent.Snapshot(LiveGroupSnapshot(
                    groupId = RideGroupId("group-1"),
                    capturedAtEpochMillis = 1L,
                    participants = listOf(ParticipantSnapshot(
                        userId = ru.sodovaya.volty.domain.social.SocialUserId("u1"),
                        displayName = "Rider",
                        presence = PresenceStatus.ONLINE,
                        location = null,
                        telemetry = null,
                        lastSeenAtEpochMillis = 1L,
                    )),
                )))
            ),
        )
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))

        val event = repository.observeGroup(RideGroupId("group-1")).first()

        assertTrue(event is SocialLiveEvent.Snapshot)
        assertEquals(listOf("access", "fresh"), transport.liveGroupTokens)
        assertEquals(1, transport.refreshCalls)
    }

    @Test
    fun terminalLiveMembershipFailureDoesNotRefreshOrReconnect() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("access", "refresh", Long.MAX_VALUE),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
            liveEvents = listOf(listOf(SocialLiveEvent.Failure(SocialFailure.Forbidden))),
        )
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))

        val event = repository.observeGroup(RideGroupId("group-1")).first()

        assertEquals(SocialLiveEvent.Failure(SocialFailure.Forbidden), event)
        assertEquals(listOf("access"), transport.liveGroupTokens)
        assertEquals(0, transport.refreshCalls)
    }

    @Test
    fun repeatedLiveAuthFailuresDoNotRefreshMoreThanOnce() = runTest {
        val transport = FakeTransport(
            loginCredentials = SessionCredentials("access", "refresh", Long.MAX_VALUE),
            refreshedCredentials = SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE),
            liveEvents = listOf(
                listOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized)),
                listOf(SocialLiveEvent.Failure(SocialFailure.Unauthorized)),
            ),
        )
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))

        val event = repository.observeGroup(RideGroupId("group-1")).first()

        assertEquals(SocialLiveEvent.Failure(SocialFailure.Unauthorized), event)
        assertEquals(1, transport.refreshCalls)
        assertEquals(listOf("access", "fresh"), transport.liveGroupTokens)
    }

    @Test
    fun locationSharingAcceptsUpdateWithoutTelemetryAndRenewReplacesActiveWindow() = runTest {
        val initial = SharingSession(RideGroupId("group-1"), TelemetryShareProfile.LOCATION, Long.MAX_VALUE - 1L)
        val renewed = initial.copy(expiresAtEpochMillis = Long.MAX_VALUE)
        val transport = FakeTransport(
            startSharingResult = SocialResult.Success(initial),
            renewedSharingResult = SocialResult.Success(renewed),
        )
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))

        assertEquals(
            SocialResult.Success(initial),
            repository.startSharing(ShareSessionRequest(RideGroupId("group-1"), TelemetryShareProfile.LOCATION, 60_000L, 1_000L)),
        )
        assertEquals(
            SocialResult.Success(Unit),
            repository.publishSharingUpdate(
                RideGroupId("group-1"),
                ParticipantShareUpdate(
                    capturedAtEpochMillis = 2_000L,
                    location = LocationSnapshot(56.8, 60.6, 5.0, 2_000L, Long.MAX_VALUE),
                    telemetry = null,
                ),
            ),
        )
        assertEquals(
            SocialResult.Success(renewed),
            repository.renewSharing(ShareSessionRequest(RideGroupId("group-1"), TelemetryShareProfile.LOCATION, 60_000L, 2_000L)),
        )
        assertEquals(renewed, repository.activeSharing.value)
    }

    @Test
    fun successfulLeaveClearsActiveSharingForThatGroup() = runTest {
        val active = SharingSession(RideGroupId("group-1"), TelemetryShareProfile.RIDE, Long.MAX_VALUE)
        val transport = FakeTransport(startSharingResult = SocialResult.Success(active))
        val repository = DefaultSocialRepository(transport, FakeCredentials())
        repository.login(LoginRequest("rider@example.com", "correct horse"))
        repository.startSharing(
            ShareSessionRequest(
                groupId = active.groupId,
                profile = active.profile,
                ttlMillis = 60_000L,
                startedAtEpochMillis = 1_000L,
            ),
        )

        assertEquals(SocialResult.Success(Unit), repository.leaveGroup(active.groupId))
        assertNull(repository.activeSharing.value)
    }

    private class FakeCredentials(initial: SocialCredentials? = null) : SocialCredentialStore {
        var value: SocialCredentials? = initial
        override suspend fun read(): SocialCredentials? = value
        override suspend fun write(credentials: SocialCredentials) { value = credentials }
        override suspend fun clear() { value = null }
    }

    private class CatchingTerminalTransport : FakeTransport() {
        var observeCalls = 0

        override suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> =
            SocialResult.Success(SessionCredentials("access", "refresh", Long.MAX_VALUE))

        override suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> {
            refreshCalls++
            return SocialResult.Success(SessionCredentials("fresh", "fresh-refresh", Long.MAX_VALUE))
        }

        override fun observeGroup(
            groupId: RideGroupId,
            accessTokenProvider: suspend () -> String?,
        ): Flow<SocialLiveEvent> = flow {
            observeCalls++
            try {
                emit(SocialLiveEvent.SubscriptionTerminated("not_member"))
            } catch (_: Exception) {
                // Models a transport boundary that catches generic exceptions.
            }
        }
    }

    private open class FakeTransport(
        private val profile: SocialResult<SocialSession.Authenticated> = SocialResult.Success(
            SocialSession.Authenticated(
                userId = ru.sodovaya.volty.domain.social.SocialUserId("u1"),
                displayName = "Rider",
                tokenState = ru.sodovaya.volty.domain.social.SessionTokenState.ACTIVE,
                emailVerified = true,
            )
        ),
        private val logout: SocialResult<Unit> = SocialResult.Success(Unit),
        private val refreshedProfile: SocialResult<SocialSession.Authenticated> = profile,
        private val loginCredentials: SessionCredentials =
            SessionCredentials("access", "refresh", expiresAtEpochMillis = 10_000L),
        private val refreshedCredentials: SessionCredentials = loginCredentials,
        private val rejectFirstGroupsRequest: Boolean = false,
        private val rejectFirstVoiceJoinRequest: Boolean = false,
        private val startSharingResult: SocialResult<SharingSession> = SocialResult.Failure(SocialFailure.Network()),
        private val renewedSharingResult: SocialResult<SharingSession> = startSharingResult,
        private val liveEvents: List<List<SocialLiveEvent>> = listOf(listOf(SocialLiveEvent.Failure(SocialFailure.Network("reconnect")))),
    ) : SocialTransport {
        val groupRequestTokens = mutableListOf<String>()
        val voiceJoinTokens = mutableListOf<String>()
        val liveGroupTokens = mutableListOf<String>()
        val profileTokens = mutableListOf<String>()
        var refreshCalls = 0
        private var liveAttempt = 0

        override suspend fun register(request: RegistrationRequest) = loginResult()
        override open suspend fun login(request: LoginRequest): SocialResult<SessionCredentials> = loginResult()
        override open suspend fun refreshSession(refreshToken: String): SocialResult<SessionCredentials> =
            SocialResult.Success(refreshedCredentials).also { refreshCalls++ }
        override suspend fun logout(accessToken: String) = logout
        override suspend fun getProfile(accessToken: String): SocialResult<SocialSession.Authenticated> {
            profileTokens += accessToken
            return if (accessToken == "fresh") refreshedProfile else profile
        }
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
        override suspend fun deleteGroup(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)
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
        override open fun observeGroup(
            groupId: RideGroupId,
            accessTokenProvider: suspend () -> String?,
        ): Flow<SocialLiveEvent> = flow {
            liveGroupTokens += requireNotNull(accessTokenProvider())
            val events = liveEvents.getOrElse(liveAttempt++) { liveEvents.last() }
            for (event in events) emit(event)
        }
        override suspend fun startSharing(accessToken: String, request: ShareSessionRequest) = startSharingResult
        override suspend fun renewSharing(accessToken: String, request: ShareSessionRequest) = renewedSharingResult
        override suspend fun publishSharingUpdate(accessToken: String, groupId: RideGroupId, update: ParticipantShareUpdate) = SocialResult.Success(Unit)
        override suspend fun stopSharing(accessToken: String, groupId: RideGroupId) = SocialResult.Success(Unit)

        private fun loginResult() = SocialResult.Success(loginCredentials)
    }

    private fun authenticatedProfile() = SocialSession.Authenticated(
        userId = ru.sodovaya.volty.domain.social.SocialUserId("u1"),
        displayName = "Rider",
        tokenState = ru.sodovaya.volty.domain.social.SessionTokenState.ACTIVE,
        emailVerified = true,
    )
}

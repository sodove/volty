package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.ParticipantShareUpdate
import ru.sodovaya.volty.domain.social.ProfileUpdate
import ru.sodovaya.volty.domain.social.RegistrationRequest
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.VoiceParticipant
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials
import ru.sodovaya.volty.domain.social.VoiceRoomEngine
import ru.sodovaya.volty.domain.social.VoiceRoomFailureReason
import ru.sodovaya.volty.domain.social.VoiceRoomState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveKitVoiceRoomRepositoryTest {
    @Test
    fun joinConnectsLiveKitWithOpenMicAndMirrorsParticipants() = runTest {
        val engine = FakeVoiceRoomEngine()
        val social = FakeSocialRepository()
        val repository = LiveKitVoiceRoomRepository(
            socialRepository = social,
            engine = engine,
            scope = voiceScope(),
        )

        val result = repository.join(RideGroupId("group-1"))
        advanceUntilIdle()

        assertEquals(SocialResult.Success(Unit), result)
        assertEquals(listOf("wss://voice.example.test" to "lk-token"), engine.connectCalls)
        assertEquals(VoiceRoomState.Joined(muted = false, participants = emptyList()), repository.state.value)

        engine.participants.value = listOf(
            VoiceParticipant(
                userId = SocialUserId("u1"),
                displayName = "Rider",
                isSpeaking = true,
            ),
        )
        advanceUntilIdle()

        assertEquals(
            VoiceRoomState.Joined(
                muted = false,
                participants = listOf(
                    VoiceParticipant(
                        userId = SocialUserId("u1"),
                        displayName = "Rider",
                        isSpeaking = true,
                    ),
                ),
            ),
            repository.state.value,
        )
    }

    @Test
    fun setMutedUpdatesJoinedStateAndDelegatesToEngine() = runTest {
        val engine = FakeVoiceRoomEngine()
        val repository = LiveKitVoiceRoomRepository(
            socialRepository = FakeSocialRepository(),
            engine = engine,
            scope = voiceScope(),
        )

        repository.join(RideGroupId("group-1"))
        advanceUntilIdle()

        val result = repository.setMuted(true)
        advanceUntilIdle()

        assertEquals(SocialResult.Success(Unit), result)
        assertEquals(listOf(true), engine.mutedCalls)
        assertEquals(VoiceRoomState.Joined(muted = true, participants = emptyList()), repository.state.value)
    }

    @Test
    fun leaveDisconnectsEngineAndCleansUpOnlyOnce() = runTest {
        val engine = FakeVoiceRoomEngine()
        val social = FakeSocialRepository()
        val repository = LiveKitVoiceRoomRepository(
            socialRepository = social,
            engine = engine,
            scope = voiceScope(),
        )

        repository.join(RideGroupId("group-1"))
        advanceUntilIdle()

        assertEquals(SocialResult.Success(Unit), repository.leave())
        assertEquals(SocialResult.Success(Unit), repository.leave())
        advanceUntilIdle()

        assertEquals(1, engine.disconnectCalls)
        assertEquals(listOf(RideGroupId("group-1")), social.leaveVoiceCalls)
        assertEquals(VoiceRoomState.Available, repository.state.value)
    }

    @Test
    fun unsupportedProviderFailsWithoutConnectingTheEngine() = runTest {
        val engine = FakeVoiceRoomEngine()
        val social = FakeSocialRepository(
            joinVoiceResult = SocialResult.Success(
                VoiceRoomCredentials(
                    provider = "somebody-else",
                    serverUrl = "wss://voice.example.test",
                    roomId = "room-1",
                    participantToken = "lk-token",
                    expiresAtEpochMillis = 10_000L,
                ),
            ),
        )
        val repository = LiveKitVoiceRoomRepository(
            socialRepository = social,
            engine = engine,
            scope = voiceScope(),
        )

        val result = repository.join(RideGroupId("group-1"))
        advanceUntilIdle()

        assertTrue(result is SocialResult.Failure)
        assertEquals(emptyList(), engine.connectCalls)
        assertEquals(VoiceRoomState.Failed(VoiceRoomFailureReason.CONNECTION_FAILED), repository.state.value)
    }

    @Test
    fun engineFailureLeavesTheRoomAndSurfacesConnectionFailure() = runTest {
        val engine = FakeVoiceRoomEngine(connectResult = Result.failure(IllegalStateException("boom")))
        val social = FakeSocialRepository()
        val repository = LiveKitVoiceRoomRepository(
            socialRepository = social,
            engine = engine,
            scope = voiceScope(),
        )

        val result = repository.join(RideGroupId("group-1"))
        advanceUntilIdle()

        assertTrue(result is SocialResult.Failure)
        assertEquals(listOf(RideGroupId("group-1")), social.leaveVoiceCalls)
        assertEquals(VoiceRoomState.Failed(VoiceRoomFailureReason.CONNECTION_FAILED), repository.state.value)
    }
}

private fun TestScope.voiceScope(): CoroutineScope =
    CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())

private class FakeVoiceRoomEngine(
    override val requiredPermissions: List<String> = emptyList(),
    private val connectResult: Result<Unit> = Result.success(Unit),
    private val setMutedResult: Result<Unit> = Result.success(Unit),
) : VoiceRoomEngine {
    override val participants = MutableStateFlow<List<VoiceParticipant>>(emptyList())
    val connectCalls = mutableListOf<Pair<String, String>>()
    val mutedCalls = mutableListOf<Boolean>()
    var disconnectCalls = 0

    override suspend fun connect(serverUrl: String, participantToken: String): Result<Unit> {
        connectCalls += serverUrl to participantToken
        return connectResult
    }

    override suspend fun disconnect() {
        disconnectCalls += 1
    }

    override suspend fun setMuted(muted: Boolean): Result<Unit> {
        mutedCalls += muted
        return setMutedResult
    }
}

private class FakeSocialRepository(
    private val joinVoiceResult: SocialResult<VoiceRoomCredentials> = SocialResult.Success(
        VoiceRoomCredentials(
            provider = "livekit",
            serverUrl = "wss://voice.example.test",
            roomId = "room-1",
            participantToken = "lk-token",
            expiresAtEpochMillis = 10_000L,
        ),
    ),
) : SocialRepository {
    override val session = MutableStateFlow<SocialSession>(
        SocialSession.Authenticated(
            userId = SocialUserId("u1"),
            displayName = "Rider",
            tokenState = SessionTokenState.ACTIVE,
            emailVerified = true,
        ),
    )
    override val activeSharing = MutableStateFlow<SharingSession?>(null)
    val joinVoiceCalls = mutableListOf<RideGroupId>()
    val leaveVoiceCalls = mutableListOf<RideGroupId>()

    override suspend fun joinVoice(groupId: RideGroupId): SocialResult<VoiceRoomCredentials> {
        joinVoiceCalls += groupId
        return joinVoiceResult
    }

    override suspend fun leaveVoice(groupId: RideGroupId): SocialResult<Unit> {
        leaveVoiceCalls += groupId
        return SocialResult.Success(Unit)
    }

    override suspend fun register(request: RegistrationRequest): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun login(request: LoginRequest): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun logout(): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun verifyEmail(token: String): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun requestPasswordReset(email: String): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun deleteAccount(): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun getProfile(): SocialResult<SocialSession.Authenticated> = unexpectedSocialCall()
    override suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated> = unexpectedSocialCall()
    override suspend fun listFriends(): SocialResult<List<FriendSummary>> = SocialResult.Success(emptyList())
    override suspend fun sendFriendRequest(request: FriendRequest): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun listGroups(): SocialResult<List<RideGroup>> = SocialResult.Success(emptyList())
    override suspend fun createGroup(name: String): SocialResult<RideGroup> = unexpectedSocialCall()
    override suspend fun joinGroup(inviteCode: String): SocialResult<RideGroup> = unexpectedSocialCall()
    override suspend fun leaveGroup(groupId: RideGroupId): SocialResult<Unit> = unexpectedSocialCall()
    override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> = emptyFlow()
    override suspend fun startSharing(request: ShareSessionRequest): SocialResult<SharingSession> = unexpectedSocialCall()
    override suspend fun publishSharingUpdate(
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit> = unexpectedSocialCall()
    override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> = unexpectedSocialCall()
}

private fun <T> unexpectedSocialCall(): SocialResult<T> = throw AssertionError("Unexpected fake call")

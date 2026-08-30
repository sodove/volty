package ru.sodovaya.volty.domain.social

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import ru.sodovaya.volty.domain.location.LocationConsumer
import ru.sodovaya.volty.domain.location.LocationDemandPolicy
import ru.sodovaya.volty.domain.location.RideLocationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocationOnlySharingCoordinatorTest {
    @Test
    fun start_and_stop_location_only_sharing_issue_only_social_demand() {
        val started = LocationDemandPolicy.setDemand(
            LocationDemandPolicy.initialState,
            LocationConsumer.SOCIAL_SHARING,
            enabled = true,
        )
        val stopped = LocationDemandPolicy.setDemand(
            started.state,
            LocationConsumer.SOCIAL_SHARING,
            enabled = false,
        )

        assertEquals(setOf(LocationConsumer.SOCIAL_SHARING), started.state.demands)
        assertEquals(RideLocationStatus.NotRequested, stopped.state.status)
        assertEquals(emptySet(), stopped.state.demands)
    }

    @Test
    fun locationOnlyPublishDoesNotReadTelemetrySource() = runTest {
        val repository = RecordingRepository()
        val source = object : SocialTelemetrySource {
            override val latest = MutableStateFlow<EarnedTelemetry?>(null)
        }
        val location = LocationSnapshot(56.8, 60.6, 5.0, 2_000L, 10_000L)

        val result = SocialShareSessionCoordinator(repository, source).publish(
            groupId = RideGroupId("group-1"),
            profile = TelemetryShareProfile.LOCATION,
            location = location,
        )

        assertEquals(SocialResult.Success(Unit), result)
        assertEquals(location, repository.lastUpdate?.location)
        assertNull(repository.lastUpdate?.telemetry)
    }

    @Test
    fun telemetryProfileStillPublishesLocationBeforeTelemetryIsReady() = runTest {
        val repository = RecordingRepository()
        val source = object : SocialTelemetrySource {
            override val latest = MutableStateFlow<EarnedTelemetry?>(null)
        }
        val location = LocationSnapshot(56.8, 60.6, 5.0, 2_000L, 10_000L)

        val result = SocialShareSessionCoordinator(repository, source).publish(
            groupId = RideGroupId("group-1"),
            profile = TelemetryShareProfile.FULL,
            location = location,
        )

        assertEquals(SocialResult.Success(Unit), result)
        assertEquals(location, repository.lastUpdate?.location)
        assertNotNull(repository.lastUpdate?.telemetry)
        assertEquals(TelemetryShareProfile.FULL, repository.lastUpdate?.telemetry?.profile)
    }

    private class RecordingRepository : SocialRepository {
        override val session = MutableStateFlow<SocialSession>(
            SocialSession.Authenticated(
                userId = SocialUserId("user-1"),
                displayName = "Rider",
                tokenState = SessionTokenState.ACTIVE,
            ),
        )
        override val activeSharing = MutableStateFlow<SharingSession?>(null)
        var lastUpdate: ParticipantShareUpdate? = null

        override suspend fun register(request: RegistrationRequest): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun login(request: LoginRequest): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun logout(): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun verifyEmail(token: String): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun requestPasswordReset(email: String): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun deleteAccount(): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun getProfile(): SocialResult<SocialSession.Authenticated> = SocialResult.Success(session.value as SocialSession.Authenticated)
        override suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated> = SocialResult.Success(session.value as SocialSession.Authenticated)
        override suspend fun listFriends(): SocialResult<List<FriendSummary>> = SocialResult.Success(emptyList())
        override suspend fun sendFriendRequest(request: FriendRequest): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun listGroups(): SocialResult<List<RideGroup>> = SocialResult.Success(emptyList())
        override suspend fun createGroup(name: String): SocialResult<RideGroup> = SocialResult.Failure(SocialFailure.Network())
        override suspend fun joinGroup(inviteCode: String): SocialResult<RideGroup> = SocialResult.Failure(SocialFailure.Network())
        override suspend fun leaveGroup(groupId: RideGroupId): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun deleteGroup(groupId: RideGroupId): SocialResult<Unit> = SocialResult.Success(Unit)
        override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> = emptyFlow()
        override suspend fun startSharing(request: ShareSessionRequest): SocialResult<SharingSession> = SocialResult.Failure(SocialFailure.Network())
        override suspend fun publishSharingUpdate(groupId: RideGroupId, update: ParticipantShareUpdate): SocialResult<Unit> {
            lastUpdate = update
            return SocialResult.Success(Unit)
        }
        override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> = SocialResult.Success(Unit)
        override suspend fun joinVoice(groupId: RideGroupId): SocialResult<VoiceRoomCredentials> = SocialResult.Failure(SocialFailure.Network())
        override suspend fun leaveVoice(groupId: RideGroupId): SocialResult<Unit> = SocialResult.Success(Unit)
    }
}

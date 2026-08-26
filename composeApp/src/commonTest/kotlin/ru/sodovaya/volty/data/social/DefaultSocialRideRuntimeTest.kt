package ru.sodovaya.volty.data.social

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.social.EarnedTelemetry
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.LoginRequest
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.ParticipantShareUpdate
import ru.sodovaya.volty.domain.social.ProfileUpdate
import ru.sodovaya.volty.domain.social.RegistrationRequest
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.ShareSessionRequest
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialRepository
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialShareSessionCoordinator
import ru.sodovaya.volty.domain.social.SocialTelemetrySource
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomCredentials
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.VoiceProviderAvailability

class DefaultSocialRideRuntimeTest {
    @Test
    fun selectingTheSameGroupIsIdempotentAndRetainsLiveState() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val runtime = runtime(repository)
        val group = testGroup()

        runtime.selectGroup(group)
        testScheduler.advanceUntilIdle()
        repository.events.tryEmit(snapshotEvent())
        testScheduler.advanceUntilIdle()
        runtime.selectGroup(group)
        testScheduler.advanceUntilIdle()

        assertEquals(group, runtime.state.value.selectedGroup)
        assertEquals(1, repository.observeCalls)
        assertTrue(runtime.state.value.liveEvent is SocialLiveEvent.Snapshot)
        runtime.close()
    }

    @Test
    fun networkFailureKeepsTheLastRosterVisible() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val runtime = runtime(repository)
        runtime.selectGroup(testGroup())
        testScheduler.advanceUntilIdle()

        repository.events.tryEmit(snapshotEvent())
        testScheduler.advanceUntilIdle()
        repository.events.tryEmit(SocialLiveEvent.Failure(SocialFailure.Network("offline")))
        testScheduler.advanceUntilIdle()

        val event = runtime.state.value.liveEvent as SocialLiveEvent.Snapshot
        assertEquals(1, event.value.participants.size)
        assertEquals(1, runtime.state.value.markers.size)
        assertFalse(runtime.state.value.markers.single().stale)
        runtime.close()
    }

    @Test
    fun freshnessIsReevaluatedFromTheInjectedClockWithoutAnotherEvent() = runTest {
        val repository = FakeRuntimeSocialRepository()
        var now = 1_000L
        val runtime = runtime(repository, nowEpochMillis = { now })
        runtime.selectGroup(testGroup())
        testScheduler.advanceUntilIdle()
        repository.events.tryEmit(snapshotEvent())
        testScheduler.advanceUntilIdle()

        now = 20_000L
        runtime.refreshLiveProjection()

        assertTrue(runtime.state.value.markers.single().stale)
        runtime.close()
    }

    @Test
    fun routeLifecycleHooksDoNotStopRuntime() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val runtime = runtime(repository)
        val group = testGroup()
        runtime.selectGroup(group)

        runtime.onBack()
        runtime.onNavigationChanged()

        assertEquals(group, runtime.state.value.selectedGroup)
        assertEquals(0, repository.stopSharingCalls)
        assertEquals(0, repository.leaveVoiceCalls)
        runtime.close()
    }

    @Test
    fun explicitLogoutCleanupStopsLocationSharingVoiceAndClearsState() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val voice = FakeRuntimeVoiceRepository()
        val location = FakeRuntimeLocationProvider()
        val runtime = runtime(repository, voice, location)
        val group = testGroup()
        runtime.selectGroup(group)
        testScheduler.advanceUntilIdle()
        runtime.store.setSharing(SharingSession(group.id, TelemetryShareProfile.LOCATION, 10_000L))
        runtime.store.setVoice(VoiceRoomState.Joined())

        runtime.logoutCleanup()

        assertEquals(null, runtime.state.value.selectedGroup)
        assertEquals(null, runtime.state.value.sharing)
        assertEquals(VoiceRoomState.Available, runtime.state.value.voice)
        assertTrue(location.stopCalls > 0)
        assertTrue(voice.leaveCalls > 0)
        assertTrue(repository.stopSharingCalls > 0)
        runtime.close()
    }

    @Test
    fun firstLocationEmittedWhenProviderStartsIsPublished() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val location = FakeRuntimeLocationProvider()
        location.onStart = { location.locationUpdates.tryEmit(testLocation()) }
        val runtime = runtime(repository, location = location)
        runtime.selectGroup(testGroup())
        testScheduler.advanceUntilIdle()

        val result = runtime.startSharing(60_000L)
        testScheduler.advanceUntilIdle()

        assertTrue(result is SocialResult.Success)
        assertEquals(1, repository.publishedUpdates)
        runtime.close()
    }

    @Test
    fun an_old_selection_does_not_attach_after_a_newer_selection() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val runtime = runtime(repository)
        val first = testGroup().copy(id = RideGroupId("first"))
        val second = testGroup().copy(id = RideGroupId("second"))
        val third = testGroup().copy(id = RideGroupId("third"))
        val stopGate = CompletableDeferred<Unit>()
        repository.stopSharingGate = stopGate
        runtime.store.selectGroup(first)
        runtime.store.setSharing(SharingSession(first.id, TelemetryShareProfile.LOCATION, 10_000L))

        runtime.selectGroup(second)
        runtime.selectGroup(third)
        testScheduler.runCurrent()
        stopGate.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(third.id), repository.observedGroupIds)
        runtime.close()
    }

    @Test
    fun terminal_subscription_event_clears_selected_group_and_live_state() = runTest {
        val repository = FakeRuntimeSocialRepository()
        val runtime = runtime(repository)
        runtime.selectGroup(testGroup())
        testScheduler.advanceUntilIdle()
        repository.events.tryEmit(snapshotEvent())
        testScheduler.advanceUntilIdle()

        repository.events.tryEmit(SocialLiveEvent.Failure(SocialFailure.Forbidden, terminal = true))
        testScheduler.advanceUntilIdle()

        assertEquals(null, runtime.state.value.selectedGroup)
        assertEquals(null, runtime.state.value.liveEvent)
        assertEquals(emptyList(), runtime.state.value.markers)
        runtime.close()
    }

    private fun runtime(
        repository: FakeRuntimeSocialRepository,
        voice: FakeRuntimeVoiceRepository = FakeRuntimeVoiceRepository(),
        location: FakeRuntimeLocationProvider = FakeRuntimeLocationProvider(),
        nowEpochMillis: () -> Long = { 1_000L },
    ): DefaultSocialRideRuntime {
        return DefaultSocialRideRuntime(
            socialRepository = repository,
            voiceRepository = voice,
            locationProvider = location,
            sharingCoordinator = SocialShareSessionCoordinator(repository, FakeRuntimeTelemetrySource()),
            scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined + Job()),
            nowEpochMillis = nowEpochMillis,
            freshnessTickerIntervalMillis = null,
        )
    }

    private fun snapshotEvent() = SocialLiveEvent.Snapshot(
        LiveGroupSnapshot(
            groupId = testGroup().id,
            capturedAtEpochMillis = 1_000L,
            participants = listOf(
                ParticipantSnapshot(
                    userId = SocialUserId("member"),
                    displayName = "Member",
                    presence = PresenceStatus.ONLINE,
                    location = testLocation(),
                    telemetry = null,
                    lastSeenAtEpochMillis = 1_000L,
                ),
            ),
        ),
    )
}

private class FakeRuntimeSocialRepository : SocialRepository {
    override val session = MutableStateFlow<SocialSession>(authenticated())
    override val activeSharing = MutableStateFlow<SharingSession?>(null)
    val events = MutableStateFlow<SocialLiveEvent>(SocialLiveEvent.Failure(SocialFailure.Network("initial")))
    var observeCalls = 0
    var stopSharingCalls = 0
    var leaveVoiceCalls = 0
    var publishedUpdates = 0
    val observedGroupIds = mutableListOf<RideGroupId>()
    var stopSharingGate: CompletableDeferred<Unit>? = null

    override suspend fun register(request: RegistrationRequest) = unexpected<Unit>()
    override suspend fun login(request: LoginRequest) = unexpected<Unit>()
    override suspend fun logout() = SocialResult.Success(Unit)
    override suspend fun verifyEmail(token: String) = unexpected<Unit>()
    override suspend fun requestPasswordReset(email: String) = unexpected<Unit>()
    override suspend fun resetPassword(token: String, newPassword: String) = unexpected<Unit>()
    override suspend fun deleteAccount() = unexpected<Unit>()
    override suspend fun getProfile() = unexpected<SocialSession.Authenticated>()
    override suspend fun updateProfile(request: ProfileUpdate) = unexpected<SocialSession.Authenticated>()
    override suspend fun listFriends() = SocialResult.Success(emptyList<FriendSummary>())
    override suspend fun sendFriendRequest(request: FriendRequest) = unexpected<Unit>()
    override suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean) = unexpected<Unit>()
    override suspend fun listGroups() = SocialResult.Success(emptyList<RideGroup>())
    override suspend fun createGroup(name: String) = unexpected<RideGroup>()
    override suspend fun joinGroup(inviteCode: String) = unexpected<RideGroup>()
    override suspend fun leaveGroup(groupId: RideGroupId) = unexpected<Unit>()
    override suspend fun deleteGroup(groupId: RideGroupId) = unexpected<Unit>()
    override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> {
        observeCalls++
        observedGroupIds += groupId
        return events
    }
    override suspend fun startSharing(request: ShareSessionRequest) = SocialResult.Success(
        SharingSession(request.groupId, request.profile, request.startedAtEpochMillis + request.ttlMillis),
    )
    override suspend fun renewSharing(request: ShareSessionRequest) = startSharing(request)
    override suspend fun publishSharingUpdate(groupId: RideGroupId, update: ParticipantShareUpdate): SocialResult<Unit> {
        publishedUpdates++
        return SocialResult.Success(Unit)
    }
    override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> {
        stopSharingGate?.await()
        stopSharingCalls++
        activeSharing.value = null
        return SocialResult.Success(Unit)
    }
    override suspend fun joinVoice(groupId: RideGroupId) = unexpected<VoiceRoomCredentials>()
    override suspend fun getVoiceProvider() = SocialResult.Success(VoiceProviderAvailability(false, "none"))
    override suspend fun leaveVoice(groupId: RideGroupId): SocialResult<Unit> {
        leaveVoiceCalls++
        return SocialResult.Success(Unit)
    }
}

private class FakeRuntimeVoiceRepository : VoiceRoomRepository {
    override val state = MutableStateFlow<VoiceRoomState>(VoiceRoomState.Available)
    var leaveCalls = 0
    override suspend fun join(groupId: RideGroupId) = SocialResult.Success(Unit)
    override suspend fun leave(): SocialResult<Unit> {
        leaveCalls++
        state.value = VoiceRoomState.Available
        return SocialResult.Success(Unit)
    }
    override suspend fun setMuted(muted: Boolean) = SocialResult.Success(Unit)
}

private class FakeRuntimeLocationProvider(
    var onStart: (() -> Unit)? = null,
) : LocationProvider {
    val locationUpdates = MutableSharedFlow<LocationSnapshot>(extraBufferCapacity = 1)
    override val updates: Flow<LocationSnapshot> = locationUpdates
    var stopCalls = 0

    override suspend fun start() {
        onStart?.invoke()
    }
    override suspend fun stop() { stopCalls++ }
}

private class FakeRuntimeTelemetrySource : SocialTelemetrySource {
    override val latest: StateFlow<EarnedTelemetry?> = MutableStateFlow(null)
}

private fun testGroup() = RideGroup(
    id = RideGroupId("group-1"),
    name = "Night Ride",
    ownerId = SocialUserId("owner"),
)

private fun authenticated() = SocialSession.Authenticated(
    userId = SocialUserId("u1"),
    displayName = "Rider",
    tokenState = SessionTokenState.ACTIVE,
)

private fun testLocation() = LocationSnapshot(
    latitude = 56.8389,
    longitude = 60.6057,
    accuracyMeters = 4.0,
    capturedAtEpochMillis = 1_000L,
    staleAfterEpochMillis = 16_000L,
)

private fun <T> unexpected(): SocialResult<T> = throw AssertionError("Unexpected fake call")

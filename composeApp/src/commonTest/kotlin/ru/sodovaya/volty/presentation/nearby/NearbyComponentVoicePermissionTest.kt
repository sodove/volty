package ru.sodovaya.volty.presentation.nearby

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import ru.sodovaya.volty.domain.social.FriendRequest
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.domain.social.GroupMemberRole
import ru.sodovaya.volty.domain.social.GroupMemberSummary
import ru.sodovaya.volty.domain.social.LocationProvider
import ru.sodovaya.volty.domain.social.LocationSnapshot
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
import ru.sodovaya.volty.domain.social.SocialShareSessionCoordinator
import ru.sodovaya.volty.domain.social.SocialTelemetrySource
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.VoiceRoomFailureReason
import ru.sodovaya.volty.domain.social.VoiceRoomRepository
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.UserSearchResult
import ru.sodovaya.volty.data.social.DefaultSocialRideRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NearbyComponentVoicePermissionTest {
    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun joinRequestsMicrophonePermissionBeforeCallingVoiceRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val voiceRepository = FakeNearbyVoiceRoomRepository(
            requiredPermissions = listOf("android.permission.RECORD_AUDIO"),
        )
        val component = component(voiceRepository)

        component.onSelectGroup(nearbyTestGroup())
        advanceUntilIdle()

        component.onJoinVoice()
        assertTrue(component.state.value.pendingVoicePermissionRequest)
        assertEquals(emptyList(), voiceRepository.joinCalls)

        component.onVoicePermissionResult(granted = true)
        advanceUntilIdle()

        assertFalse(component.state.value.pendingVoicePermissionRequest)
        assertEquals(listOf(RideGroupId("group-1")), voiceRepository.joinCalls)
    }

    @Test
    fun deniedMicrophonePermissionDoesNotCallVoiceRepositoryAndShowsVoiceFailure() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val voiceRepository = FakeNearbyVoiceRoomRepository(
            requiredPermissions = listOf("android.permission.RECORD_AUDIO"),
        )
        val component = component(voiceRepository)

        component.onSelectGroup(nearbyTestGroup())
        advanceUntilIdle()

        component.onJoinVoice()
        component.onVoicePermissionResult(granted = false)
        advanceUntilIdle()

        assertEquals(emptyList(), voiceRepository.joinCalls)
        assertEquals(
            VoiceRoomState.Failed(VoiceRoomFailureReason.MICROPHONE_PERMISSION_DENIED),
            component.state.value.voice,
        )
    }

    @Test
    fun successfulDeleteClearsSelectedGroupLiveSharingAndVoiceState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository()
        val group = nearbyTestGroup().copy(ownerId = SocialUserId("u1"))
        val voiceRepository = FakeNearbyVoiceRoomRepository(requiredPermissions = emptyList()).apply {
            state.value = VoiceRoomState.Joined(muted = true)
        }
        val component = component(voiceRepository, socialRepository)

        component.onSelectGroup(group)
        socialRepository.activeSharing.value = SharingSession(group.id, ru.sodovaya.volty.domain.social.TelemetryShareProfile.LOCATION, 10_000L)
        advanceUntilIdle()

        component.onDeleteGroup(group)
        advanceUntilIdle()

        assertEquals(listOf(group.id), socialRepository.deleteCalls)
        assertEquals(listOf(Unit), voiceRepository.leaveCalls)
        assertEquals(emptyList(), component.state.value.groups)
        assertEquals(null, component.state.value.selectedGroup)
        assertEquals(emptyList(), component.state.value.markers)
        assertEquals(null, component.state.value.liveEvent)
        assertEquals(null, component.state.value.sharing)
        assertEquals(VoiceRoomState.Available, component.state.value.voice)
    }

    @Test
    fun friend_search_is_debounced_and_only_latest_query_updates_state() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository().apply {
            searchResults = listOf(
                UserSearchResult(SocialUserId("alex"), "Alex"),
            )
        }
        val component = component(FakeNearbyVoiceRoomRepository(emptyList()), socialRepository)
        advanceUntilIdle()
        socialRepository.searchCalls.clear()

        component.onFriendQueryChanged("al")
        advanceTimeBy(299)
        assertEquals(emptyList(), socialRepository.searchCalls)

        component.onFriendQueryChanged("alex")
        advanceUntilIdle()

        assertEquals(listOf("alex"), socialRepository.searchCalls)
        assertEquals("Alex", component.state.value.friendSearchResults.single().displayName)
        assertFalse(component.state.value.friendSearchLoading)
    }

    @Test
    fun search_failure_keeps_previous_results() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository().apply {
            searchResults = listOf(UserSearchResult(SocialUserId("alex"), "Alex"))
        }
        val component = component(FakeNearbyVoiceRoomRepository(emptyList()), socialRepository)
        advanceUntilIdle()

        component.onFriendQueryChanged("alex")
        advanceUntilIdle()
        socialRepository.searchFailure = true
        component.onFriendQueryChanged("offline")
        advanceUntilIdle()

        assertEquals("Alex", component.state.value.friendSearchResults.single().displayName)
        assertEquals("offline", component.state.value.error)
    }

    @Test
    fun invalid_login_keeps_authentication_error_visible() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository().apply {
            loginResult = SocialResult.Failure(ru.sodovaya.volty.domain.social.SocialFailure.Unauthorized)
        }
        val component = component(FakeNearbyVoiceRoomRepository(emptyList()), socialRepository)
        advanceUntilIdle()

        component.onEmailChanged("sodovaya@example.com")
        component.onPasswordChanged("wrong-password")
        component.onSubmitAuth()
        advanceUntilIdle()

        assertEquals("Неверный email или пароль", component.state.value.error)
    }

    @Test
    fun friend_request_refreshes_friends_and_clears_only_its_row_loading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository().apply {
            searchResults = listOf(UserSearchResult(SocialUserId("alex"), "Alex"))
        }
        val component = component(FakeNearbyVoiceRoomRepository(emptyList()), socialRepository)
        advanceUntilIdle()

        component.onSendFriendRequest("alex")
        advanceUntilIdle()

        assertEquals(listOf(SocialUserId("alex")), socialRepository.sendCalls)
        assertEquals(FriendshipState.REQUEST_SENT, component.state.value.friends.single().state)
        assertTrue(component.state.value.friendActionIds.isEmpty())
        assertEquals("Запрос отправлен", component.state.value.notice)
    }

    @Test
    fun incoming_friend_response_refreshes_the_friend_row() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val socialRepository = FakeNearbySocialRepository().apply {
            friends = listOf(
                FriendSummary(
                    friendshipId = ru.sodovaya.volty.domain.social.FriendshipId("friendship-1"),
                    userId = SocialUserId("alex"),
                    displayName = "Alex",
                    state = FriendshipState.REQUEST_RECEIVED,
                )
            )
        }
        val component = component(FakeNearbyVoiceRoomRepository(emptyList()), socialRepository)
        advanceUntilIdle()

        component.onRespondToFriendRequest("friendship-1", accept = true)
        advanceUntilIdle()

        assertEquals(listOf("friendship-1" to true), socialRepository.respondCalls)
        assertEquals(FriendshipState.ACCEPTED, component.state.value.friends.single().state)
        assertTrue(component.state.value.friendActionIds.isEmpty())
    }

    private fun component(
        voiceRepository: FakeNearbyVoiceRoomRepository,
        socialRepository: FakeNearbySocialRepository = FakeNearbySocialRepository(),
    ): DefaultNearbyComponent {
        return DefaultNearbyComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            socialRepository = socialRepository,
            socialRuntime = DefaultSocialRideRuntime(
                socialRepository = socialRepository,
                voiceRepository = voiceRepository,
                locationProvider = FakeLocationProvider(),
                sharingCoordinator = SocialShareSessionCoordinator(socialRepository, FakeTelemetrySource()),
                freshnessTickerIntervalMillis = null,
            ),
            onBackRequested = {},
        )
    }
}

private class FakeNearbyVoiceRoomRepository(
    override val requiredPermissions: List<String>,
) : VoiceRoomRepository {
    override val state = MutableStateFlow<VoiceRoomState>(VoiceRoomState.Available)
    val joinCalls = mutableListOf<RideGroupId>()
    val leaveCalls = mutableListOf<Unit>()

    override suspend fun join(groupId: RideGroupId): SocialResult<Unit> {
        joinCalls += groupId
        state.value = VoiceRoomState.Joined(muted = false)
        return SocialResult.Success(Unit)
    }

    override suspend fun leave(): SocialResult<Unit> {
        leaveCalls += Unit
        state.value = VoiceRoomState.Available
        return SocialResult.Success(Unit)
    }
    override suspend fun setMuted(muted: Boolean): SocialResult<Unit> = SocialResult.Success(Unit)
}

private class FakeNearbySocialRepository(
    initialSession: SocialSession = SocialSession.Authenticated(
        userId = SocialUserId("u1"),
        displayName = "Rider",
        tokenState = SessionTokenState.ACTIVE,
        emailVerified = true,
    ),
) : SocialRepository {
    override val session = MutableStateFlow(initialSession)
    override val activeSharing = MutableStateFlow<SharingSession?>(null)
    val deleteCalls = mutableListOf<RideGroupId>()
    val searchCalls = mutableListOf<String>()
    val sendCalls = mutableListOf<SocialUserId>()
    val respondCalls = mutableListOf<Pair<String, Boolean>>()
    var searchResults: List<UserSearchResult> = emptyList()
    var searchFailure: Boolean = false
    var friends = emptyList<FriendSummary>()
    var loginResult: SocialResult<Unit> = SocialResult.Success(Unit)

    override suspend fun register(request: RegistrationRequest): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun login(request: LoginRequest): SocialResult<Unit> = loginResult.also { result ->
        if (result is SocialResult.Failure) session.value = SocialSession.LoggedOut
    }
    override suspend fun logout(): SocialResult<Unit> = SocialResult.Success(Unit)
    override suspend fun verifyEmail(token: String): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun requestPasswordReset(email: String): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun resetPassword(token: String, newPassword: String): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun deleteAccount(): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun getProfile(): SocialResult<SocialSession.Authenticated> = unexpectedNearbyCall()
    override suspend fun updateProfile(request: ProfileUpdate): SocialResult<SocialSession.Authenticated> = unexpectedNearbyCall()
    override suspend fun listFriends(): SocialResult<List<FriendSummary>> = SocialResult.Success(friends)
    override suspend fun sendFriendRequest(request: FriendRequest): SocialResult<Unit> {
        sendCalls += request.userId
        friends = listOf(
            FriendSummary(
                friendshipId = ru.sodovaya.volty.domain.social.FriendshipId("friendship-${request.userId.value}"),
                userId = request.userId,
                displayName = searchResults.first { it.userId == request.userId }.displayName,
                state = ru.sodovaya.volty.domain.social.FriendshipState.REQUEST_SENT,
            )
        )
        return SocialResult.Success(Unit)
    }
    override suspend fun respondToFriendRequest(friendshipId: String, accept: Boolean): SocialResult<Unit> {
        respondCalls += friendshipId to accept
        friends = if (accept) {
            friends.map { friend ->
                if (friend.friendshipId.value == friendshipId) friend.copy(state = FriendshipState.ACCEPTED) else friend
            }
        } else {
            friends.filterNot { it.friendshipId.value == friendshipId }
        }
        return SocialResult.Success(Unit)
    }
    override suspend fun searchUsers(query: String): SocialResult<List<UserSearchResult>> {
        searchCalls += query
        return if (searchFailure) SocialResult.Failure(ru.sodovaya.volty.domain.social.SocialFailure.Network("offline"))
        else SocialResult.Success(searchResults)
    }
    override suspend fun listGroups(): SocialResult<List<RideGroup>> = SocialResult.Success(listOf(nearbyTestGroup()))
    override suspend fun createGroup(name: String): SocialResult<RideGroup> = unexpectedNearbyCall()
    override suspend fun joinGroup(inviteCode: String): SocialResult<RideGroup> = unexpectedNearbyCall()
    override suspend fun leaveGroup(groupId: RideGroupId): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun deleteGroup(groupId: RideGroupId): SocialResult<Unit> {
        deleteCalls += groupId
        return SocialResult.Success(Unit)
    }
    override fun observeGroup(groupId: RideGroupId): Flow<SocialLiveEvent> = emptyFlow()
    override suspend fun startSharing(request: ShareSessionRequest): SocialResult<SharingSession> = unexpectedNearbyCall()
    override suspend fun publishSharingUpdate(
        groupId: RideGroupId,
        update: ParticipantShareUpdate,
    ): SocialResult<Unit> = unexpectedNearbyCall()
    override suspend fun stopSharing(groupId: RideGroupId): SocialResult<Unit> {
        activeSharing.value = null
        return SocialResult.Success(Unit)
    }
    override suspend fun joinVoice(groupId: RideGroupId) =
        unexpectedNearbyCall<ru.sodovaya.volty.domain.social.VoiceRoomCredentials>()
    override suspend fun leaveVoice(groupId: RideGroupId) = SocialResult.Success(Unit)
}

private class FakeLocationProvider : LocationProvider {
    override val updates: Flow<LocationSnapshot> = emptyFlow()
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}

private class FakeTelemetrySource : SocialTelemetrySource {
    override val latest: StateFlow<ru.sodovaya.volty.domain.social.EarnedTelemetry?> =
        MutableStateFlow(null)
}

private fun nearbyTestGroup() = RideGroup(
    id = RideGroupId("group-1"),
    name = "Night Ride",
    ownerId = SocialUserId("owner"),
    members = listOf(
        GroupMemberSummary(
            userId = SocialUserId("owner"),
            displayName = "Owner",
            role = GroupMemberRole.OWNER,
        ),
    ),
)

private fun <T> unexpectedNearbyCall(): SocialResult<T> = throw AssertionError("Unexpected fake call")

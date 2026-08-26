package ru.sodovaya.volty.presentation.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.social.FriendSummary
import ru.sodovaya.volty.domain.social.FriendshipId
import ru.sodovaya.volty.domain.social.FriendshipState
import ru.sodovaya.volty.domain.social.GroupMemberRole
import ru.sodovaya.volty.domain.social.GroupMemberSummary
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SessionTokenState
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialResult
import ru.sodovaya.volty.domain.social.SocialSession
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.TelemetryShareProfile
import ru.sodovaya.volty.domain.social.VoiceRoomState
import ru.sodovaya.volty.domain.social.UserSearchResult

class NearbyUiStateTest {
    @Test
    fun roster_member_without_location_remains_visible_without_a_current_point() {
        val participant = ParticipantSnapshot(
            userId = SocialUserId("member"),
            displayName = "Member",
            presence = PresenceStatus.ONLINE,
            location = null,
            telemetry = null,
            lastSeenAtEpochMillis = 1_000L,
        )

        assertFalse(nearbyParticipantHasCurrentPoint(participant))
    }

    @Test
    fun friend_search_rows_expose_send_only_for_users_without_friendship_state() {
        val result = UserSearchResult(
            userId = SocialUserId("new-user"),
            displayName = "New User",
        )

        assertEquals(FriendSearchAction.SEND_REQUEST, friendSearchAction(result))
    }

    @Test
    fun friend_search_rows_keep_server_friendship_status() {
        val result = UserSearchResult(
            userId = SocialUserId("friend"),
            displayName = "Friend",
            friendshipId = FriendshipId("friendship"),
            state = FriendshipState.REQUEST_SENT,
        )

        assertEquals(FriendSearchAction.STATUS, friendSearchAction(result))
    }

    @Test
    fun friend_rows_are_busy_only_when_their_own_action_is_running() {
        assertTrue(isFriendRowBusy(setOf("friendship-1"), "friendship-1"))
        assertFalse(isFriendRowBusy(setOf("friendship-1"), "friendship-2"))
    }

    @Test
    fun friend_action_key_prefers_friendship_id_when_server_provides_one() {
        val result = UserSearchResult(
            userId = SocialUserId("friend"),
            displayName = "Friend",
            friendshipId = FriendshipId("friendship"),
        )

        assertEquals("friendship", friendActionKey(result))
    }

    @Test
    fun friend_states_are_sorted_with_incoming_requests_first() {
        val friends = listOf(
            friend(FriendshipState.ACCEPTED, "accepted"),
            friend(FriendshipState.REQUEST_SENT, "sent"),
            friend(FriendshipState.BLOCKED, "blocked"),
            friend(FriendshipState.REQUEST_RECEIVED, "received"),
        )

        assertEquals(
            listOf("received", "accepted", "sent", "blocked"),
            sortFriendsForDisplay(friends).map { it.displayName },
        )
    }

    @Test
    fun refresh_failure_keeps_cached_groups_and_friends() {
        val group = group(ownerId = "owner")
        val friend = FriendSummary(
            friendshipId = FriendshipId("friendship"),
            userId = SocialUserId("friend"),
            displayName = "Friend",
            state = FriendshipState.ACCEPTED,
        )
        val current = NearbyComponent.State(
            groups = listOf(group),
            friends = listOf(friend),
            selectedGroup = group,
        )

        val merged = mergeRefreshState(
            current = current,
            groups = SocialResult.Failure(SocialFailure.Network("offline")),
            friends = SocialResult.Success(emptyList()),
        )

        assertEquals(listOf(group), merged.groups)
        assertEquals(emptyList(), merged.friends)
        assertEquals(group, merged.selectedGroup)
        assertEquals("offline", merged.error)
    }

    @Test
    fun member_does_not_get_owner_controls_but_can_leave() {
        val memberSession = authenticated("member")
        val group = group(ownerId = "owner")

        assertFalse(isGroupOwner(memberSession, group))
        assertTrue(canLeaveGroup(memberSession, group))
    }

    @Test
    fun owner_can_leave_without_deleting_the_group() {
        val ownerSession = authenticated("owner")

        assertTrue(canLeaveGroup(ownerSession, group(ownerId = "owner")))
    }

    @Test
    fun owner_gets_owner_controls() {
        assertTrue(isGroupOwner(authenticated("owner"), group(ownerId = "owner")))
    }

    @Test
    fun owner_group_control_uses_delete_action() {
        assertEquals(
            GroupManagementAction.DELETE,
            groupManagementAction(authenticated("owner"), group(ownerId = "owner")),
        )
    }

    @Test
    fun member_group_control_uses_leave_action() {
        assertEquals(
            GroupManagementAction.LEAVE,
            groupManagementAction(authenticated("member"), group(ownerId = "owner")),
        )
    }

    @Test
    fun sharing_ttl_is_never_negative() {
        assertEquals(90_000L, remainingSharingMillis(expiresAt = 190_000L, now = 100_000L))
        assertEquals(0L, remainingSharingMillis(expiresAt = 90_000L, now = 100_000L))
    }

    @Test
    fun second_operation_is_rejected_while_one_is_running() {
        val state = NearbyComponent.State(operation = NearbyComponent.Operation.CREATE_GROUP)

        assertFalse(canStartOperation(state))
        assertTrue(canStartOperation(state.copy(operation = null)))
    }

    @Test
    fun owner_invite_code_is_taken_from_the_server_group() {
        assertEquals("NIGHT-42", inviteCodeFor(group(ownerId = "owner", inviteCode = "NIGHT-42")))
    }

    @Test
    fun joining_an_existing_group_replaces_by_id_without_a_duplicate_entry() {
        val existing = group(ownerId = "owner")
        val joined = existing.copy(
            members = existing.members + GroupMemberSummary(
                userId = SocialUserId("member"),
                displayName = "Member",
                role = GroupMemberRole.MEMBER,
            ),
        )

        assertEquals(listOf(joined), upsertGroupEntry(listOf(existing), joined))
    }

    @Test
    fun removing_selected_group_clears_live_sharing_and_voice_state() {
        val selected = group(ownerId = "owner")
        val current = NearbyComponent.State(
            groups = listOf(selected),
            selectedGroup = selected,
            liveEvent = SocialLiveEvent.Snapshot(LiveGroupSnapshot(selected.id, 1L, emptyList())),
            markers = listOf(
                ParticipantMarker(
                    userId = "member",
                    label = "Member",
                    latitude = 56.8,
                    longitude = 60.6,
                    accuracyMeters = 5.0,
                    presence = PresenceStatus.ONLINE,
                    stale = false,
                ),
            ),
            sharing = SharingSession(selected.id, TelemetryShareProfile.LOCATION, 10_000L),
            pendingVoicePermissionRequest = true,
            voice = VoiceRoomState.Joined(muted = true),
        )

        val cleared = clearRemovedGroupState(current, selected.id, VoiceRoomState.Available)

        assertEquals(emptyList(), cleared.groups)
        assertNull(cleared.selectedGroup)
        assertNull(cleared.liveEvent)
        assertEquals(emptyList(), cleared.markers)
        assertNull(cleared.sharing)
        assertFalse(cleared.pendingVoicePermissionRequest)
        assertEquals(VoiceRoomState.Available, cleared.voice)
    }

    private fun authenticated(userId: String) = SocialSession.Authenticated(
        userId = SocialUserId(userId),
        displayName = "Rider",
        tokenState = SessionTokenState.ACTIVE,
    )

    private fun friend(state: FriendshipState, name: String) = FriendSummary(
        friendshipId = FriendshipId("friendship-$name"),
        userId = SocialUserId("user-$name"),
        displayName = name,
        state = state,
    )

    private fun group(ownerId: String, inviteCode: String? = null) = RideGroup(
        id = RideGroupId("group"),
        name = "Night Ride",
        ownerId = SocialUserId(ownerId),
        inviteCode = inviteCode,
        members = listOf(
            GroupMemberSummary(
                userId = SocialUserId(ownerId),
                displayName = "Owner",
                role = GroupMemberRole.OWNER,
            ),
        ),
    )
}

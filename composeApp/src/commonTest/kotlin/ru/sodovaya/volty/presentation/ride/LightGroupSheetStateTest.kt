package ru.sodovaya.volty.presentation.ride

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.social.GroupMemberRole
import ru.sodovaya.volty.domain.social.GroupMemberSummary
import ru.sodovaya.volty.domain.social.LiveGroupSnapshot
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroup
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SharingSession
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialParticipantMarker
import ru.sodovaya.volty.domain.social.SocialRuntimeState
import ru.sodovaya.volty.domain.social.SocialUserId
import ru.sodovaya.volty.domain.social.TelemetryShareProfile

class LightGroupSheetStateTest {
    @Test
    fun maps_selected_group_members_and_sharing_to_the_light_sheet() {
        val group = RideGroup(
            id = RideGroupId("night-ride"),
            name = "Ночная поездка",
            ownerId = SocialUserId("anya"),
            members = listOf(
                GroupMemberSummary(SocialUserId("anya"), "Аня", GroupMemberRole.OWNER),
                GroupMemberSummary(SocialUserId("ilya"), "Илья", GroupMemberRole.MEMBER),
            ),
        )

        val sheet = lightGroupSheetState(
            SocialRuntimeState(
                selectedGroup = group,
                sharing = SharingSession(
                    groupId = group.id,
                    profile = TelemetryShareProfile.LOCATION,
                    expiresAtEpochMillis = 123_000L,
                ),
            ),
        )

        assertEquals("Ночная поездка", sheet?.groupName)
        assertEquals(listOf("Аня", "Илья"), sheet?.members?.map { it.name })
        assertTrue(sheet?.sharingActive == true)
        assertEquals(123_000L, sheet?.sharingExpiresAtEpochMillis)
    }

    @Test
    fun empty_runtime_has_no_sheet_group_and_dismiss_does_not_clear_runtime() {
        val sheet = lightGroupSheetState(SocialRuntimeState())

        assertEquals(null, sheet)
        assertFalse(lightGroupSheetVisibleAfterDismiss(initiallyVisible = true))
        assertTrue(lightRuntimeShouldRemainSelectedAfterDismiss())
    }

    @Test
    fun missing_current_point_is_not_presented_as_offline() {
        assertEquals("нет текущей точки", lightGroupMemberStatusText(null))
        assertEquals("оффлайн", lightGroupMemberStatusText(PresenceStatus.OFFLINE))
    }

    @Test
    fun sharing_expiry_is_displayed_as_a_human_date_instead_of_epoch_millis() {
        assertEquals("01.01, 00:00", formatSharingExpiry(0L, TimeZone.UTC))
    }

    @Test
    fun sheet_marks_a_member_without_a_location_as_having_no_current_point() {
        val member = GroupMemberSummary(SocialUserId("anya"), "Аня", GroupMemberRole.OWNER)
        val group = RideGroup(
            id = RideGroupId("night-ride"),
            name = "Ночная поездка",
            ownerId = member.userId,
            members = listOf(member),
        )
        val sheet = lightGroupSheetState(
            SocialRuntimeState(
                selectedGroup = group,
                liveEvent = SocialLiveEvent.Snapshot(
                    LiveGroupSnapshot(
                        groupId = group.id,
                        capturedAtEpochMillis = 100L,
                        participants = listOf(
                            ParticipantSnapshot(
                                userId = member.userId,
                                displayName = member.displayName,
                                presence = PresenceStatus.STALE,
                                location = null,
                                telemetry = null,
                                lastSeenAtEpochMillis = 90L,
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(null, sheet?.members?.single()?.status)
        assertFalse(sheet?.members?.single()?.hasCurrentPoint == true)
    }

    @Test
    fun sheet_prefers_stale_marker_status_over_online_snapshot_presence() {
        val member = GroupMemberSummary(SocialUserId("anya"), "Аня", GroupMemberRole.OWNER)
        val group = RideGroup(
            id = RideGroupId("night-ride"),
            name = "Ночная поездка",
            ownerId = member.userId,
            members = listOf(member),
        )
        val sheet = lightGroupSheetState(
            SocialRuntimeState(
                selectedGroup = group,
                liveEvent = SocialLiveEvent.Snapshot(
                    LiveGroupSnapshot(
                        groupId = group.id,
                        capturedAtEpochMillis = 100L,
                        participants = listOf(
                            ParticipantSnapshot(
                                userId = member.userId,
                                displayName = member.displayName,
                                presence = PresenceStatus.ONLINE,
                                location = null,
                                telemetry = null,
                                lastSeenAtEpochMillis = 90L,
                            ),
                        ),
                    ),
                ),
                markers = listOf(
                    SocialParticipantMarker(
                        userId = member.userId.value,
                        label = member.displayName,
                        latitude = 56.8,
                        longitude = 60.6,
                        accuracyMeters = 20.0,
                        presence = PresenceStatus.STALE,
                        stale = true,
                    ),
                ),
            ),
        )

        assertEquals(PresenceStatus.STALE, sheet?.members?.single()?.status)
    }
}

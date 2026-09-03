package ru.sodovaya.volty.presentation.ride

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.presentation.nearby.SocialLiveState

class LightGroupRideUiStateTest {
    @Test
    fun no_selected_group_hides_the_light_group_affordance() {
        assertNull(
            lightGroupRideUiState(
                SocialLiveState(
                    markers = listOf(
                        ParticipantMarker(
                            userId = "rider-1",
                            label = "Rider",
                            latitude = 56.8,
                            longitude = 60.6,
                            accuracyMeters = 5.0,
                            presence = PresenceStatus.ONLINE,
                            stale = false,
                        ),
                    ),
                ),
            ),
        )
    }

    @Test
    fun active_group_keeps_real_markers_and_counts_online_participants() {
        val markers = listOf(
            ParticipantMarker(
                userId = "rider-1",
                label = "Rider",
                latitude = 56.8,
                longitude = 60.6,
                accuracyMeters = 5.0,
                presence = PresenceStatus.ONLINE,
                stale = false,
            ),
            ParticipantMarker(
                userId = "rider-2",
                label = "Stale rider",
                latitude = 56.81,
                longitude = 60.61,
                accuracyMeters = 12.0,
                presence = PresenceStatus.STALE,
                stale = true,
            ),
        )

        val mapped = lightGroupRideUiState(
            SocialLiveState(
                groupId = RideGroupId("group-1"),
                markers = markers,
            ),
        )

        assertEquals(RideGroupId("group-1"), mapped?.groupId)
        assertEquals(markers, mapped?.markers)
        assertEquals(2, mapped?.participantCount)
        assertEquals(1, mapped?.onlineParticipantCount)
    }
}

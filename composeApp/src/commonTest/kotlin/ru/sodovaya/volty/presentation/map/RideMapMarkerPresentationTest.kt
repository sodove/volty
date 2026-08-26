package ru.sodovaya.volty.presentation.map

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

class RideMapMarkerPresentationTest {
    @Test
    fun live_marker_label_contains_name_state_and_accuracy() {
        assertEquals(
            "Sasha · онлайн · ±8 м",
            rideMapMarkerLabel(
                ParticipantMarker(
                    userId = "u1",
                    label = "Sasha",
                    latitude = 56.8,
                    longitude = 60.6,
                    accuracyMeters = 8.4,
                    presence = PresenceStatus.ONLINE,
                    stale = false,
                ),
            ),
        )
    }

    @Test
    fun stale_presence_is_explicitly_shown_on_the_marker_label() {
        assertEquals(
            "Rider · устарело · ±12 м",
            rideMapMarkerLabel(
                ParticipantMarker(
                    userId = "u1",
                    label = "Rider",
                    latitude = 56.8,
                    longitude = 60.6,
                    accuracyMeters = 12.1,
                    presence = PresenceStatus.STALE,
                    stale = true,
                ),
            ),
        )
    }
}

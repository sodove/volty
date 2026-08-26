package ru.sodovaya.volty.presentation.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.SocialLiveEvent
import ru.sodovaya.volty.domain.social.SocialUserId

class SocialLiveSessionTest {
    @Test
    fun shareRevokedRemovesOnlyTheRevokedParticipant() {
        val first = marker("u1")
        val second = marker("u2")

        assertEquals(
            listOf(second),
            reduceParticipantMarkers(
                current = listOf(first, second),
                event = SocialLiveEvent.ShareRevoked(SocialUserId("u1")),
                nowEpochMillis = 10_000L,
            ),
        )
    }

    @Test
    fun liveStreamFailureKeepsLastKnownMarkersAsStale() {
        assertEquals(
            listOf(marker("u1").copy(presence = PresenceStatus.STALE, stale = true)),
            reduceParticipantMarkers(
                current = listOf(marker("u1")),
                event = SocialLiveEvent.Failure(
                    ru.sodovaya.volty.domain.social.SocialFailure.Network("offline")
                ),
                nowEpochMillis = 10_000L,
            ),
        )
    }

    @Test
    fun terminal_subscription_event_clears_markers() {
        assertEquals(
            emptyList(),
            reduceParticipantMarkers(
                current = listOf(marker("u1")),
                event = SocialLiveEvent.Failure(
                    ru.sodovaya.volty.domain.social.SocialFailure.Forbidden,
                    terminal = true,
                ),
                nowEpochMillis = 10_000L,
            ),
        )
    }

    private fun marker(userId: String) = ParticipantMarker(
        userId = userId,
        label = userId,
        latitude = 56.8389,
        longitude = 60.6057,
        accuracyMeters = 8.0,
        presence = PresenceStatus.ONLINE,
        stale = false,
    )
}

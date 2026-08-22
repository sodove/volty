package ru.sodovaya.volty.presentation.nearby

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.domain.social.SocialUserId

class ParticipantMarkerMapperTest {
    @Test
    fun missingLocationIsNotDrawn() {
        val markers = ParticipantMarkerMapper.map(
            listOf(
                ParticipantSnapshot(SocialUserId("u1"), "Rider", PresenceStatus.ONLINE, null, null, 1_000L)
            ),
            nowEpochMillis = 2_000L,
        )
        assertTrue(markers.isEmpty())
    }

    @Test
    fun futureCaptureIsNotDrawn() {
        val markers = ParticipantMarkerMapper.map(listOf(participant(capturedAt = 3_000L)), 2_000L)
        assertTrue(markers.isEmpty())
    }

    @Test
    fun staleLocationIsNotDrawn() {
        val markers = ParticipantMarkerMapper.map(
            listOf(participant(capturedAt = 1_000L, staleAfter = 1_500L)),
            nowEpochMillis = 2_000L,
        )
        assertTrue(markers.isEmpty())
    }

    private fun participant(capturedAt: Long, staleAfter: Long = 10_000L) = ParticipantSnapshot(
        userId = SocialUserId("u1"),
        displayName = "Rider",
        presence = PresenceStatus.ONLINE,
        location = LocationSnapshot(56.8389, 60.6057, 8.0, capturedAt, staleAfter),
        telemetry = null,
        lastSeenAtEpochMillis = capturedAt,
    )
}

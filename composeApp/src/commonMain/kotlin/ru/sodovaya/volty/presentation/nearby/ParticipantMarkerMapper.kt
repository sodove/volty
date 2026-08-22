package ru.sodovaya.volty.presentation.nearby

import ru.sodovaya.volty.domain.social.LocationSharePolicy
import ru.sodovaya.volty.domain.social.LocationSnapshotStatus
import ru.sodovaya.volty.domain.social.ParticipantSnapshot
import ru.sodovaya.volty.domain.social.PresenceStatus

/** Common marker data; an Android map SDK consumes this without owning policy. */
data class ParticipantMarker(
    val userId: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val presence: PresenceStatus,
    val stale: Boolean,
)

object ParticipantMarkerMapper {
    fun map(
        participants: List<ParticipantSnapshot>,
        nowEpochMillis: Long,
    ): List<ParticipantMarker> = participants.mapNotNull { participant ->
        val location = participant.location ?: return@mapNotNull null
        if (location.capturedAtEpochMillis > nowEpochMillis) return@mapNotNull null
        if (LocationSharePolicy.snapshotStatus(location, nowEpochMillis) == LocationSnapshotStatus.STALE) {
            return@mapNotNull null
        }
        ParticipantMarker(
            userId = participant.userId.value,
            label = participant.displayName,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracyMeters,
            presence = participant.presence,
            stale = participant.presence == PresenceStatus.STALE,
        )
    }
}

package ru.sodovaya.volty.presentation.nearby

import kotlinx.coroutines.flow.StateFlow
import ru.sodovaya.volty.domain.social.RideGroupId
import ru.sodovaya.volty.domain.social.SocialFailure
import ru.sodovaya.volty.domain.social.SocialLiveEvent

data class SocialLiveState(
    val groupId: RideGroupId? = null,
    val liveEvent: SocialLiveEvent? = null,
    val markers: List<ParticipantMarker> = emptyList(),
)

/**
 * One live group subscription shared by the dashboard and the Nearby controls.
 * It deliberately lives above both screens so navigation cannot make friends
 * disappear from the Ride map.
 */
interface SocialLiveSession {
    val state: StateFlow<SocialLiveState>

    fun selectGroup(groupId: RideGroupId)
    fun clear()
    fun close()
}

internal fun reduceParticipantMarkers(
    current: List<ParticipantMarker>,
    event: SocialLiveEvent,
    nowEpochMillis: Long,
): List<ParticipantMarker> = when (event) {
    is SocialLiveEvent.Snapshot -> ParticipantMarkerMapper.map(event.value.participants, nowEpochMillis)
    is SocialLiveEvent.ShareRevoked -> current.filterNot { it.userId == event.userId.value }
    is SocialLiveEvent.ShareExpired -> current.filterNot { it.userId == event.userId.value }
    is SocialLiveEvent.Failure -> if (event.error is SocialFailure.Network) {
        if (event.terminal) emptyList()
        else current.map { it.copy(presence = ru.sodovaya.volty.domain.social.PresenceStatus.STALE, stale = true) }
    } else if (event.terminal) {
        emptyList()
    } else {
        emptyList()
    }
}

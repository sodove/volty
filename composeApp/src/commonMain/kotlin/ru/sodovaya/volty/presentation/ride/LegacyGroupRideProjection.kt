package ru.sodovaya.volty.presentation.ride

import ru.sodovaya.volty.presentation.nearby.ParticipantMarker
import ru.sodovaya.volty.presentation.nearby.SocialLiveState
import ru.sodovaya.volty.domain.social.PresenceStatus

// Earlier dashboard revisions consumed this group projection. The live dashboard now
// consumes LightChannelRideUiState; this pure legacy adapter remains for old contracts.
internal data class LightGroupRideUiState(
    val groupId: ru.sodovaya.volty.domain.social.RideGroupId,
    val markers: List<ParticipantMarker>,
) {
    val participantCount: Int get() = markers.size
    val onlineParticipantCount: Int get() = markers.count { it.presence == PresenceStatus.ONLINE }
}

internal fun lightGroupRideUiState(state: SocialLiveState): LightGroupRideUiState? =
    state.groupId?.let { groupId ->
        LightGroupRideUiState(groupId = groupId, markers = state.markers)
    }

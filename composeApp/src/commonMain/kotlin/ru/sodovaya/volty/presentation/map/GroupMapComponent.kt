package ru.sodovaya.volty.presentation.map

import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

/** State projection for GroupMap; runtime ownership stays in SocialRideRuntime. */
internal data class GroupMapState(
    val markers: List<ParticipantMarker>,
) {
    val participantCount: Int get() = markers.size
}

internal fun groupMapState(markers: List<ParticipantMarker>): GroupMapState =
    GroupMapState(markers = markers)

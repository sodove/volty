package ru.sodovaya.volty.presentation.map

import kotlin.math.roundToInt
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

/** Compact text shown above a remote live marker without exposing extra data. */
internal fun rideMapMarkerLabel(marker: ParticipantMarker): String {
    val presenceLabel = when (marker.presence) {
        PresenceStatus.ONLINE -> "онлайн"
        PresenceStatus.STALE -> "устарело"
        PresenceStatus.OFFLINE -> "офлайн"
    }
    val accuracyLabel = marker.accuracyMeters
        .takeIf { it.isFinite() && it >= 0.0 }
        ?.roundToInt()
        ?.coerceAtLeast(0)
        ?.let { "±$it м" }
        ?: "точность неизвестна"
    return buildString {
        append(marker.label.ifBlank { "Участник" })
        append(" · ")
        append(presenceLabel)
        append(" · ")
        append(accuracyLabel)
    }
}

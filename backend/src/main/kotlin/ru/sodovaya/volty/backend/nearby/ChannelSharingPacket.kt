package ru.sodovaya.volty.backend.nearby

import kotlinx.serialization.Serializable
import ru.sodovaya.volty.backend.LocationDto
import ru.sodovaya.volty.backend.SharedTelemetryDto

@Serializable
data class ChannelSharingPacket(val sequence: Long, val capturedAtEpochMillis: Long,
    val location: LocationDto?, val telemetry: SharedTelemetryDto? = null)
data class StoredChannelSharingPacket(val packet: ChannelSharingPacket, val deadlineEpochMillis: Long)
data class ChannelSharingAudiencePeer(val actorId: String, val sessionId: String, val generation: String,
    val packet: ChannelSharingPacket, val deadlineEpochMillis: Long)
data class ChannelSharingAudience(val channelId: String, val serverTimeEpochMillis: Long,
    val peers: List<ChannelSharingAudiencePeer>)

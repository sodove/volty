package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.nearby.channelSharingPeerDisplayName
import ru.sodovaya.volty.data.voice.VoiceConversationState
import ru.sodovaya.volty.data.voice.VoiceConversationPhase
import ru.sodovaya.volty.data.voice.VoiceConversationTarget
import ru.sodovaya.volty.domain.voice.VoicePhase
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.voice.VoiceSessionTag
import ru.sodovaya.volty.domain.social.PresenceStatus
import ru.sodovaya.volty.presentation.nearby.ParticipantMarker

data class NearbyChannelMapPoint(val actorId: String, val latitude: Double, val longitude: Double, val accuracyMeters: Double, val displayName: String? = null)
data class NearbyChannelMapState(val operation: VoiceOperationId? = null, val channelId: String? = null,
    val session: VoiceSessionTag? = null, val observed: Boolean = false,
    val points: List<NearbyChannelMapPoint> = emptyList())
/** Native annotation DTO: ONLINE means a current point here, never microphone/voice presence. */
internal fun nearbyChannelMarkers(state: NearbyChannelMapState, fallbackLabel: String): List<ParticipantMarker> {
    if (!state.observed || state.operation == null || state.channelId == null || state.session == null) return emptyList()
    return state.points.map {point -> ParticipantMarker(point.actorId,channelSharingPeerDisplayName(point.displayName) ?: fallbackLabel,point.latitude,point.longitude,
        point.accuracyMeters,PresenceStatus.ONLINE,false)}
}

internal fun nearbyChannelMapState(source: VoiceConversationState, operation: VoiceOperationId?): NearbyChannelMapState {
    val channel = source.target as? VoiceConversationTarget.Channel ?: return NearbyChannelMapState()
    val voice = source.voice ?: return NearbyChannelMapState()
    val session = voice.sessionId ?: return NearbyChannelMapState()
    if (operation == null || source.operationId != operation || voice.operationId != operation ||
        source.phase != VoiceConversationPhase.JOIN_SUBMITTED || !voice.wanted || voice.cleanupPending ||
        voice.phase == VoicePhase.FAILED || voice.phase == VoicePhase.STOPPING) return NearbyChannelMapState()
    val audience = source.sharingAudience?.takeIf { it.observed }
    return NearbyChannelMapState(operation, channel.destinationId, VoiceSessionTag(session, voice.generation),
        observed = audience != null, points = audience?.peers?.mapNotNull { peer ->
            peer.packet.location?.let { NearbyChannelMapPoint(peer.actorId, it.latitude, it.longitude, it.accuracyMeters, channelSharingPeerDisplayName(peer.displayName)) }
        }.orEmpty())
}

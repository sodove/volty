package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.voice.*

data class NearbyChannelRosterPanel(val known:Boolean=false,
    val peers:List<VoiceRosterPresencePeer> = emptyList(),val connected:Int?=null, val profileNames:Map<String,String> = emptyMap())
internal fun nearbyChannelRosterPanel(view:NearbyVoiceView):NearbyChannelRosterPanel {
    val voice=view.voice ?: return NearbyChannelRosterPanel()
    val roster=view.channelRoster ?: return NearbyChannelRosterPanel()
    if(view.kind!=NearbyConversationKind.CHANNEL || view.phase!=NearbyVoiceUiPhase.ACTIVE || view.operation==null ||
        voice.operationId!=view.operation || voice.sessionId==null || !voice.wanted || voice.cleanupPending ||
        voice.phase==VoicePhase.FAILED || voice.phase==VoicePhase.STOPPING ||
        roster.retired || roster.unknown!=null) return NearbyChannelRosterPanel()
    val peers=roster.peers?.filter {it.leaseFresh}?.map {row ->
        row.copy(connected=row.connected && row.peer.transportConfirmed && row.peer.phase==VoiceRosterPhase.CONNECTED)
    } ?: return NearbyChannelRosterPanel()
    val names=peers.mapNotNull {row ->
        voiceRosterProfileName(roster.profileNames[row.peer.actorId])?.let {row.peer.actorId to it}
    }.toMap()
    return NearbyChannelRosterPanel(true,peers,peers.count {it.connected},names)
}

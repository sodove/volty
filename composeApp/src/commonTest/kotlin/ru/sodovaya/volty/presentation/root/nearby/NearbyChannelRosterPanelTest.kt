package ru.sodovaya.volty.presentation.root.nearby

import kotlin.test.*
import ru.sodovaya.volty.domain.voice.*

class NearbyChannelRosterPanelTest {
    private val operation=VoiceOperationId("original")
    private fun peer(id:String,fresh:Boolean=true,connected:Boolean=true,transport:Boolean=true)=VoiceRosterPresencePeer(
        VoiceRosterPeer(id,VoiceSessionId(id),operation,if(transport) VoiceRosterPhase.CONNECTED else VoiceRosterPhase.CONNECTING,
            1000,if(transport) "sid" else null,transport),fresh,connected)
    private fun view()=NearbyVoiceView(operation=operation,phase=NearbyVoiceUiPhase.ACTIVE,kind=NearbyConversationKind.CHANNEL,
        voice=VoiceSessionState(operationId=operation,sessionId=VoiceSessionId("own"),wanted=true),
        channelRoster=VoiceRosterPresenceView(3,listOf(peer("one"),peer("expired",fresh=false),peer("unconfirmed",transport=false)),999,null,false))
    @Test fun fresh_rows_and_transport_evidence_determine_counts_even_with_incoherent_provider() {
        val panel=nearbyChannelRosterPanel(view());assertTrue(panel.known)
        assertEquals(listOf("one","unconfirmed"),panel.peers.map {it.peer.actorId});assertEquals(1,panel.connected)
        assertFalse(panel.peers.last().connected)
        val empty=view().copy(channelRoster=VoiceRosterPresenceView(4,emptyList(),999,null,false))
        assertEquals(0,nearbyChannelRosterPanel(empty).connected);assertTrue(nearbyChannelRosterPanel(empty).known)
    }
    @Test fun missing_or_detached_evidence_cannot_earn_empty_roster_or_connected_count() {
        val current=view()
        for(candidate in listOf(current.copy(channelRoster=null),current.copy(kind=NearbyConversationKind.CALL),
            current.copy(operation=VoiceOperationId("other")),current.copy(phase=NearbyVoiceUiPhase.PREPARING),
            current.copy(voice=current.voice!!.copy(wanted=false)),current.copy(voice=current.voice!!.copy(cleanupPending=true)),
            current.copy(voice=current.voice!!.copy(phase=VoicePhase.FAILED)),
            current.copy(voice=current.voice!!.copy(phase=VoicePhase.STOPPING)),
            current.copy(channelRoster=current.channelRoster!!.copy(unknown=VoiceRosterUnknown.UNAVAILABLE)),
            current.copy(channelRoster=current.channelRoster!!.copy(retired=true)))) {
            val panel=nearbyChannelRosterPanel(candidate);assertFalse(panel.known);assertNull(panel.connected);assertTrue(panel.peers.isEmpty())
        }
    }
}

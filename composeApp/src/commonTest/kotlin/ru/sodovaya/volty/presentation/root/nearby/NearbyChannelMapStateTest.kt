package ru.sodovaya.volty.presentation.root.nearby

import kotlin.test.*
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.nearby.*
import ru.sodovaya.volty.domain.social.LocationSnapshot
import ru.sodovaya.volty.domain.voice.*

class NearbyChannelMapStateTest {
    private val operation = VoiceOperationId("original")
    private val tag = VoiceSessionTag(VoiceSessionId("session"), VoiceGeneration(3))
    private val point = ObservedChannelSharingPeer("actor", ChannelSharingTag(
        "10000000-0000-0000-0000-000000000001", "20000000-0000-0000-0000-000000000002"),
        ChannelSharingMode.LOCATION, ChannelSharingPacket(7, 1000, LocationSnapshot(55.0,60.0,4.0,1000,2000)),2000)
    private fun source() = VoiceConversationState(VoiceConversationPhase.JOIN_SUBMITTED,"channel",operation,
        VoiceSessionState(operationId=operation,sessionId=tag.sessionId,generation=tag.generation,wanted=true,
            phase=VoicePhase.CONNECTING,transportConnected=false,captureStarted=false,renderStarted=false),
        VoiceConversationTarget.Channel("channel"),sharingAudience=ChannelSharingAudienceView(true,listOf(point)))

    @Test fun actual_coordinates_are_not_voice_or_audibility_evidence() {
        val map=nearbyChannelMapState(source(),operation)
        assertEquals(operation,map.operation);assertEquals("channel",map.channelId);assertEquals(tag,map.session)
        assertTrue(map.observed);assertEquals(listOf(NearbyChannelMapPoint("actor",55.0,60.0,4.0)),map.points)
    }
    @Test fun incomplete_observation_cannot_earn_points_even_with_populated_payload() {
        val map=nearbyChannelMapState(source().copy(sharingAudience=ChannelSharingAudienceView(false,listOf(point))),operation)
        assertEquals(operation,map.operation);assertFalse(map.observed);assertTrue(map.points.isEmpty())
    }
    @Test fun another_operation_with_connected_flags_cannot_supply_positions() {
        assertEquals(NearbyChannelMapState(),nearbyChannelMapState(source(),VoiceOperationId("replacement")))
        assertEquals(NearbyChannelMapState(),nearbyChannelMapState(source().copy(voice=source().voice!!.copy(
            operationId=VoiceOperationId("other"),transportConnected=true)),operation))
    }
    @Test fun same_uuid_friend_call_cannot_carry_channel_points() {
        assertEquals(NearbyChannelMapState(),nearbyChannelMapState(source().copy(
            target=VoiceConversationTarget.OutgoingCall("channel","peer","key")),operation))
    }
    @Test fun absent_or_withdrawn_session_cannot_preserve_old_points() {
        assertEquals(NearbyChannelMapState(),nearbyChannelMapState(source().copy(phase=VoiceConversationPhase.PREPARING),operation))
        for(voice in listOf(null,source().voice!!.copy(wanted=false),source().voice!!.copy(cleanupPending=true),
            source().voice!!.copy(sessionId=null),source().voice!!.copy(phase=VoicePhase.FAILED))) {
            assertEquals(NearbyChannelMapState(),nearbyChannelMapState(source().copy(voice=voice),operation))
        }
    }
    @Test fun telemetry_without_coordinates_does_not_count_as_a_point() {
        val map=nearbyChannelMapState(source().copy(sharingAudience=ChannelSharingAudienceView(true,
            listOf(point.copy(packet=point.packet.copy(location=null))))),operation)
        assertTrue(map.observed);assertTrue(map.points.isEmpty())
    }
    @Test fun native_annotations_use_earned_coordinates_and_explicit_generic_label() {
        val markers=nearbyChannelMarkers(nearbyChannelMapState(source(),operation),"Participant")
        assertEquals(1,markers.size);assertEquals("actor",markers.single().userId)
        assertEquals("Participant",markers.single().label);assertEquals(55.0,markers.single().latitude)
        assertEquals(60.0,markers.single().longitude);assertEquals(4.0,markers.single().accuracyMeters)
    }
    @Test fun native_annotations_reject_unobserved_or_detached_payloads() {
        val active=nearbyChannelMapState(source(),operation)
        for(value in listOf(active.copy(observed=false),active.copy(operation=null),active.copy(channelId=null),active.copy(session=null))) {
            assertTrue(nearbyChannelMarkers(value,"Participant").isEmpty())
        }
    }
}

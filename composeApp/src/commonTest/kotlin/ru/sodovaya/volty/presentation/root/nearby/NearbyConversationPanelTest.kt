package ru.sodovaya.volty.presentation.root.nearby

import kotlin.test.*
import ru.sodovaya.volty.domain.voice.*

class NearbyConversationPanelTest {
    private val operation = VoiceOperationId("original")
    private fun view(voice: VoiceSessionState? = null) = NearbyVoiceView(
        operation = operation, phase = NearbyVoiceUiPhase.ACTIVE, voice = voice,
        kind = NearbyConversationKind.CHANNEL)

    @Test fun audioChoiceNeedsExactCurrentSessionAndCannotShowPendingAsSelected() {
        val session=VoiceSessionId("session");val generation=VoiceGeneration(3)
        val current=VoiceSessionState(operationId=operation,sessionId=session,generation=generation,wanted=true,phase=VoicePhase.CONNECTING,published=true)
        val chosen=VoiceAudioPreferences(VoiceInputPreference.PHONE,VoiceOutputPreference.HEADSET)
        val audio=ru.sodovaya.volty.data.voice.VoiceConversationAudioState(operation,VoiceSessionTag(session,generation),chosen,
            pending=chosen.copy(input=VoiceInputPreference.HEADSET))
        val original=view(current).copy(audio=audio)
        assertEquals(audio,nearbyConversationPanel(original).audio)
        assertEquals(chosen,nearbyConversationPanel(original).audio?.preferences)
        assertFalse(nearbyConversationPanel(original).audioEditable)
        assertTrue(nearbyConversationPanel(original.copy(audio=audio.copy(pending=null))).audioEditable)
        assertFalse(nearbyConversationPanel(original.copy(audio=audio.copy(pending=null),voice=current.copy(published=false))).audioEditable)
        for(bad in listOf(audio.copy(operation=VoiceOperationId("old")),audio.copy(tag=audio.tag.copy(generation=VoiceGeneration(2)))))
            assertNull(nearbyConversationPanel(original.copy(audio=bad)).audio)
        assertNull(nearbyConversationPanel(original.copy(voice=current.copy(cleanupPending=true))).audio)
        assertNull(nearbyConversationPanel(original.copy(voice=current.copy(wanted=false))).audio)
    }

    @Test fun admission_without_transport_evidence_is_connecting() {
        val panel = nearbyConversationPanel(view())
        assertEquals(NearbyPanelConnection.CONNECTING, panel.connection)
        assertEquals(operation, panel.disconnectOperation)
        assertNull(panel.audioOperation)
    }
    @Test fun connected_flags_from_another_operation_do_not_enable_audio_controls() {
        val panel = nearbyConversationPanel(view(VoiceSessionState(
            operationId = VoiceOperationId("replacement"), wanted = true,
            phase = VoicePhase.CONNECTED, transportConnected = true, published = true)))
        assertEquals(NearbyPanelConnection.CONNECTING, panel.connection)
        assertNull(panel.audioOperation)
    }
    @Test fun transport_connected_does_not_require_or_claim_audibility() {
        val panel = nearbyConversationPanel(view(VoiceSessionState(operationId = operation,
            wanted = true, phase = VoicePhase.CONNECTED, transportConnected = true,
            captureStarted = false, published = false, renderStarted = false)))
        assertEquals(NearbyPanelConnection.CONNECTED, panel.connection)
        assertEquals(operation, panel.audioOperation)
    }
    @Test fun reconnecting_wins_over_stale_transport_flag() {
        assertEquals(NearbyPanelConnection.RECONNECTING, nearbyConversationPanel(view(
            VoiceSessionState(operationId = operation, wanted = true,
                phase = VoicePhase.RECONNECTING, transportConnected = true))).connection)
    }
    @Test fun stopped_intent_cannot_show_connected_or_offer_audio_controls() {
        val panel = nearbyConversationPanel(view(VoiceSessionState(operationId = operation,
            wanted = false, phase = VoicePhase.STOPPING, transportConnected = true)))
        assertEquals(NearbyPanelConnection.STOPPING, panel.connection)
        assertNull(panel.audioOperation)
    }
    @Test fun old_trip_remains_cleanup_only_under_a_replacement_conversation() {
        val panel = nearbyConversationPanel(view(), NearbySharingView(
            operation = VoiceOperationId("old"), generation = "old-trip", wanted = false,
            active = true, localCleanupPending = true, serverConfirmationPending = false))
        assertNull(panel.hideTrip)
        assertFalse(panel.tripActive)
        assertTrue(panel.localCleanupPending)
        assertFalse(panel.serverConfirmationPending)
        assertEquals(NearbyTripAction(VoiceOperationId("old"), "old-trip"), panel.retryTripCleanup)
    }
    @Test fun exact_trip_hide_and_disconnect_keep_different_original_targets() {
        val panel = nearbyConversationPanel(view(), NearbySharingView(
            operation = operation, generation = "trip", wanted = true, preparing = true))
        assertNull(panel.hideTrip)
        assertEquals("trip", panel.cancelTripRequestId)
        assertEquals(operation, panel.disconnectOperation)
        assertFalse(panel.tripActive)
        assertTrue(panel.tripPreparing)
    }
    @Test fun active_trip_hides_the_original_operation_and_generation() {
        val panel = nearbyConversationPanel(view(), NearbySharingView(
            operation = operation, generation = "trip", wanted = true, active = true))
        assertEquals(NearbyTripAction(operation, "trip"), panel.hideTrip)
        assertNull(panel.cancelTripRequestId)
        assertTrue(panel.tripActive)
    }
    @Test fun failed_withdrawn_intent_still_reports_failure() {
        assertEquals(NearbyPanelConnection.FAILED, nearbyConversationPanel(view(
            VoiceSessionState(operationId = operation, wanted = false, phase = VoicePhase.FAILED))).connection)
    }
    @Test fun unrelated_microphone_flags_cannot_render_as_current_controls() {
        val panel = nearbyConversationPanel(view(VoiceSessionState(operationId = VoiceOperationId("other"),
            wanted = true, muted = true, deafened = true)))
        assertFalse(panel.muted)
        assertFalse(panel.deafened)
    }
    @Test fun call_never_offers_trip_even_with_earned_voice() {
        val source = view(VoiceSessionState(operationId = operation, wanted = true)).copy(kind = NearbyConversationKind.CALL)
        val panel = nearbyConversationPanel(source, NearbySharingView(operation = operation, generation = "incoherent", wanted = true, active = true))
        assertNull(panel.startTripOperation)
        assertNull(panel.hideTrip)
        assertFalse(panel.tripActive)
    }
    @Test fun trip_start_requires_earned_voice_and_no_old_cleanup() {
        assertNull(nearbyConversationPanel(view()).startTripOperation)
        val source = view(VoiceSessionState(operationId = operation, wanted = true))
        assertEquals(operation, nearbyConversationPanel(source).startTripOperation)
        assertNull(nearbyConversationPanel(source, NearbySharingView(serverConfirmationPending = true)).startTripOperation)
        assertNull(nearbyConversationPanel(source, NearbySharingView(localCleanupPending = true)).startTripOperation)
    }
}

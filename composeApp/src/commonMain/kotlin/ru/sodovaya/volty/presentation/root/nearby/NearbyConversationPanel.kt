package ru.sodovaya.volty.presentation.root.nearby

import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.voice.VoicePhase

enum class NearbyPanelConnection { HIDDEN, CONNECTING, CONNECTED, RECONNECTING, STOPPING, FAILED }

/** Keep cancellation, permission errors and cleanup reachable even before/after media exists. */
internal fun nearbyConversationPanelVisible(voice: NearbyVoiceView, sharing: NearbySharingView): Boolean {
    val panel = nearbyConversationPanel(voice, sharing)
    return panel.connection != NearbyPanelConnection.HIDDEN || voice.starting != null || voice.pending != null ||
        sharing.pending != null || panel.localCleanupPending || panel.serverConfirmationPending ||
        voice.error != null || sharing.error != null
}
data class NearbyTripAction(val operation: VoiceOperationId, val generation: String)
data class NearbyConversationPanelView(
    val connection: NearbyPanelConnection = NearbyPanelConnection.HIDDEN,
    val disconnectOperation: VoiceOperationId? = null,
    val audioOperation: VoiceOperationId? = null,
    val startTripOperation: VoiceOperationId? = null,
    val muted: Boolean = false, val deafened: Boolean = false,
    val hideTrip: NearbyTripAction? = null,
    val cancelTripRequestId: String? = null,
    val retryTripCleanup: NearbyTripAction? = null,
    val tripActive: Boolean = false, val tripPreparing: Boolean = false,
    val localCleanupPending: Boolean = false, val serverConfirmationPending: Boolean = false,
    val audio: ru.sodovaya.volty.data.voice.VoiceConversationAudioState? = null, val audioEditable: Boolean = false)

internal fun nearbyConversationPanel(voice: NearbyVoiceView, sharing: NearbySharingView = NearbySharingView()): NearbyConversationPanelView {
    val operation = voice.operation
    val evidence = voice.voice?.takeIf { operation != null && it.operationId == operation }
    val connection = when {
        evidence?.phase == VoicePhase.FAILED || voice.phase == NearbyVoiceUiPhase.FAILED -> NearbyPanelConnection.FAILED
        evidence?.phase == VoicePhase.STOPPING || evidence?.cleanupPending == true || evidence?.wanted == false -> NearbyPanelConnection.STOPPING
        evidence?.phase == VoicePhase.RECONNECTING -> NearbyPanelConnection.RECONNECTING
        evidence?.phase == VoicePhase.CONNECTED && evidence.transportConnected -> NearbyPanelConnection.CONNECTED
        operation != null -> NearbyPanelConnection.CONNECTING
        else -> NearbyPanelConnection.HIDDEN
    }
    val trip = sharing.operation?.let { owner -> sharing.generation?.let { NearbyTripAction(owner, it) } }
    val sameConversation = operation != null && sharing.operation == operation && voice.kind == NearbyConversationKind.CHANNEL
    val currentAudio = voice.audio?.takeIf { original -> voice.phase==NearbyVoiceUiPhase.ACTIVE && evidence?.wanted==true && !evidence.cleanupPending &&
            connection!=NearbyPanelConnection.STOPPING && connection!=NearbyPanelConnection.FAILED &&
            original.operation==operation && original.tag.sessionId==evidence.sessionId && original.tag.generation==evidence.generation }
    return NearbyConversationPanelView(
        connection = connection, disconnectOperation = operation,
        audioOperation = operation.takeIf { evidence?.wanted == true && connection != NearbyPanelConnection.STOPPING && connection != NearbyPanelConnection.FAILED },
        startTripOperation = operation.takeIf { evidence?.wanted == true && voice.kind == NearbyConversationKind.CHANNEL &&
            connection != NearbyPanelConnection.STOPPING && connection != NearbyPanelConnection.FAILED &&
            !sharing.wanted && !sharing.preparing && sharing.pending == null &&
            !sharing.localCleanupPending && !sharing.serverConfirmationPending },
        muted = evidence?.muted == true, deafened = evidence?.deafened == true,
        hideTrip = trip.takeIf { sameConversation && sharing.wanted && !sharing.preparing },
        cancelTripRequestId = trip?.generation.takeIf { sameConversation && sharing.wanted && sharing.preparing },
        retryTripCleanup = trip.takeIf { sharing.localCleanupPending || sharing.serverConfirmationPending },
        tripActive = sameConversation && sharing.active, tripPreparing = sameConversation && sharing.preparing,
        localCleanupPending = sharing.localCleanupPending, serverConfirmationPending = sharing.serverConfirmationPending,
        audio = currentAudio, audioEditable = currentAudio!=null && currentAudio.pending==null && evidence?.published==true)
}

package ru.sodovaya.volty.presentation.root.nearby

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import volty.composeapp.generated.resources.*

/** Required callbacks keep screen composition/disposal separate from process ownership. */
class NearbyConversationPanelActions(
    val disconnect: (VoiceOperationId) -> Unit,
    val mute: (VoiceOperationId, Boolean) -> Unit,
    val deafen: (VoiceOperationId, Boolean) -> Unit,
    val startTrip: (VoiceOperationId) -> Unit,
    val hideTrip: (NearbyTripAction) -> Unit,
    val retryTripCleanup: (NearbyTripAction) -> Unit,
    val confirmSwitch: (String) -> Unit,
    val cancelVoiceRequest: (String) -> Unit,
    val cancelSharingRequest: (String) -> Unit,
)

@Composable
internal fun NearbyConversationPanelScreen(
    voice: NearbyVoiceView, sharing: NearbySharingView, expanded: Boolean,
    actions: NearbyConversationPanelActions, modifier: Modifier = Modifier, maxHeight: Dp = 400.dp,
) {
    var detailsExpanded by remember { mutableStateOf(false) }
    val showDetails = expanded || detailsExpanded
    val panel = nearbyConversationPanel(voice, sharing)
    val roster = nearbyChannelRosterPanel(voice)
    val pending = voice.pending
    if (pending != null && pending.confirmationRequired && !pending.confirmed) {
        AlertDialog(onDismissRequest = { actions.cancelVoiceRequest(pending.id) },
            title = { Text(stringResource(Res.string.nearby_panel_switch_title)) },
            text = { Text(stringResource(Res.string.nearby_panel_switch_text, pending.label)) },
            confirmButton = { TextButton(onClick = { actions.confirmSwitch(pending.id) }) {
                Text(stringResource(Res.string.nearby_panel_switch_confirm)) } },
            dismissButton = { TextButton(onClick = { actions.cancelVoiceRequest(pending.id) }) {
                Text(stringResource(Res.string.nearby_cancel)) } })
    }
    val request = voice.starting ?: pending
    if (panel.connection == NearbyPanelConnection.HIDDEN && request == null && sharing.pending == null &&
        !panel.localCleanupPending && !panel.serverConfirmationPending && voice.error == null && sharing.error == null) return
    Surface(modifier.fillMaxWidth(), tonalElevation = 3.dp) {
        Column(Modifier.heightIn(max = minOf(maxHeight,if (showDetails) 400.dp else 280.dp)).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (voice.operation != null) {
                Text(voice.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(panel.connection.message()), style = MaterialTheme.typography.bodySmall)
                if (voice.kind == NearbyConversationKind.CHANNEL) {
                    Text(if(roster.known) stringResource(Res.string.nearby_panel_roster_count,roster.peers.size,roster.connected ?: 0)
                        else stringResource(Res.string.nearby_panel_roster_unknown),style=MaterialTheme.typography.bodySmall)

                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val audio = panel.audioOperation
                    TextButton(enabled = audio != null, onClick = { audio?.let { actions.mute(it, !panel.muted) } }) {
                        Text(stringResource(if (panel.muted) Res.string.nearby_panel_unmute else Res.string.nearby_panel_mute))
                    }
                    panel.disconnectOperation?.let { operation -> TextButton(onClick = { actions.disconnect(operation) }) {
                        Text(stringResource(Res.string.nearby_panel_disconnect), color = MaterialTheme.colorScheme.error)
                    } }
                }
                if (!expanded) TextButton(onClick = {detailsExpanded = !detailsExpanded}) {
                    Text(stringResource(if (detailsExpanded) Res.string.nearby_panel_fewer_controls else Res.string.nearby_panel_more_controls))
                }
                if (showDetails) {
                    panel.audioOperation?.let { operation -> TextButton(onClick = { actions.deafen(operation, !panel.deafened) }) {
                        Text(stringResource(if (panel.deafened) Res.string.nearby_panel_undeafen else Res.string.nearby_panel_deafen))
                    } }
                    panel.startTripOperation?.let { operation -> TextButton(onClick = { actions.startTrip(operation) }) {
                        Text(stringResource(Res.string.nearby_panel_start_position_timed))
                    } }
                }
            }
            request?.let { original -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.nearby_panel_preparing, original.label), Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { actions.cancelVoiceRequest(original.id) }) { Text(stringResource(Res.string.nearby_cancel)) }
            } }
            sharing.pending?.let { original -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(Res.string.nearby_panel_trip_permission), Modifier.weight(1f))
                TextButton(onClick = { actions.cancelSharingRequest(original.id) }) { Text(stringResource(Res.string.nearby_cancel)) }
            } }
            if (panel.tripActive || panel.tripPreparing) Text(stringResource(
                if (panel.tripActive) Res.string.nearby_panel_trip_active else Res.string.nearby_panel_trip_preparing))
            // Always reachable while sharing: hiding GPS must not require disconnecting voice.
            panel.hideTrip?.let { original -> TextButton(onClick = { actions.hideTrip(original) }) {
                Text(stringResource(Res.string.nearby_panel_hide_position))
            } }
            panel.cancelTripRequestId?.let { id -> TextButton(onClick = { actions.cancelSharingRequest(id) }) {
                Text(stringResource(Res.string.nearby_panel_hide_position))
            } }
            if (panel.localCleanupPending) Text(stringResource(Res.string.nearby_panel_local_cleanup))
            if (panel.serverConfirmationPending) Text(stringResource(Res.string.nearby_panel_server_cleanup))
            panel.retryTripCleanup?.let { original -> TextButton(onClick = { actions.retryTripCleanup(original) }) {
                Text(stringResource(Res.string.nearby_retry))
            } }
            voice.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
            sharing.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
            // Participant rows follow disconnect, audio and trip controls.
            if(showDetails && roster.known) {
                roster.peers.take(12).forEach {row ->
                    Text((roster.profileNames[row.peer.actorId] ?: stringResource(Res.string.nearby_panel_roster_peer,row.peer.actorId.take(8))) + " · " +
                        stringResource(if(row.connected) Res.string.nearby_panel_roster_connected else Res.string.nearby_panel_roster_connecting),
                        style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if(roster.peers.size>12) Text(stringResource(Res.string.nearby_panel_roster_more,roster.peers.size-12),
                    style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun NearbyPanelConnection.message(): StringResource = when (this) {
    NearbyPanelConnection.HIDDEN, NearbyPanelConnection.CONNECTING -> Res.string.nearby_voice_connecting
    NearbyPanelConnection.CONNECTED -> Res.string.nearby_panel_transport_connected
    NearbyPanelConnection.RECONNECTING -> Res.string.nearby_panel_reconnecting
    NearbyPanelConnection.STOPPING -> Res.string.nearby_panel_stopping
    NearbyPanelConnection.FAILED -> Res.string.nearby_voice_connection_failed
}
private fun NearbyVoiceError.message(): StringResource = when (this) {
    NearbyVoiceError.MICROPHONE_DENIED -> Res.string.nearby_panel_microphone_denied
    NearbyVoiceError.NOT_VISIBLE -> Res.string.nearby_panel_not_visible
    NearbyVoiceError.CLEANUP_REQUIRED -> Res.string.nearby_panel_cleanup_required
    NearbyVoiceError.UNAVAILABLE -> Res.string.nearby_shell_unavailable
}
private fun NearbySharingError.message(): StringResource = when (this) {
    NearbySharingError.LOCATION_DENIED -> Res.string.nearby_panel_location_denied
    NearbySharingError.NOT_VISIBLE -> Res.string.nearby_panel_not_visible
    NearbySharingError.CLEANUP_REQUIRED -> Res.string.nearby_panel_cleanup_required
    NearbySharingError.UNAVAILABLE -> Res.string.nearby_shell_unavailable
}

package ru.sodovaya.volty.presentation.root.nearby

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.data.voice.VoiceAudioChangeResult
import ru.sodovaya.volty.data.voice.VoiceConversationAudioState
import ru.sodovaya.volty.domain.voice.VoiceAudioPreferences
import ru.sodovaya.volty.domain.voice.VoiceInputPreference
import ru.sodovaya.volty.domain.voice.VoiceOperationId
import ru.sodovaya.volty.domain.voice.VoiceOutputPreference
import ru.sodovaya.volty.domain.nearby.ChannelSharingTtl
import volty.composeapp.generated.resources.*

/** Required callbacks keep screen composition/disposal separate from process ownership. */
class NearbyConversationPanelActions(
    val disconnect: (VoiceOperationId) -> Unit,
    val mute: (VoiceOperationId, Boolean) -> Unit,
    val deafen: (VoiceOperationId, Boolean) -> Unit,
    val configureAudio: (VoiceConversationAudioState, VoiceAudioPreferences) -> Unit,
    val startTrip: (VoiceOperationId, ChannelSharingTtl) -> Unit,
    val hideTrip: (NearbyTripAction) -> Unit,
    val retryTripCleanup: (NearbyTripAction) -> Unit,
    val confirmSwitch: (String) -> Unit,
    val cancelVoiceRequest: (String) -> Unit,
    val cancelSharingRequest: (String) -> Unit,
)

@Composable
internal fun NearbyConversationPanelScreen(
    voice: NearbyVoiceView,
    sharing: NearbySharingView,
    expanded: Boolean,
    actions: NearbyConversationPanelActions,
    modifier: Modifier = Modifier,
    maxHeight: Dp = 400.dp,
    onOpenDetails: () -> Unit = {},
) {
    val panel = nearbyConversationPanel(voice, sharing)
    val roster = nearbyChannelRosterPanel(voice)
    val pending = voice.pending
    var selectedTtl by remember(voice.operation) { mutableStateOf(ChannelSharingTtl.HALF_HOUR) }
    if (pending != null && pending.confirmationRequired && !pending.confirmed) {
        AlertDialog(
            onDismissRequest = { actions.cancelVoiceRequest(pending.id) },
            title = { Text(stringResource(Res.string.nearby_panel_switch_title)) },
            text = { Text(stringResource(Res.string.nearby_panel_switch_text, pending.label)) },
            confirmButton = {
                TextButton(onClick = { actions.confirmSwitch(pending.id) }) {
                    Text(stringResource(Res.string.nearby_panel_switch_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { actions.cancelVoiceRequest(pending.id) }) {
                    Text(stringResource(Res.string.nearby_cancel))
                }
            },
        )
    }

    val request = voice.starting ?: pending
    if (!nearbyConversationPanelVisible(voice, sharing)) return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier else Modifier.padding(horizontal = 12.dp)),
        shape = RoundedCornerShape(if (expanded) 0.dp else 16.dp),
        color = if (expanded) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (expanded) 0.dp else 3.dp,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = if (expanded) maxHeight else minOf(maxHeight, 280.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 24.dp else 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 4.dp),
        ) {
            if (voice.operation != null) {
                ConversationHeader(voice, panel.connection)
                ConversationActions(panel, actions, expanded, onOpenDetails)

                if (expanded && voice.kind == NearbyConversationKind.CHANNEL) {
                    ParticipantsSection(roster)
                }

                SharingCommands(
                    panel = panel,
                    actions = actions,
                    expanded = expanded,
                    selectedTtl = selectedTtl,
                    onSelectTtl = { selectedTtl = it },
                )

                panel.audio?.let { audio ->
                    NearbyAudioStatus(audio)
                    if (expanded) NearbyAudioControls(audio, panel.audioEditable, actions)
                }
            }

            request?.let { original ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        stringResource(Res.string.nearby_panel_preparing, original.label),
                        Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { actions.cancelVoiceRequest(original.id) }) {
                        Text(stringResource(Res.string.nearby_cancel))
                    }
                }
            }

            sharing.pending?.let { original ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.nearby_panel_trip_permission), Modifier.weight(1f))
                    TextButton(onClick = { actions.cancelSharingRequest(original.id) }) {
                        Text(stringResource(Res.string.nearby_cancel))
                    }
                }
            }

            if (panel.localCleanupPending) Text(stringResource(Res.string.nearby_panel_local_cleanup))
            if (panel.serverConfirmationPending) Text(stringResource(Res.string.nearby_panel_server_cleanup))
            panel.retryTripCleanup?.let { original ->
                TextButton(onClick = { actions.retryTripCleanup(original) }) {
                    Text(stringResource(Res.string.nearby_retry))
                }
            }
            voice.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
            sharing.error?.let { Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ConversationHeader(voice: NearbyVoiceView, connection: NearbyPanelConnection) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            voice.label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            stringResource(connection.message()),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConversationActions(
    panel: NearbyConversationPanelView,
    actions: NearbyConversationPanelActions,
    expanded: Boolean,
    onOpenDetails: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val audio = panel.audioOperation
        IconButton(
            enabled = audio != null,
            modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
            onClick = { audio?.let { actions.mute(it, !panel.muted) } },
        ) {
            Icon(
                if (panel.muted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = stringResource(
                    if (panel.muted) Res.string.nearby_panel_unmute else Res.string.nearby_panel_mute,
                ),
            )
        }

        if (expanded) {
            panel.audioOperation?.let { operation ->
                IconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = { actions.deafen(operation, !panel.deafened) },
                ) {
                    Icon(
                        if(panel.deafened) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription=stringResource(
                            if (panel.deafened) Res.string.nearby_panel_undeafen else Res.string.nearby_panel_deafen,
                        ),
                    )
                }
            }
        } else {
            IconButton(
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                onClick = onOpenDetails,
            ) {
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = stringResource(Res.string.nearby_panel_more_controls),
                )
            }
        }

        Spacer(Modifier.weight(1f))
        panel.disconnectOperation?.let { operation ->
            Button(
                modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                onClick = { actions.disconnect(operation) },
            ) {
                Text(stringResource(Res.string.nearby_panel_disconnect), maxLines = 1)
            }
        }
    }
}

@Composable
private fun ParticipantsSection(roster: NearbyChannelRosterPanel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.nearby_panel_participants), style = MaterialTheme.typography.titleSmall)
        if (roster.known) {
            Text(
                stringResource(Res.string.nearby_panel_roster_count, roster.peers.size, roster.connected ?: 0),
                style = MaterialTheme.typography.bodySmall,
            )
            roster.peers.take(12).forEach { row ->
                Text(
                    (roster.profileNames[row.peer.actorId]
                        ?: stringResource(Res.string.nearby_panel_roster_peer, row.peer.actorId.take(8))) +
                        " · " + stringResource(
                            if (row.connected) Res.string.nearby_panel_roster_connected
                            else Res.string.nearby_panel_roster_connecting,
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (roster.peers.size > 12) {
                Text(
                    stringResource(Res.string.nearby_panel_roster_more, roster.peers.size - 12),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(stringResource(Res.string.nearby_panel_roster_unknown), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SharingCommands(
    panel: NearbyConversationPanelView,
    actions: NearbyConversationPanelActions,
    expanded: Boolean,
    selectedTtl: ChannelSharingTtl,
    onSelectTtl: (ChannelSharingTtl) -> Unit,
) {
    Column(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (panel.tripActive || panel.tripPreparing || panel.hideTrip != null || panel.cancelTripRequestId != null) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (panel.tripActive || panel.tripPreparing) {
                    Text(
                        stringResource(
                            if (panel.tripActive) Res.string.nearby_panel_trip_active
                            else Res.string.nearby_panel_trip_preparing,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // Always reachable while sharing: hiding GPS must not require disconnecting voice.
                panel.hideTrip?.let { original ->
                    TextButton(onClick = { actions.hideTrip(original) }) {
                        Text(stringResource(Res.string.nearby_panel_hide_position))
                    }
                }
                panel.cancelTripRequestId?.let { id ->
                    TextButton(onClick = { actions.cancelSharingRequest(id) }) {
                        Text(stringResource(Res.string.nearby_panel_hide_position))
                    }
                }
            }
        }

        if (expanded) {
            panel.startTripOperation?.let { operation ->
                Text(stringResource(Res.string.nearby_panel_duration), style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        ChannelSharingTtl.HALF_HOUR to Res.string.nearby_panel_duration_half_hour,
                        ChannelSharingTtl.TWO_HOURS to Res.string.nearby_panel_duration_two_hours,
                        ChannelSharingTtl.EIGHT_HOURS to Res.string.nearby_panel_duration_eight_hours,
                    ).forEach { (ttl, label) ->
                        FilterChip(
                            selected = selectedTtl == ttl,
                            onClick = { onSelectTtl(ttl) },
                            label = { Text(stringResource(label)) },
                        )
                    }
                }
                Button(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    onClick = { actions.startTrip(operation, selectedTtl) },
                ) {
                    Text(stringResource(Res.string.nearby_panel_share_position))
                }
            }
        }
    }
}

@Composable
internal fun nearbyConversationStatusText(voice: NearbyVoiceView, sharing: NearbySharingView): String {
    val panel=nearbyConversationPanel(voice,sharing)
    return when {
        voice.operation!=null -> stringResource(panel.connection.message())
        voice.error!=null -> stringResource(voice.error.message())
        sharing.error!=null -> stringResource(sharing.error.message())
        panel.localCleanupPending -> stringResource(Res.string.nearby_panel_local_cleanup)
        panel.serverConfirmationPending -> stringResource(Res.string.nearby_panel_server_cleanup)
        voice.starting!=null || voice.pending!=null -> stringResource(Res.string.nearby_panel_preparing,
            (voice.starting ?: voice.pending)!!.label)
        sharing.pending!=null -> stringResource(Res.string.nearby_panel_trip_permission)
        else -> stringResource(Res.string.nearby_panel_more_controls)
    }
}

internal fun NearbyPanelConnection.message(): StringResource = when (this) {
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

@Composable
private fun NearbyAudioStatus(original: VoiceConversationAudioState) {
    if (original.pending != null) {
        Text(stringResource(Res.string.nearby_audio_pending), style = MaterialTheme.typography.bodySmall)
    }
    val message = when (original.lastResult) {
        VoiceAudioChangeResult.UNAVAILABLE -> Res.string.nearby_audio_unavailable
        VoiceAudioChangeResult.REQUIRES_RECONNECT -> Res.string.nearby_audio_reconnect
        VoiceAudioChangeResult.FAILED -> Res.string.nearby_audio_failed
        VoiceAudioChangeResult.STALE -> Res.string.nearby_audio_stale
        else -> null
    }
    message?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun NearbyAudioControls(
    original: VoiceConversationAudioState,
    enabled: Boolean,
    actions: NearbyConversationPanelActions,
) {
    Text(stringResource(Res.string.nearby_audio_input), style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VoiceInputPreference.entries.forEach { input ->
            FilterChip(
                selected = original.preferences.input == input,
                enabled = enabled,
                onClick = { actions.configureAudio(original, original.preferences.copy(input = input)) },
                label = {
                    Text(stringResource(when (input) {
                        VoiceInputPreference.AUTO -> Res.string.nearby_audio_auto
                        VoiceInputPreference.PHONE -> Res.string.nearby_audio_phone
                        VoiceInputPreference.HEADSET -> Res.string.nearby_audio_headset
                    }))
                },
            )
        }
    }
    Text(stringResource(Res.string.nearby_audio_output), style = MaterialTheme.typography.labelMedium)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        VoiceOutputPreference.entries.forEach { output ->
            FilterChip(
                selected = original.preferences.output == output,
                enabled = enabled,
                onClick = { actions.configureAudio(original, original.preferences.copy(output = output)) },
                label = {
                    Text(stringResource(when (output) {
                        VoiceOutputPreference.AUTO -> Res.string.nearby_audio_auto
                        VoiceOutputPreference.HEADSET -> Res.string.nearby_audio_headset
                        VoiceOutputPreference.SPEAKER -> Res.string.nearby_audio_speaker
                    }))
                },
            )
        }
    }
}

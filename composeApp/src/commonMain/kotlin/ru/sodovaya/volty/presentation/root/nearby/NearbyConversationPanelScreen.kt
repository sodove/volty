package ru.sodovaya.volty.presentation.root.nearby

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import ru.sodovaya.volty.domain.audio.MAX_LISTEN_GAIN_DB
import ru.sodovaya.volty.domain.audio.MIN_LISTEN_GAIN_DB
import ru.sodovaya.volty.domain.audio.VoiceListenSettings
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import kotlin.math.roundToInt
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
    listen: VoiceListenSettings = VoiceListenSettings(),
    onParticipantGain: (actorId: String, db: Float) -> Unit = { _, _ -> },
) {
    val panel = nearbyConversationPanel(voice, sharing)
    val roster = nearbyChannelRosterPanel(voice)
    val pending = voice.pending
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
            if (voice.operation != null && expanded) {
                ExpandedConversation(voice, sharing, panel, roster, actions, listen, onParticipantGain)
            } else if (voice.operation != null) {
                ConversationHeader(voice, panel.connection)
                ConversationActions(panel, actions, expanded = false, onOpenDetails)
                CollapsedTripRow(panel = panel, actions = actions)

                panel.audio?.let { audio -> NearbyAudioStatus(audio) }
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

            // Expanded shows these waits inside its status line instead of separate rows.
            val inlineNotices = expanded && voice.operation != null
            if (panel.localCleanupPending && !inlineNotices) Text(stringResource(Res.string.nearby_panel_local_cleanup))
            if (panel.serverConfirmationPending && !inlineNotices) Text(stringResource(Res.string.nearby_panel_server_cleanup))
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
private fun ExpandedConversation(
    voice: NearbyVoiceView,
    sharing: NearbySharingView,
    panel: NearbyConversationPanelView,
    roster: NearbyChannelRosterPanel,
    actions: NearbyConversationPanelActions,
    listen: VoiceListenSettings,
    onParticipantGain: (String, Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(voice.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(panel.connection.dotColor())
                Text(
                    expandedStatusLine(panel, roster, voice.kind == NearbyConversationKind.CHANNEL),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        panel.audio?.let { audio -> AudioSettingsMenu(audio, panel.audioEditable, actions) }
    }
    ConversationActions(panel, actions, expanded = true, onOpenDetails = {})
    panel.audio?.let { audio -> NearbyAudioStatus(audio, showPending = false) }
    if (voice.kind == NearbyConversationKind.CHANNEL) ParticipantRows(roster, listen, onParticipantGain)
    PositionRow(panel, sharing, actions)
}

@Composable
private fun expandedStatusLine(panel: NearbyConversationPanelView, roster: NearbyChannelRosterPanel, channel: Boolean): String {
    val connection = stringResource(panel.connection.message())
    val base = if (channel && roster.known && panel.connection == NearbyPanelConnection.CONNECTED)
        stringResource(Res.string.nearby_panel_status_count, connection, roster.peers.size) else connection
    val notice = when (nearbyPanelNotice(panel)) {
        NearbyPanelNotice.NONE -> return base
        NearbyPanelNotice.LOCAL_CLEANUP -> Res.string.nearby_panel_notice_local_cleanup
        NearbyPanelNotice.SERVER_CLEANUP -> Res.string.nearby_panel_notice_server_cleanup
        NearbyPanelNotice.PREPARING_POSITION -> Res.string.nearby_panel_notice_position
        NearbyPanelNotice.AUDIO_CHANGING -> Res.string.nearby_panel_notice_audio
    }
    return "$connection · ${stringResource(notice)}"
}

private val ConnectedGreen = Color(0xFF6FCF7F)
private val WaitingAmber = Color(0xFFE8C26B)

@Composable
private fun NearbyPanelConnection.dotColor(): Color = when (this) {
    NearbyPanelConnection.CONNECTED -> ConnectedGreen
    NearbyPanelConnection.FAILED -> MaterialTheme.colorScheme.error
    NearbyPanelConnection.STOPPING, NearbyPanelConnection.HIDDEN -> MaterialTheme.colorScheme.outline
    NearbyPanelConnection.CONNECTING, NearbyPanelConnection.RECONNECTING -> WaitingAmber
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParticipantRows(
    roster: NearbyChannelRosterPanel,
    listen: VoiceListenSettings,
    onParticipantGain: (String, Float) -> Unit,
) {
    var adjusting by remember { mutableStateOf<String?>(null) }
    if (!roster.known) {
        Text(stringResource(Res.string.nearby_panel_roster_unknown), style = MaterialTheme.typography.bodySmall)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        roster.peers.take(12).forEach { row ->
            val name = roster.profileNames[row.peer.actorId]
                ?: stringResource(Res.string.nearby_panel_roster_peer, row.peer.actorId.take(4))
            Row(
                // Long press opens this person's volume; a tap does nothing, so it cannot be hit by accident.
                Modifier.combinedClickable(onClick = {}, onLongClick = { adjusting = row.peer.actorId }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = stringResource(
                    if (row.connected) Res.string.nearby_panel_roster_connected else Res.string.nearby_panel_roster_connecting,
                )
                Box(Modifier.semantics { contentDescription = status }) {
                    StatusDot(if (row.connected) ConnectedGreen else WaitingAmber)
                }
            }
        }
        adjusting?.let { actorId ->
            val name = roster.profileNames[actorId]
                ?: stringResource(Res.string.nearby_panel_roster_peer, actorId.take(4))
            val db = listen.participantGainDb[actorId] ?: 0f
            AlertDialog(
                onDismissRequest = { adjusting = null },
                title = { Text(stringResource(Res.string.nearby_participant_volume, name)) },
                text = {
                    Column {
                        Text(stringResource(Res.string.nearby_participant_volume_db, db.roundToInt()))
                        Slider(
                            value = db.coerceIn(MIN_LISTEN_GAIN_DB, MAX_LISTEN_GAIN_DB),
                            onValueChange = { onParticipantGain(actorId, it.roundToInt().toFloat()) },
                            valueRange = MIN_LISTEN_GAIN_DB..MAX_LISTEN_GAIN_DB,
                            steps = (MAX_LISTEN_GAIN_DB - MIN_LISTEN_GAIN_DB).roundToInt() - 1,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { adjusting = null }) { Text(stringResource(Res.string.nearby_participant_volume_done)) } },
                dismissButton = { TextButton(onClick = { onParticipantGain(actorId, 0f) }) { Text(stringResource(Res.string.nearby_participant_volume_reset)) } },
            )
        }
        if (roster.peers.size > 12) {
            Text(stringResource(Res.string.nearby_panel_roster_more, roster.peers.size - 12), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PositionRow(panel: NearbyConversationPanelView, sharing: NearbySharingView, actions: NearbyConversationPanelActions) {
    val row = nearbyPositionRow(panel)
    if (!row.visible) return
    var choosingDuration by remember(panel.startTripOperation) { mutableStateOf(false) }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Default.Place, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.nearby_panel_position), style = MaterialTheme.typography.bodyLarge)
            when {
                panel.tripPreparing -> Text(stringResource(Res.string.nearby_panel_trip_preparing), style = MaterialTheme.typography.bodySmall)
                panel.tripActive -> Text(
                    sharing.ttl?.let { stringResource(Res.string.nearby_panel_position_visible, stringResource(it.label())) }
                        ?: stringResource(Res.string.nearby_panel_trip_active),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Box {
            // Hiding stays one tap and never requires leaving voice.
            Switch(
                checked = row.checked,
                enabled = row.enabled,
                onCheckedChange = { on ->
                    if (on) choosingDuration = true
                    else panel.hideTrip?.let(actions.hideTrip) ?: panel.cancelTripRequestId?.let(actions.cancelSharingRequest)
                },
            )
            DropdownMenu(expanded = choosingDuration, onDismissRequest = { choosingDuration = false }) {
                ChannelSharingTtl.entries.forEach { ttl ->
                    DropdownMenuItem(
                        text = { Text(stringResource(ttl.label())) },
                        onClick = {
                            choosingDuration = false
                            panel.startTripOperation?.let { operation -> actions.startTrip(operation, ttl) }
                        },
                    )
                }
            }
        }
    }
}

private fun ChannelSharingTtl.label(): StringResource = when (this) {
    ChannelSharingTtl.HALF_HOUR -> Res.string.nearby_panel_duration_half_hour
    ChannelSharingTtl.TWO_HOURS -> Res.string.nearby_panel_duration_two_hours
    ChannelSharingTtl.EIGHT_HOURS -> Res.string.nearby_panel_duration_eight_hours
}

/** Collapsed bar keeps its previous behaviour: a visible way to stop sharing position. */
@Composable
private fun CollapsedTripRow(panel: NearbyConversationPanelView, actions: NearbyConversationPanelActions) {
    if (!(panel.tripActive || panel.tripPreparing || panel.hideTrip != null || panel.cancelTripRequestId != null)) return
    Row(
        Modifier.fillMaxWidth().animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (panel.tripActive || panel.tripPreparing) {
            Text(
                stringResource(if (panel.tripActive) Res.string.nearby_panel_trip_active else Res.string.nearby_panel_trip_preparing),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        panel.hideTrip?.let { original ->
            TextButton(onClick = { actions.hideTrip(original) }) { Text(stringResource(Res.string.nearby_panel_hide_position)) }
        }
        panel.cancelTripRequestId?.let { id ->
            TextButton(onClick = { actions.cancelSharingRequest(id) }) { Text(stringResource(Res.string.nearby_panel_hide_position)) }
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
private fun NearbyAudioStatus(original: VoiceConversationAudioState, showPending: Boolean = true) {
    if (showPending && original.pending != null) {
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

/** Device choice is rare mid-ride, so it lives behind one icon instead of two always-visible rows. */
@Composable
private fun AudioSettingsMenu(
    original: VoiceConversationAudioState,
    enabled: Boolean,
    actions: NearbyConversationPanelActions,
) {
    var open by remember(original.operation) { mutableStateOf(false) }
    Box {
        IconButton(modifier = Modifier.size(48.dp), onClick = { open = true }) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.nearby_panel_audio_settings))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuSection(Res.string.nearby_audio_input)
            VoiceInputPreference.entries.forEach { input ->
                MenuChoice(
                    label = when (input) {
                        VoiceInputPreference.AUTO -> Res.string.nearby_audio_auto
                        VoiceInputPreference.PHONE -> Res.string.nearby_audio_phone
                        VoiceInputPreference.HEADSET -> Res.string.nearby_audio_headset
                    },
                    selected = original.preferences.input == input,
                    enabled = enabled,
                ) {
                    open = false
                    actions.configureAudio(original, original.preferences.copy(input = input))
                }
            }
            HorizontalDivider()
            MenuSection(Res.string.nearby_audio_output)
            VoiceOutputPreference.entries.forEach { output ->
                MenuChoice(
                    label = when (output) {
                        VoiceOutputPreference.AUTO -> Res.string.nearby_audio_auto
                        VoiceOutputPreference.HEADSET -> Res.string.nearby_audio_headset
                        VoiceOutputPreference.SPEAKER -> Res.string.nearby_audio_speaker
                    },
                    selected = original.preferences.output == output,
                    enabled = enabled,
                ) {
                    open = false
                    actions.configureAudio(original, original.preferences.copy(output = output))
                }
            }
        }
    }
}

@Composable
private fun MenuSection(title: StringResource) {
    Text(
        stringResource(title),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MenuChoice(label: StringResource, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        enabled = enabled,
        trailingIcon = { if (selected) Icon(Icons.Default.Check, contentDescription = null) },
        onClick = onClick,
    )
}

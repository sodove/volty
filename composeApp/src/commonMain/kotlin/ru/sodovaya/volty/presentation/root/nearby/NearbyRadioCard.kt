package ru.sodovaya.volty.presentation.root.nearby

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import ru.sodovaya.volty.domain.radio.RadioError
import ru.sodovaya.volty.domain.radio.RadioMode
import ru.sodovaya.volty.domain.radio.RadioPanelHost
import volty.composeapp.generated.resources.*

private fun RadioError.message(): StringResource = when (this) {
    RadioError.UNSUPPORTED_ANDROID -> Res.string.radio_unsupported
    RadioError.NO_KEY -> Res.string.radio_no_key
    RadioError.STALE_KEY -> Res.string.radio_stale_key
    RadioError.NOT_FOUND -> Res.string.radio_not_found
    RadioError.FULL -> Res.string.radio_full
    RadioError.WIFI_FAILED -> Res.string.radio_wifi_failed
    RadioError.AUDIO_UNAVAILABLE -> Res.string.radio_audio_unavailable
}

private fun RadioAction.label(): StringResource = when (this) {
    RadioAction.RAISE -> Res.string.radio_raise
    RadioAction.JOIN -> Res.string.radio_join
    RadioAction.LEAVE -> Res.string.radio_leave
    RadioAction.RERAISE -> Res.string.radio_reraise
}

private fun RadioMode.label(): StringResource = when (this) {
    RadioMode.LOW_LATENCY -> Res.string.radio_mode_low_latency
    RadioMode.BALANCED -> Res.string.radio_mode_balanced
    RadioMode.RELIABLE -> Res.string.radio_mode_reliable
}

private fun RadioMode.hint(): StringResource = when (this) {
    RadioMode.LOW_LATENCY -> Res.string.radio_mode_low_latency_hint
    RadioMode.BALANCED -> Res.string.radio_mode_balanced_hint
    RadioMode.RELIABLE -> Res.string.radio_mode_reliable_hint
}

private fun perform(host: RadioPanelHost, action: RadioAction, community: String) = when (action) {
    RadioAction.RAISE -> host.raise(community)
    RadioAction.JOIN -> host.join(community)
    RadioAction.LEAVE -> host.leave()
    RadioAction.RERAISE -> host.reRaise()
}

/**
 * The offline radio of one Volty server. Renders [radioPanelState] and forwards taps to the host;
 * the only logic here is the permission round trip, which can only be judged on a device.
 */
@Composable
fun NearbyRadioCard(community: String, modifier: Modifier = Modifier, host: RadioPanelHost = koinInject()) {
    val state by host.state.collectAsState()
    val roster by host.roster.collectAsState()
    val mode by host.mode.collectAsState()
    val autoJoin by host.autoJoin.collectAsState()
    val hasDiagnostics by host.hasDiagnostics.collectAsState()
    val panel = radioPanelState(state, roster, host.supported, community, mode, hasDiagnostics)

    // What the rider asked for while a permission dialog is open: an action, or turning auto-join on.
    var pendingAction by remember { mutableStateOf<RadioAction?>(null) }
    var pendingAutoJoin by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pendingAction
        val wantsAutoJoin = pendingAutoJoin
        pendingAction = null
        pendingAutoJoin = false
        if (action != null) {
            if (host.missingPermissions(scanning = false).isEmpty()) perform(host, action, community) else denied = true
        }
        if (wantsAutoJoin && host.missingPermissions(scanning = true).isEmpty()) host.setAutoJoin(true)
    }

    fun tap(action: RadioAction) {
        denied = false
        val needsPermissions = action == RadioAction.RAISE || action == RadioAction.JOIN
        val missing = if (needsPermissions) host.missingPermissions(scanning = false) else emptyList()
        if (missing.isEmpty()) {
            perform(host, action, community)
        } else {
            pendingAction = action
            launcher.launch(missing.toTypedArray())
        }
    }

    // Retraction: the card used to list every mode with its hint and the auto-join switch with two
    // hints, always open, and took half the server screen. Status and actions stay on top; the
    // settings rarely touched mid-ride fold under the chevron.
    var expanded by remember { mutableStateOf(false) }
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when (panel.title) {
                            RadioTitle.UNSUPPORTED -> stringResource(Res.string.radio_unsupported)
                            RadioTitle.STARTING, RadioTitle.CONNECTING -> stringResource(Res.string.radio_starting)
                            RadioTitle.MEMBERS -> stringResource(Res.string.radio_members, panel.members.size)
                            RadioTitle.LOST -> stringResource(Res.string.radio_lost)
                            RadioTitle.ELSEWHERE -> stringResource(Res.string.radio_elsewhere)
                            RadioTitle.IDLE, RadioTitle.ERROR -> stringResource(Res.string.radio_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (panel.members.isNotEmpty()) Text(
                        panel.members.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (host.supported) IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = stringResource(Res.string.nearby_panel_more_controls),
                    )
                }
            }
            // The unsupported title already says it; every other error is shown under the heading.
            if (panel.title != RadioTitle.UNSUPPORTED) panel.error?.let {
                Text(stringResource(it.message()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (denied) Text(stringResource(Res.string.radio_permission_denied), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (panel.primary != null || panel.secondary != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                panel.primary?.let { Button(onClick = { tap(it) }) { Text(stringResource(it.label())) } }
                panel.secondary?.let { OutlinedButton(onClick = { tap(it) }) { Text(stringResource(it.label())) } }
            }
            if (expanded && host.supported) {
                if (panel.modePicker == RadioModePicker.EDITABLE || (panel.modePicker == RadioModePicker.READ_ONLY && panel.shownMode != null)) {
                    val editable = panel.modePicker == RadioModePicker.EDITABLE
                    val shown = if (editable) mode else panel.shownMode ?: mode
                    var modeMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.fillMaxWidth().let { if (editable) it.clickable { modeMenu = true } else it }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(Res.string.radio_mode), style = MaterialTheme.typography.labelLarge)
                                Text(stringResource(shown.label()), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    stringResource(shown.hint()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (editable) Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            RadioMode.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(option.label())) },
                                    onClick = { modeMenu = false; host.setMode(option) },
                                )
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(Res.string.radio_auto_join), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(Res.string.radio_auto_join_battery),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(Res.string.radio_auto_join_location),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoJoin, onCheckedChange = { on ->
                        if (!on) {
                            host.setAutoJoin(false)
                        } else {
                            val missing = host.missingPermissions(scanning = true)
                            if (missing.isEmpty()) {
                                host.setAutoJoin(true)
                            } else {
                                pendingAutoJoin = true
                                launcher.launch(missing.toTypedArray())
                            }
                        }
                    })
                }
                if (panel.canExportDiagnostics) TextButton(onClick = host::exportDiagnostics) {
                    Text(stringResource(Res.string.radio_export_diagnostics))
                }
            }
        }
    }
}

/** Compact radio status for the bottom area; draws nothing unless a radio is starting, running or lost. */
@Composable
fun NearbyRadioStatusLine(modifier: Modifier = Modifier, host: RadioPanelHost = koinInject()) {
    val state by host.state.collectAsState()
    val roster by host.roster.collectAsState()
    val panel = radioPanelState(state, roster, host.supported)
    val title = radioStatusTitle(panel) ?: return
    Text(
        when (title) {
            RadioTitle.MEMBERS -> stringResource(Res.string.radio_members, panel.members.size)
            RadioTitle.LOST -> stringResource(Res.string.radio_lost)
            else -> stringResource(Res.string.radio_starting)
        },
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = if (title == RadioTitle.LOST) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
    )
}

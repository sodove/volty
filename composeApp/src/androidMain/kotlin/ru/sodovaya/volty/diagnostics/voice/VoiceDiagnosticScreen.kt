package ru.sodovaya.volty.diagnostics.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ru.sodovaya.volty.R
import ru.sodovaya.volty.data.voice.*
import ru.sodovaya.volty.domain.voice.*

@Composable
fun VoiceDiagnosticScreen(
    state: VoiceDiagnosticUiState,
    permissionError: Boolean,
    onStart: (String, VoiceAudioPreferences) -> Unit,
    onStop: () -> Unit,
    onReload: () -> Unit,
    onExport: () -> Unit,
    onMuted: (Boolean) -> Unit,
    onDeafened: (Boolean) -> Unit,
) {
    var group by remember { mutableStateOf<String?>(null) }
    var input by remember { mutableStateOf(VoiceInputPreference.AUTO) }
    var output by remember { mutableStateOf(VoiceOutputPreference.AUTO) }
    val editable = state.phase == VoiceDiagnosticPhase.IDLE || state.phase == VoiceDiagnosticPhase.ERROR
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.voice_diag_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.voice_diag_description))
                Text(stringResource(when (state.phase) {
                    VoiceDiagnosticPhase.IDLE -> R.string.voice_diag_idle
                    VoiceDiagnosticPhase.CONNECTING -> R.string.voice_diag_connecting
                    VoiceDiagnosticPhase.RUNNING -> R.string.voice_diag_running
                    VoiceDiagnosticPhase.STOPPING -> R.string.voice_diag_stopping
                    VoiceDiagnosticPhase.ERROR -> R.string.voice_diag_failed
                    VoiceDiagnosticPhase.CLEANUP_FAILED -> R.string.voice_diag_cleanup_failed
                }))
                if (permissionError) Text(stringResource(R.string.voice_diag_permission), color = MaterialTheme.colorScheme.error)
                state.error?.let { Text(if (it == VoiceEngineError.LEGACY_ACTIVE) stringResource(R.string.voice_diag_legacy_active) else it.name,
                    color = MaterialTheme.colorScheme.error) }
                if (state.groups.isEmpty()) Text(stringResource(R.string.voice_diag_empty))
                state.groups.forEach { candidate ->
                    Row {
                        RadioButton(group == candidate.id, { group = candidate.id }, enabled = editable)
                        Text(candidate.name, modifier = Modifier.padding(top = 12.dp))
                    }
                }
                TextButton(onClick = onReload, enabled = editable) { Text(stringResource(R.string.voice_diag_reload)) }
                Text(stringResource(R.string.voice_diag_input))
                VoiceInputPreference.entries.forEach { candidate ->
                    Row {
                        RadioButton(input == candidate, { input = candidate }, enabled = editable)
                        Text(stringResource(when (candidate) {
                            VoiceInputPreference.AUTO -> R.string.voice_diag_auto
                            VoiceInputPreference.PHONE -> R.string.voice_diag_phone
                            VoiceInputPreference.HEADSET -> R.string.voice_diag_headset
                        }), Modifier.padding(top = 12.dp))
                    }
                }
                Text(stringResource(R.string.voice_diag_output))
                VoiceOutputPreference.entries.forEach { candidate ->
                    Row {
                        RadioButton(output == candidate, { output = candidate }, enabled = editable)
                        Text(stringResource(when (candidate) {
                            VoiceOutputPreference.AUTO -> R.string.voice_diag_auto
                            VoiceOutputPreference.HEADSET -> R.string.voice_diag_headset
                            VoiceOutputPreference.SPEAKER -> R.string.voice_diag_speaker
                        }), Modifier.padding(top = 12.dp))
                    }
                }
                Button(onClick = { group?.let { onStart(it, VoiceAudioPreferences(input, output)) } },
                    enabled = editable && state.groups.any { it.id == group }) { Text(stringResource(R.string.voice_diag_start)) }
                Button(onClick = onStop, enabled = !editable && state.phase != VoiceDiagnosticPhase.STOPPING) { Text(stringResource(R.string.voice_diag_stop)) }
                Row {
                    Checkbox(state.muted, onMuted, enabled = state.phase == VoiceDiagnosticPhase.RUNNING)
                    Text(stringResource(R.string.voice_diag_mute), Modifier.padding(top = 12.dp))
                }
                Row {
                    Checkbox(state.deafened, onDeafened, enabled = state.phase == VoiceDiagnosticPhase.RUNNING)
                    Text(stringResource(R.string.voice_diag_deafen), Modifier.padding(top = 12.dp))
                }
                Text(stringResource(R.string.voice_diag_route_unknown))
                state.diagnostics?.let { diagnostics ->
                    val snapshot by diagnostics.state.collectAsState()
                    Text(snapshot.formatVoiceDiagnostics(), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onExport) { Text(stringResource(R.string.voice_diag_export)) }
                }
            }
        }
    }
}

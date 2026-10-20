package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.resolveHistoryMetricPreset
import volty.composeapp.generated.resources.*

/** Small bounded set of saved layouts, with no per-preset series reads. */
@Composable
fun HistoryMetricPresetsPanel(detail: HistoryDetailState, state: HistoryPresetsState,
    onLoad: () -> Unit, onSave: (String) -> Unit, onApply: (String) -> Unit, onDelete: (String) -> Unit) {
    if (!state.available) return
    var expanded by remember(detail.rideId) { mutableStateOf(false) }
    var name by remember(detail.rideId) { mutableStateOf(TextFieldValue()) }
    val ride = detail.ride
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        TextButton(onClick = { expanded = !expanded; if (expanded && !state.busy) onLoad() }) {
            Text(stringResource(Res.string.history_presets_title), Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) {
            Text(stringResource(Res.string.history_presets_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
            if (state.error != null) {
                Text(stringResource(when (state.error) {
                    HistoryPresetError.READ -> Res.string.history_presets_read_error
                    HistoryPresetError.SAVE -> Res.string.history_presets_save_error
                    HistoryPresetError.DELETE -> Res.string.history_presets_delete_error
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onLoad, enabled = !state.busy) { Text(stringResource(Res.string.history_presets_reload)) }
            }
            val presets = state.presets.filter { it.vehicleId == ride?.vehicleId }
            if (state.loaded && presets.isEmpty()) Text(stringResource(Res.string.history_presets_empty),
                Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            for (preset in presets) {
                val resolution = resolveHistoryMetricPreset(preset, checkNotNull(ride).vehicleId, detail.sources)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onApply(preset.id) },
                        enabled = !state.busy && (resolution.keys.isNotEmpty() || preset.metrics.isEmpty()), modifier = Modifier.weight(1f)) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(preset.name, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(Res.string.history_presets_availability, resolution.keys.size, resolution.unavailable.size),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = { onDelete(preset.id) }, enabled = !state.busy) {
                        Icon(Icons.Default.Close, stringResource(Res.string.history_presets_delete, preset.name))
                    }
                }
            }
            OutlinedTextField(value = name, onValueChange = { if (it.text.length <= 80) name = it }, singleLine = true,
                label = { Text(stringResource(Res.string.history_presets_name)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            TextButton(onClick = { onSave(name.text) }, enabled = ride != null && !state.busy && name.text.isNotBlank() &&
                detail.selectedKeys.isNotEmpty() && detail.selectedKeys.size <= 128) { Text(stringResource(Res.string.history_presets_save)) }
            if (detail.selectedKeys.size > 128) Text(stringResource(Res.string.history_presets_metric_limit),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

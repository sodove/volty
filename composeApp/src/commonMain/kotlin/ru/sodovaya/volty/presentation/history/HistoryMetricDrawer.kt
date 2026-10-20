package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import volty.composeapp.generated.resources.*

/** Local editor state stays synchronous; storage/selection commands belong to the owner. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryMetricDrawer(
    state: HistoryDetailState,
    onLoadSource: (String) -> Unit,
    onAdd: (HistorySeriesKey) -> Unit,
    onRemove: (HistorySeriesKey) -> Unit,
    onDismiss: () -> Unit,
    reduceMotion: Boolean = false,
    presets: HistoryPresetsState = HistoryPresetsState(),
    onLoadPresets: () -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    onApplyPreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit = {},
) {
    var query by remember(state.rideId) { mutableStateOf(TextFieldValue()) }
    var expanded by remember(state.rideId) { mutableStateOf(emptySet<String>()) }
    val text = historyMetricText()
    val sourceLabels = historySourceLabels(state.sources)
    val selected = state.selectedKeys.toSet()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { if (WindowInsets.ime.getBottom(LocalDensity.current) == 0) BottomSheetDefaults.DragHandle() },
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding()) {
            val compact = maxHeight < 280.dp
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (!compact) item("heading") {
                    Column {
                        Text(stringResource(Res.string.history_metrics_title), Modifier.padding(horizontal = 24.dp),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(Res.string.history_metrics_hint), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item("query") {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        label = { Text(stringResource(Res.string.history_metric_search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 4.dp else 8.dp))
                }
                if (presets.available) item("presets") {
                    HistoryMetricPresetsPanel(state, presets, onLoadPresets, onSavePreset, onApplyPreset, onDeletePreset)
                }
                if (state.sources.isEmpty()) item("empty") {
                    Text(stringResource(if (state.loading) Res.string.history_metrics_loading else Res.string.history_metrics_empty),
                        Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                var shownSources = 0
                val searching = query.text.isNotBlank()
                state.sources.forEach { source ->
                    val open = source.id in expanded
                    val recorded = state.recordedKeys[source.id]
                    val caption = "${sourceLabels.getValue(source.id)} ${text.kinds.getValue(source.kind)}"
                    val options = if (open || searching) historyMetricOptions(
                        historyMetricPickerKeys(recorded?.keys.orEmpty(), state.selectedKeys, source.id).map { option ->
                            val group = HistoryMetricRegistry.descriptor(option.key.metricId)?.group
                            HistoryMetricOption(option.key, text.title(option.key),
                                "$caption ${text.groups[group] ?: text.other}", option.available)
                        }, query.text) else emptyList()
                    if (searching && options.isEmpty() && !historySearchMatches(caption, query.text)) return@forEach
                    shownSources++
                    if (!(compact && searching && options.isNotEmpty())) item("source:${source.id.length}:${source.id}") {
                        TextButton(onClick = {
                            expanded = if (open) expanded - source.id else expanded + source.id
                            if (!open) onLoadSource(source.id)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp)) {
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                Text(sourceLabels.getValue(source.id), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(text.kinds.getValue(source.kind), style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (source.id in state.loadingSources) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                        }
                    }
                    if (open || searching) {
                        if (source.id in state.sourceErrors) item("error:${source.id}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(Res.string.history_metrics_error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { onLoadSource(source.id) }) { Text(stringResource(Res.string.history_retry)) }
                            }
                        }
                        options.groupBy { HistoryMetricRegistry.descriptor(it.key.metricId)?.group }.entries
                            .sortedBy { it.key?.ordinal ?: Int.MAX_VALUE }.forEach { (group, rows) ->
                            if (!(compact && searching)) item("group:${source.id.length}:${source.id}:${group?.name}") {
                                Text(text.groups[group] ?: text.other, Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            }
                            items(rows, key = { historyMetricItemKey(it.key) }) { option ->
                                val checked = option.key in selected
                                Row(Modifier.fillMaxWidth().then(if (reduceMotion) Modifier else Modifier.animateItem())
                                    .toggleable(value = checked, enabled = option.available || checked, role = Role.Checkbox,
                                        onValueChange = { if (checked) onRemove(option.key) else onAdd(option.key) })
                                    .padding(horizontal = 24.dp, vertical = if (compact) 0.dp else 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(option.title, style = MaterialTheme.typography.bodyLarge)
                                        if (compact && searching) Text(caption, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (!option.available) Text(stringResource(Res.string.history_metric_unlisted),
                                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Checkbox(checked = checked, onCheckedChange = null)
                                }
                            }
                        }
                        if (recorded != null && options.isEmpty()) item("no-match:${source.id}") {
                            Text(stringResource(Res.string.history_metrics_empty), Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (recorded?.truncated == true) item("partial:${source.id}") {
                            Text(stringResource(Res.string.history_metrics_partial), Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (searching && shownSources == 0) item("no-search-match") {
                    Text(stringResource(Res.string.history_metrics_empty), Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

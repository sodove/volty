package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistorySeriesKey
import volty.composeapp.generated.resources.*

/** Ordered source-aware selection, independent of currently connected hardware. */
@Composable
fun HistorySelectedMetrics(state: HistoryDetailState, onRemove: (HistorySeriesKey) -> Unit,
    onMove: (HistorySeriesKey, Int) -> Unit, modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val text = historyMetricText()
    val sources = state.sources.associateBy { it.id }
    val sourceLabels = historySourceLabels(state.sources)
    LazyColumn(modifier) {
        if (state.selectedKeys.isEmpty()) item("empty") {
            Text(stringResource(Res.string.history_metrics_selected_empty), Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(state.selectedKeys, key = { _, key -> historyMetricItemKey(key) }) { index, key ->
            val title = text.title(key)
            Row(Modifier.fillMaxWidth().then(if (reduceMotion) Modifier else Modifier.animateItem()).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    sources[key.sourceId]?.let { source -> Text("${sourceLabels.getValue(source.id)} · ${text.kinds.getValue(source.kind)}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (key in state.metricErrors) Text(stringResource(Res.string.history_metric_chart_error),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onMove(key, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, stringResource(Res.string.history_move_up, title))
                }
                IconButton(onClick = { onMove(key, index + 1) }, enabled = index < state.selectedKeys.lastIndex) {
                    Icon(Icons.Default.KeyboardArrowDown, stringResource(Res.string.history_move_down, title))
                }
                IconButton(onClick = { onRemove(key) }) {
                    Icon(Icons.Default.Close, stringResource(Res.string.history_remove_metric, title))
                }
            }
        }
    }
}

package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/** Only renders selected, already-owned recorded series; no live graph streams.
 * Cursor age is supplied by the caller, never inferred from thinned point spacing.
 */
@Composable
fun HistoryChartPanel(state: HistoryDetailState, onSelectTime: (Long?) -> Unit,
    cursorMaxAgeMillis: (HistorySeriesKey) -> Long, modifier: Modifier = Modifier,
    windowPreview: HistoryRange? = null, gestureOwner: HistoryComponent? = null, compact: Boolean = false,
    mapMetricKey: HistorySeriesKey? = null, onSelectMapMetric: ((HistorySeriesKey) -> Unit)? = null) {
    val text = historyMetricText()
    val labels = historySourceLabels(state.sources)
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.selectedKeys.isEmpty()) item("empty") {
            Text(stringResource(Res.string.history_metrics_selected_empty))
        }
        items(state.selectedKeys, key = ::historyMetricItemKey) { key ->
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text.title(key), style = MaterialTheme.typography.titleSmall)
                        Text(labels[key.sourceId] ?: stringResource(Res.string.history_summary_source_unavailable),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onSelectMapMetric != null) IconButton(onClick = { onSelectMapMetric(key) }) {
                        Icon(Icons.Default.Map, stringResource(Res.string.history_map_use_metric, text.title(key)),
                            tint = if (mapMetricKey == key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    }
                    val density = LocalDensity.current
                    var bodyHeight by remember(key, compact, density.density, density.fontScale) { mutableStateOf(if (compact) 100.dp else 180.dp) }
                    // A slower range query clears facts, not the row's geometry.
                    // Preserve the measured body height while progress/errors are
                    // shown; never show the old window's values as a placeholder.
                    Box(Modifier.fillMaxWidth().heightIn(min = bodyHeight).onSizeChanged { size ->
                        val measured = with(density) { size.height.toDp() }
                        if (measured > bodyHeight) bodyHeight = measured
                    }) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { when {
                        key in state.loadingKeys -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        key in state.metricErrors -> Text(stringResource(Res.string.history_metric_chart_error), color = MaterialTheme.colorScheme.error)
                        else -> {
                            val series = state.series[key]; val range = state.range
                            if (series == null || range == null) Text(stringResource(Res.string.history_summary_unavailable))
                            else RecordedChart(series, range, windowPreview, state.cursorEpochMillis,
                                cursorMaxAgeMillis(key), onSelectTime, gestureOwner, compact)
                        }
                    }
                    }
                    }
                }
            }
        }
    }
}

private data class BoundHistoryChart(val source: HistorySeries, val range: HistoryRange, val scene: HistoryChartScene?)

@Composable
private fun RecordedChart(series: HistorySeries, range: HistoryRange, preview: HistoryRange?, cursor: Long?, maxAgeMillis: Long,
    onSelectTime: (Long?) -> Unit, gestureOwner: HistoryComponent?, compact: Boolean) {
    val bound by produceState<BoundHistoryChart?>(null, series, range) {
        value = withContext(Dispatchers.Default) { BoundHistoryChart(series, range, historyChartScene(series, range)) }
    }
    val matching = bound?.takeIf { it.source === series && it.range == range }
    val window = preview ?: range
    val scene = remember(matching, window) { matching?.scene?.let { historyChartViewport(it, window) } }
    val latestSelect by rememberUpdatedState(onSelectTime)
    val chartPadding = with(LocalDensity.current) { 6.dp.toPx() }
    val unit = historyUnitText(HistoryMetricRegistry.descriptor(series.key.metricId)?.unit)
    val sample = cursor?.let { historyCursorSample(series, it, maxAgeMillis) }
    val sampleTime = sample?.tick?.let { tick -> series.origin?.let { historyAxisEpochMillis(it, tick) } ?: tick.epochMillis }
    val selected = scene?.segments?.flatten()?.firstOrNull { it.epochMillis == sampleTime && it.x in 0f..1f }
    val reading = selected?.let { "${formatFixed(it.value.toFloat(), 2)} $unit" } ?: "—"
    Text(stringResource(Res.string.history_chart_cursor, reading), style = MaterialTheme.typography.labelMedium)
    when {
        matching == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
        scene == null -> Text(stringResource(Res.string.history_metric_chart_error), color = MaterialTheme.colorScheme.error)
        scene.segments.isEmpty() -> Text(stringResource(Res.string.history_summary_unavailable))
        else -> {
            if (!compact) Text(stringResource(Res.string.history_chart_scale, scene.minimum?.let { formatFixed(it.toFloat(), 2) } ?: "—", scene.maximum?.let { formatFixed(it.toFloat(), 2) } ?: "—", unit),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val line = MaterialTheme.colorScheme.primary
            val grid = MaterialTheme.colorScheme.outlineVariant
            val cursorColor = MaterialTheme.colorScheme.tertiary
            val description = stringResource(Res.string.history_chart_description)
            Canvas(Modifier.fillMaxWidth().height(if (compact) 76.dp else 132.dp).semantics { contentDescription = description }
                .pointerInput(range, gestureOwner) {
                    if (gestureOwner != null) detectHistoryChartGestures(gestureOwner, range, chartPadding) { latestSelect(it) }
                    else detectTapGestures { offset ->
                        val width = size.width - 2 * chartPadding
                        if (width > 0) latestSelect(historyTimeAtFraction(range, (offset.x - chartPadding) / width))
                    }
                }) {
                val padding = 6.dp.toPx()
                val width = (size.width - 2 * padding).coerceAtLeast(0f)
                val height = (size.height - 2 * padding).coerceAtLeast(0f)
                fun point(p: HistoryChartPoint) = Offset(padding + p.x * width, padding + p.y * height)
                drawLine(grid, Offset(padding, size.height - padding), Offset(size.width - padding, size.height - padding))
                clipRect(padding, padding, size.width - padding, size.height - padding) {
                for (segment in scene.segments) {
                    if (segment.size == 1) drawCircle(line, 2.dp.toPx(), point(segment.single()))
                    else {
                        val path = Path()
                        segment.forEachIndexed { index, p -> val at = point(p)
                            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y) }
                        drawPath(path, line, style = Stroke(2.dp.toPx()))
                    }
                }
                cursor?.takeIf { it in window.startEpochMillis..window.endEpochMillis }
                    ?.let { historyTimeFraction(window, it) }?.let { fraction ->
                    val x = padding + width * fraction
                    drawLine(cursorColor, Offset(x, padding), Offset(x, size.height - padding), 1.dp.toPx())
                }
                selected?.let { drawCircle(cursorColor, 4.dp.toPx(), point(it)) }
                }
            }
            if (scene.truncated) Text(stringResource(Res.string.history_chart_partial),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

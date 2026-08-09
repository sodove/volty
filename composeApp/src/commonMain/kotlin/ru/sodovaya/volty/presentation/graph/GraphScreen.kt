package ru.sodovaya.volty.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.presentation.common.MetricCard
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.graph_add
import volty.composeapp.generated.resources.graph_avg
import volty.composeapp.generated.resources.graph_avg_peak
import volty.composeapp.generated.resources.graph_battery_group
import volty.composeapp.generated.resources.graph_cell_delta
import volty.composeapp.generated.resources.graph_cell_max
import volty.composeapp.generated.resources.graph_cell_min
import volty.composeapp.generated.resources.graph_compare
import volty.composeapp.generated.resources.graph_current
import volty.composeapp.generated.resources.graph_duty
import volty.composeapp.generated.resources.graph_esc_temp
import volty.composeapp.generated.resources.graph_erpm
import volty.composeapp.generated.resources.graph_input_voltage
import volty.composeapp.generated.resources.graph_history
import volty.composeapp.generated.resources.graph_min
import volty.composeapp.generated.resources.graph_motor_current
import volty.composeapp.generated.resources.graph_motor_power
import volty.composeapp.generated.resources.graph_motor_temp
import volty.composeapp.generated.resources.graph_motion_group
import volty.composeapp.generated.resources.graph_no_data
import volty.composeapp.generated.resources.graph_no_history
import volty.composeapp.generated.resources.graph_now
import volty.composeapp.generated.resources.graph_peak
import volty.composeapp.generated.resources.graph_power
import volty.composeapp.generated.resources.graph_remove
import volty.composeapp.generated.resources.graph_selected_time
import volty.composeapp.generated.resources.graph_soc
import volty.composeapp.generated.resources.graph_speed
import volty.composeapp.generated.resources.graph_temp
import volty.composeapp.generated.resources.graph_title
import volty.composeapp.generated.resources.graph_used
import volty.composeapp.generated.resources.graph_volt
import volty.composeapp.generated.resources.graph_cells_group
import volty.composeapp.generated.resources.graph_current_ride

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(component: GraphComponent) {
    val state by component.state.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.graph_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Text(
                        text = stringResource(Res.string.graph_history),
                        modifier = Modifier
                            .clickable { showHistory = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MetricPicker(
                state = state,
                onSelect = { metric ->
                    if (metric in state.visibleMetrics) component.onMetricSelected(metric)
                    else component.onMetricAdded(metric)
                }
            )
            WindowPicker(state.window, component::onWindowSelected)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 280.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.visibleMetrics, key = { it.name }) { metric ->
                    GraphCard(
                        metric = metric,
                        series = state.series[metric] ?: GraphSeries(metric, emptyList()),
                        selectedPoint = state.selectedPoints[metric],
                        selectedTimestamp = state.selectedTimestamp,
                        onSelectTimestamp = component::onTimestampSelected,
                        onRemove = { component.onMetricRemoved(metric) }
                    )
                }
            }
        }
    }
    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text(stringResource(Res.string.graph_history)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(Res.string.graph_current_ride),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                component.onRideSelected(null)
                                showHistory = false
                            }
                            .padding(vertical = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.history.isEmpty()) {
                        Text(stringResource(Res.string.graph_no_history))
                    } else {
                        state.history.forEach { ride ->
                            Text(
                                text = ride.startedAt.toString(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        component.onRideSelected(ride.id)
                                        showHistory = false
                                    }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun MetricPicker(state: GraphComponent.State, onSelect: (GraphMetric) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(Res.string.graph_add),
            modifier = Modifier.padding(horizontal = 14.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(GraphMetric.values().toList(), key = { it.name }) { metric ->
                MetricTab(
                    label = graphMetricLabel(metric),
                    active = metric in state.visibleMetrics,
                    onClick = { onSelect(metric) }
                )
            }
        }
    }
}

@Composable
private fun WindowPicker(window: GraphWindow, onSelect: (GraphWindow) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GraphWindow.values().forEach { candidate ->
                WindowChip(candidate.label, window == candidate) { onSelect(candidate) }
            }
        }
    }
}

@Composable
private fun GraphCard(
    metric: GraphMetric,
    series: GraphSeries,
    selectedPoint: GraphPoint?,
    selectedTimestamp: kotlin.time.Instant?,
    onSelectTimestamp: (kotlin.time.Instant?) -> Unit,
    onRemove: () -> Unit
) {
    val points = series.points
    val values = points.map { it.value }
    val current = selectedPoint?.value ?: values.lastOrNull()
    val avg = values.takeIf { it.isNotEmpty() }?.average()?.toFloat()
    val peak = values.maxOrNull()
    val min = values.minOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(graphMetricLabel(metric), fontWeight = FontWeight.SemiBold)
                Text(metric.source.name, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.graph_remove))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    "${formatVal(current, metric)} ${metric.unit}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (selectedTimestamp == null) stringResource(Res.string.graph_now)
                    else stringResource(Res.string.graph_selected_time),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${formatVal(avg, metric)} / ${formatVal(peak, metric)} ${metric.unit}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(8.dp))
        LineGraph(
            series = series,
            selectedPoint = selectedPoint,
            onSelectTimestamp = onSelectTimestamp,
            modifier = Modifier.fillMaxWidth().height(160.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MetricCard(
                label = stringResource(Res.string.graph_avg),
                value = "${formatVal(avg, metric)} ${metric.unit}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = stringResource(Res.string.graph_peak),
                value = "${formatVal(peak, metric)} ${metric.unit}",
                modifier = Modifier.weight(1f)
            )
            MetricCard(
                label = stringResource(Res.string.graph_min),
                value = "${formatVal(min, metric)} ${metric.unit}",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MetricTab(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun WindowChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
private fun LineGraph(
    series: GraphSeries,
    selectedPoint: GraphPoint?,
    onSelectTimestamp: (kotlin.time.Instant?) -> Unit,
    modifier: Modifier = Modifier,
    minRange: Float = minRangeFor(series.metric)
) {
    val points = series.points
    if (points.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.graph_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        return
    }
    val values = points.map { it.value }
    val rawMin = values.min()
    val rawMax = values.max()
    val center = (rawMin + rawMax) / 2f
    val half = maxOf((rawMax - rawMin) / 2f, minRange / 2f, 0.001f)
    val min = center - half
    val range = 2 * half
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    Canvas(
        modifier = modifier.pointerInput(series) {
            detectTapGestures { offset ->
                onSelectTimestamp(timestampAtFraction(points, (offset.x / size.width).coerceIn(0f, 1f)))
            }
        }
    ) {
        val w = size.width
        val h = size.height
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            drawLine(
                color = gridColor,
                start = Offset(0f, f * h),
                end = Offset(w, f * h),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f))
            )
        }
        val step = w / (points.size - 1)
        val path = Path()
        val fill = Path()
        points.forEachIndexed { index, point ->
            val x = index * step
            val y = h - ((point.value - min) / range) * h
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(w, h)
        fill.close()
        drawPath(fill, color = lineColor.copy(alpha = 0.12f))
        drawPath(path, color = lineColor, style = Stroke(width = 2.5f))

        val marker = selectedPoint ?: points.last()
        val markerFraction = timestampFraction(points, marker.timestamp)
        val markerX = markerFraction * w
        val markerY = h - ((marker.value - min) / range) * h
        drawLine(
            color = lineColor.copy(alpha = 0.6f),
            start = Offset(markerX, 0f),
            end = Offset(markerX, h),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
        )
        drawCircle(lineColor, radius = 4f, center = Offset(markerX, markerY))
    }
}

private fun minRangeFor(metric: GraphMetric): Float = when (metric) {
    GraphMetric.POWER, GraphMetric.MOTOR_POWER -> 100f
    GraphMetric.CURRENT, GraphMetric.MOTOR_CURRENT -> 2f
    GraphMetric.VOLTAGE, GraphMetric.INPUT_VOLTAGE, GraphMetric.CELL_MIN_V, GraphMetric.CELL_MAX_V -> 1f
    GraphMetric.SOC, GraphMetric.DUTY -> 5f
    GraphMetric.TEMPERATURE, GraphMetric.ESC_TEMPERATURE, GraphMetric.MOTOR_TEMPERATURE -> 5f
    GraphMetric.CELL_DELTA_MV -> 20f
    GraphMetric.SPEED -> 5f
    GraphMetric.ERPM -> 500f
}

@Composable
private fun graphMetricLabel(metric: GraphMetric): String = stringResource(metricResource(metric))

private fun metricResource(metric: GraphMetric): StringResource = when (metric) {
    GraphMetric.SOC -> Res.string.graph_soc
    GraphMetric.POWER -> Res.string.graph_power
    GraphMetric.CURRENT -> Res.string.graph_current
    GraphMetric.VOLTAGE -> Res.string.graph_volt
    GraphMetric.TEMPERATURE -> Res.string.graph_temp
    GraphMetric.CELL_MIN_V -> Res.string.graph_cell_min
    GraphMetric.CELL_MAX_V -> Res.string.graph_cell_max
    GraphMetric.CELL_DELTA_MV -> Res.string.graph_cell_delta
    GraphMetric.SPEED -> Res.string.graph_speed
    GraphMetric.DUTY -> Res.string.graph_duty
    GraphMetric.MOTOR_CURRENT -> Res.string.graph_motor_current
    GraphMetric.INPUT_VOLTAGE -> Res.string.graph_input_voltage
    GraphMetric.MOTOR_POWER -> Res.string.graph_motor_power
    GraphMetric.ERPM -> Res.string.graph_erpm
    GraphMetric.ESC_TEMPERATURE -> Res.string.graph_esc_temp
    GraphMetric.MOTOR_TEMPERATURE -> Res.string.graph_motor_temp
}

private fun formatVal(value: Float?, metric: GraphMetric): String {
    if (value == null) return "—"
    val precision = when (metric) {
        GraphMetric.SOC, GraphMetric.POWER, GraphMetric.MOTOR_POWER, GraphMetric.DUTY,
        GraphMetric.TEMPERATURE, GraphMetric.ESC_TEMPERATURE, GraphMetric.MOTOR_TEMPERATURE,
        GraphMetric.ERPM -> 0
        GraphMetric.CURRENT, GraphMetric.MOTOR_CURRENT, GraphMetric.CELL_DELTA_MV -> 1
        GraphMetric.VOLTAGE, GraphMetric.INPUT_VOLTAGE, GraphMetric.CELL_MIN_V, GraphMetric.CELL_MAX_V -> 2
        GraphMetric.SPEED -> 1
    }
    return formatFixed(value, precision)
}

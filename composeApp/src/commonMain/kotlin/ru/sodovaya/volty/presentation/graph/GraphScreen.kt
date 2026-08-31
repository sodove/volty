package ru.sodovaya.volty.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.graph_battery_group
import volty.composeapp.generated.resources.graph_cell_delta
import volty.composeapp.generated.resources.graph_cell_max
import volty.composeapp.generated.resources.graph_cell_min
import volty.composeapp.generated.resources.graph_cells_group
import volty.composeapp.generated.resources.graph_compare
import volty.composeapp.generated.resources.graph_current
import volty.composeapp.generated.resources.graph_current_ride
import volty.composeapp.generated.resources.graph_current_header
import volty.composeapp.generated.resources.graph_duty
import volty.composeapp.generated.resources.graph_esc_temp
import volty.composeapp.generated.resources.graph_erpm
import volty.composeapp.generated.resources.graph_history
import volty.composeapp.generated.resources.graph_input_voltage
import volty.composeapp.generated.resources.graph_motor_current
import volty.composeapp.generated.resources.graph_motor_power
import volty.composeapp.generated.resources.graph_motor_temp
import volty.composeapp.generated.resources.graph_motion_group
import volty.composeapp.generated.resources.graph_max_header
import volty.composeapp.generated.resources.graph_min_header
import volty.composeapp.generated.resources.graph_no_data
import volty.composeapp.generated.resources.graph_no_history
import volty.composeapp.generated.resources.graph_now
import volty.composeapp.generated.resources.graph_power
import volty.composeapp.generated.resources.graph_plot
import volty.composeapp.generated.resources.graph_point_time
import volty.composeapp.generated.resources.graph_ride_max_note
import volty.composeapp.generated.resources.graph_remove
import volty.composeapp.generated.resources.graph_scale_note
import volty.composeapp.generated.resources.graph_signals
import volty.composeapp.generated.resources.graph_soc
import volty.composeapp.generated.resources.graph_speed
import volty.composeapp.generated.resources.graph_temp
import volty.composeapp.generated.resources.graph_title
import volty.composeapp.generated.resources.graph_time_range
import volty.composeapp.generated.resources.graph_volt
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val PlotColors = listOf(
    Color(0xFF4D7FC4),
    Color(0xFFC83434),
    Color(0xFF3D9B76),
    Color(0xFFD07A2F),
    Color(0xFF8C63C7),
    Color(0xFF149AA5),
    Color(0xFFCC4D8A),
    Color(0xFF6F7C8F)
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun GraphScreen(
    component: GraphComponent,
) {
    val state by component.state.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var showComparison by remember { mutableStateOf(false) }

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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(Res.string.graph_compare),
                            modifier = Modifier
                                .clickable { showComparison = true }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(Res.string.graph_history),
                            modifier = Modifier
                                .clickable {
                                    component.onHistoryRequested()
                                    showHistory = true
                                }
                                .minimumInteractiveComponentSize()
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val landscape = maxWidth >= 700.dp
            if (landscape) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PlotPane(
                        state = state,
                        onWindowSelected = component::onWindowSelected,
                        onTimestampSelected = component::onTimestampSelected,
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxHeight()
                    )
                    MetricTable(
                        state = state,
                        onMetricAdded = component::onMetricAdded,
                        onMetricRemoved = component::onMetricRemoved,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PlotPane(
                        state = state,
                        onWindowSelected = component::onWindowSelected,
                        onTimestampSelected = component::onTimestampSelected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 300.dp, max = 430.dp)
                    )
                    MetricTable(
                        state = state,
                        onMetricAdded = component::onMetricAdded,
                        onMetricRemoved = component::onMetricRemoved,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }
    }

    if (showHistory) {
        HistoryDialog(
            state = state,
            onDismiss = { showHistory = false },
            onRideSelected = {
                component.onRideSelected(it)
                showHistory = false
            }
        )
    }
    if (showComparison) {
        ComparisonDialog(
            state = state,
            onDismiss = { showComparison = false },
            onTimestampSelected = component::onTimestampSelected,
            onMetricAdded = component::onMetricAdded,
            onMetricRemoved = component::onMetricRemoved
        )
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun PlotPane(
    state: GraphComponent.State,
    onWindowSelected: (GraphWindow) -> Unit,
    onTimestampSelected: (Instant?) -> Unit,
    modifier: Modifier
) {
    val timeline = remember(state.series, state.visibleMetrics) {
        mergedPlotTimeline(state.visibleMetrics.mapNotNull { state.series[it] })
    }
    Column(modifier = modifier) {
        WindowPicker(state.window, onWindowSelected)
        Spacer(Modifier.height(8.dp))
        TelemetryPlot(
            series = state.series,
            visibleMetrics = state.visibleMetrics,
            selectedTimestamp = state.selectedTimestamp,
            onTimestampSelected = onTimestampSelected,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
        )
        if (timeline.isNotEmpty()) {
            PlotTimeAxis(timeline)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.selectedTimestamp?.let {
                "${stringResource(Res.string.graph_point_time)}: ${formatTimestamp(it)}"
            } ?: stringResource(Res.string.graph_now),
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(Res.string.graph_scale_note),
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (timeline.isNotEmpty()) {
            Text(
                text = "${stringResource(Res.string.graph_time_range)}: " +
                    "${formatTimestamp(timeline.first())} — ${formatTimestamp(timeline.last())}",
                modifier = Modifier.padding(horizontal = 8.dp),
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun PlotTimeAxis(timeline: List<Instant>) {
    val ticks = timelineTicks(timeline)
    if (ticks.size == 1) {
        Text(
            text = formatAxisTimestamp(ticks.single()),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            textAlign = TextAlign.Center,
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        ticks.forEachIndexed { index, timestamp ->
            Text(
                text = formatAxisTimestamp(timestamp),
                modifier = Modifier.weight(1f),
                textAlign = when {
                    index == 0 -> TextAlign.Start
                    index == ticks.lastIndex -> TextAlign.End
                    else -> TextAlign.Center
                },
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun TelemetryPlot(
    series: Map<GraphMetric, GraphSeries>,
    visibleMetrics: List<GraphMetric>,
    selectedTimestamp: Instant?,
    onTimestampSelected: (Instant?) -> Unit,
    modifier: Modifier
) {
    val traces = visibleMetrics.mapNotNull { metric ->
        series[metric]?.takeIf { it.points.isNotEmpty() }
    }
    val timeline = remember(traces) { mergedPlotTimeline(traces) }
    val timelinePoints = remember(timeline) { timeline.map { GraphPoint(it, 0f) } }
    val latestTimeline = rememberUpdatedState(timeline)
    val latestOnTimestampSelected = rememberUpdatedState(onTimestampSelected)
    if (traces.isEmpty() || timeline.size < 2) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.graph_no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val currentTimeline = latestTimeline.value
                if (currentTimeline.isEmpty()) return@detectTapGestures
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                latestOnTimestampSelected.value(nearestTimelineTimestamp(currentTimeline, fraction))
            }
        }
    ) {
        val chartLeft = 12f
        val chartRight = size.width - 12f
        val chartTop = 18f
        val chartBottom = size.height - 18f
        val chartWidth = (chartRight - chartLeft).coerceAtLeast(1f)
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)

        listOf(0.2f, 0.4f, 0.6f, 0.8f).forEach { fraction ->
            val y = chartTop + chartHeight * fraction
            drawLine(
                color = gridColor,
                start = Offset(chartLeft, y),
                end = Offset(chartRight, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
            )
        }

        traces.forEach { trace ->
            val range = plotRange(trace.points, minimumSpanFor(trace.metric)) ?: return@forEach
            val color = plotColor(trace.metric)
            val path = Path()
            trace.points.forEachIndexed { pointIndex, point ->
                val x = chartLeft + timestampFraction(timelinePoints, point.timestamp) * chartWidth
                val y = chartBottom - plotFraction(point.value, range) * chartHeight
                if (pointIndex == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            val marker = selectedTimestamp?.let { nearestPoint(trace.points, it) } ?: trace.points.last()
            val markerX = chartLeft + timestampFraction(timelinePoints, marker.timestamp) * chartWidth
            val markerY = chartBottom - plotFraction(marker.value, range) * chartHeight
            drawCircle(color, radius = 4f, center = Offset(markerX, markerY))
        }

        selectedTimestamp?.let { timestamp ->
            val x = chartLeft + timestampFraction(timelinePoints, timestamp) * chartWidth
            drawLine(
                color = cursorColor,
                start = Offset(x, chartTop),
                end = Offset(x, chartBottom),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f))
            )
        }
    }
}

@Composable
private fun MetricTable(
    state: GraphComponent.State,
    onMetricAdded: (GraphMetric) -> Unit,
    onMetricRemoved: (GraphMetric) -> Unit,
    modifier: Modifier
) {
    val metrics = GraphMetric.values().toList()
    LazyColumn(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                stringResource(Res.string.graph_signals),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                fontWeight = FontWeight.SemiBold
            )
        }
        item {
            Text(
                stringResource(Res.string.graph_ride_max_note),
                modifier = Modifier.padding(horizontal = 8.dp),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item { MetricStatsHeader() }
        items(metrics, key = { it.name }) { metric ->
            if (metric == metrics.first() || metricGroupResource(metric) != metricGroupResource(metrics[metrics.indexOf(metric) - 1])) {
                Text(
                    stringResource(metricGroupResource(metric)),
                    modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
            }
            MetricRow(
                metric = metric,
                active = metric in state.visibleMetrics,
                point = state.selectedPoints[metric] ?: state.series[metric]?.points?.lastOrNull(),
                extrema = plotExtrema(state.series[metric]?.points.orEmpty()),
                ridePeak = state.ridePeaks[metric],
                canRemove = state.visibleMetrics.size > 1,
                onToggle = { enabled -> if (enabled) onMetricAdded(metric) else onMetricRemoved(metric) }
            )
        }
    }
}

@Composable
private fun MetricStatsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier.weight(1.55f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(Res.string.graph_current_header),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(Res.string.graph_min_header),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(Res.string.graph_max_header),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(Res.string.graph_plot),
                modifier = Modifier.widthIn(min = 42.dp),
                textAlign = TextAlign.Center,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetricRow(
    metric: GraphMetric,
    active: Boolean,
    point: GraphPoint?,
    extrema: PlotExtrema?,
    ridePeak: Float?,
    canRemove: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val color = plotColor(metric)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) color.copy(alpha = 0.10f) else Color.Transparent)
            .clickable { onToggle(!active) }
            .minimumInteractiveComponentSize()
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (active) color else MaterialTheme.colorScheme.outlineVariant)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(graphMetricLabel(metric), fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            Text(metric.unit, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.weight(1.55f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = point?.let { formatVal(it.value, metric) } ?: "—",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 12.sp,
                color = if (point == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = extrema?.let { formatVal(it.min, metric) } ?: "—",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = ridePeak?.let { formatVal(it, metric) }
                    ?: extrema?.let { formatVal(it.max, metric) }
                    ?: "—",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Checkbox(
                checked = active,
                onCheckedChange = onToggle,
                enabled = !active || canRemove
            )
        }
    }
}

@Composable
private fun WindowPicker(window: GraphWindow, onSelect: (GraphWindow) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
                val active = candidate == window
                Text(
                    candidate.label,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { onSelect(candidate) }
                        .minimumInteractiveComponentSize()
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class)
@Composable
private fun HistoryDialog(
    state: GraphComponent.State,
    onDismiss: () -> Unit,
    onRideSelected: (String?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.graph_history)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(Res.string.graph_current_ride),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRideSelected(null) }
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
                                .clickable { onRideSelected(ride.id) }
                                .padding(vertical = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
}

@OptIn(ExperimentalTime::class)
@Composable
private fun ComparisonDialog(
    state: GraphComponent.State,
    onDismiss: () -> Unit,
    onTimestampSelected: (Instant?) -> Unit,
    onMetricAdded: (GraphMetric) -> Unit,
    onMetricRemoved: (GraphMetric) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.graph_compare)) },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryPlot(
                    series = state.series,
                    visibleMetrics = state.visibleMetrics,
                    selectedTimestamp = state.selectedTimestamp,
                    onTimestampSelected = onTimestampSelected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                )
                MetricTable(
                    state = state,
                    onMetricAdded = onMetricAdded,
                    onMetricRemoved = onMetricRemoved,
                    modifier = Modifier.height(280.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
    )
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

private fun metricGroupResource(metric: GraphMetric): StringResource = when (metric) {
    GraphMetric.CELL_MIN_V, GraphMetric.CELL_MAX_V, GraphMetric.CELL_DELTA_MV -> Res.string.graph_cells_group
    GraphMetric.SOC, GraphMetric.POWER, GraphMetric.CURRENT, GraphMetric.VOLTAGE, GraphMetric.TEMPERATURE ->
        Res.string.graph_battery_group
    else -> Res.string.graph_motion_group
}

private fun minimumSpanFor(metric: GraphMetric): Float = when (metric) {
    GraphMetric.POWER, GraphMetric.MOTOR_POWER -> 100f
    GraphMetric.CURRENT, GraphMetric.MOTOR_CURRENT -> 2f
    GraphMetric.VOLTAGE, GraphMetric.INPUT_VOLTAGE, GraphMetric.CELL_MIN_V, GraphMetric.CELL_MAX_V -> 1f
    GraphMetric.SOC, GraphMetric.DUTY -> 5f
    GraphMetric.TEMPERATURE, GraphMetric.ESC_TEMPERATURE, GraphMetric.MOTOR_TEMPERATURE -> 5f
    GraphMetric.CELL_DELTA_MV -> 20f
    GraphMetric.SPEED -> 5f
    GraphMetric.ERPM -> 500f
}

private fun plotColor(metric: GraphMetric): Color =
    PlotColors[GraphMetric.values().indexOf(metric).coerceAtLeast(0) % PlotColors.size]

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

private fun formatTimestamp(timestamp: Instant): String =
    timestamp.toString().removeSuffix("Z").replace('T', ' ').take(19)

@OptIn(ExperimentalTime::class)
private fun formatAxisTimestamp(timestamp: Instant): String =
    timestamp.toString().substringAfter('T').removeSuffix("Z").take(8)

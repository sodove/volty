package com.volty.app.presentation.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volty.app.presentation.common.MetricCard
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GraphScreen(component: GraphComponent) {
    val state by component.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Graph", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = component::onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Metric tab row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                GraphMetric.values().forEach { m ->
                    MetricTab(m.label, state.metric == m) { component.onMetricSelected(m) }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            "${formatVal(state.nowValue, state.metric)} ${state.metric.unit}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Now", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("avg / peak", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${formatVal(state.avg, state.metric)} / ${formatVal(state.peak, state.metric)} ${state.metric.unit}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                LineGraph(
                    values = state.values,
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        GraphWindow.values().forEach { w ->
                            WindowChip(w.label, state.window == w) { component.onWindowSelected(w) }
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetricCard(
                    label = "avg",
                    value = "${formatVal(state.avg, state.metric)} ${state.metric.unit}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "peak",
                    value = "${formatVal(state.peak, state.metric)} ${state.metric.unit}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "min",
                    value = "${formatVal(state.min, state.metric)} ${state.metric.unit}",
                    modifier = Modifier.weight(1f)
                )
                MetricCard(
                    label = "used",
                    value = when (state.metric) {
                        GraphMetric.POWER -> "${formatVal(state.used, state.metric)} Wh"
                        GraphMetric.CURRENT -> "${formatVal(state.used, state.metric)} Ah"
                        else -> "—"
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun RowScope.MetricTab(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp),
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
private fun LineGraph(values: List<Float>, modifier: Modifier = Modifier) {
    if (values.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data yet", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        return
    }
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        // Grid lines
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            drawLine(
                color = gridColor,
                start = Offset(0f, f * h),
                end = Offset(w, f * h),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 3f))
            )
        }

        // Line
        val step = w / (values.size - 1)
        val path = Path()
        val fill = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = h - ((v - min) / range) * h
            if (i == 0) {
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
        drawPath(path = fill, color = lineColor.copy(alpha = 0.12f))
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.5f))

        // Now marker
        val lastX = (values.size - 1) * step
        val lastY = h - ((values.last() - min) / range) * h
        drawLine(
            color = lineColor.copy(alpha = 0.6f),
            start = Offset(lastX, 0f),
            end = Offset(lastX, h),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))
        )
        drawCircle(color = lineColor, center = Offset(lastX, lastY), radius = 4f)
    }
}

private fun formatVal(v: Float, metric: GraphMetric): String {
    val precision = when (metric) {
        GraphMetric.SOC -> 0
        GraphMetric.POWER -> 0
        GraphMetric.CURRENT -> 1
        GraphMetric.VOLTAGE -> 2
        GraphMetric.TEMPERATURE -> 0
    }
    val factor = listOf(1f, 10f, 100f, 1000f)[precision]
    val rounded = round(v * factor) / factor
    val whole = rounded.toInt()
    if (precision == 0) return "$whole"
    val fracInt = round((rounded - whole) * factor).toInt().toString().padStart(precision, '0')
    return "$whole.$fracInt"
}

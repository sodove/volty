package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.history.HistoryTotal
import ru.sodovaya.volty.presentation.history.historyTotalPrefix
import ru.sodovaya.volty.presentation.history.text
import ru.sodovaya.volty.presentation.history.title
import ru.sodovaya.volty.presentation.picker.GarageWhen
import ru.sodovaya.volty.presentation.picker.garageWhen
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*

/** Calm green for a healthy charge; amber and red only when the charge itself is the news. */
@Composable
internal fun socColor(percent: Double): Color = when {
    percent < 15 -> MaterialTheme.colorScheme.error
    percent < 30 -> Color(0xFFD08A10)
    else -> Color(0xFF1E9E86)
}

@Composable
internal fun SocRing(percent: Double?, size: Dp = 92.dp, stroke: Dp = 9.dp, caption: String? = null) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val fill = percent?.let { socColor(it) } ?: track
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val inset = s / 2
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(s))
            if (percent != null) drawArc(fill, -90f, (percent.coerceIn(0.0, 100.0) * 3.6).toFloat(), false,
                Offset(inset, inset), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "${formatFixed(it.toFloat(), 0)}%" } ?: "—",
                fontSize = if (size >= 80.dp) 22.sp else 15.sp, fontWeight = FontWeight.Bold)
            caption?.let { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun VehicleBadge(iconKey: String?, size: Dp = 46.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        Text(iconKeyToEmoji(iconKey), fontSize = (size.value * 0.5f).sp)
    }
}

@Composable
internal fun NearPill(near: Boolean) {
    val (bg, fg) = if (near) Color(0xFF1E9E86).copy(alpha = 0.16f) to Color(0xFF13806F)
        else MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.clip(CircleShape).background(bg).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (near) Box(Modifier.size(6.dp).clip(CircleShape).background(fg))
        Text(stringResource(if (near) Res.string.garage_near else Res.string.garage_away), fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold, color = fg)
    }
}

@Composable
internal fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun whenText(epochMillis: Long, nowMillis: Long): String {
    fun hm(h: Int, m: Int) = "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
    return when (val w = garageWhen(epochMillis, nowMillis)) {
        is GarageWhen.Today -> stringResource(Res.string.when_today, hm(w.hour, w.minute))
        is GarageWhen.Yesterday -> stringResource(Res.string.when_yesterday, hm(w.hour, w.minute))
        is GarageWhen.DaysAgo -> stringResource(Res.string.when_days_ago, w.days)
        is GarageWhen.Date -> "${w.day.toString().padStart(2, '0')}.${w.month.toString().padStart(2, '0')}"
    }
}

/** A sketch of the recorded track, fitted to the box with longitude shrunk by latitude. */
@Composable
internal fun RouteSketch(points: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    val line = Color(0xFF1E9E86)
    val ground = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(ground)) {
        if (points.size < 2) return@Canvas
        val midLat = points.sumOf { it.first } / points.size
        val k = cos(midLat * PI / 180)
        val xs = points.map { it.second * k }; val ys = points.map { it.first }
        val w = (xs.max() - xs.min()).coerceAtLeast(1e-9); val h = (ys.max() - ys.min()).coerceAtLeast(1e-9)
        val pad = 12.dp.toPx()
        val scale = minOf((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2; val oy = (size.height - h * scale) / 2
        val path = Path()
        points.forEachIndexed { i, _ ->
            val x = (ox + (xs[i] - xs.min()) * scale).toFloat()
            val y = (size.height - (oy + (ys[i] - ys.min()) * scale)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Per-cell bars scaled to the pack's own spread, so a 10 mV imbalance is visible rather than flat. */
@Composable
internal fun CellBars(cells: List<Double>, modifier: Modifier = Modifier) {
    if (cells.isEmpty()) return
    val bar = Color(0xFF1E9E86)
    val low = MaterialTheme.colorScheme.error
    Canvas(modifier) {
        val min = cells.min(); val max = cells.max()
        val span = (max - min).coerceAtLeast(0.02)
        val gap = 2.dp.toPx()
        val width = (size.width - gap * (cells.size - 1)) / cells.size
        cells.forEachIndexed { i, v ->
            val frac = (0.35 + 0.65 * (v - (max - span)) / span).coerceIn(0.1, 1.0).toFloat()
            val h = size.height * frac
            drawRoundRect(if (v == min && max - min >= 0.03) low else bar,
                Offset(i * (width + gap), size.height - h), Size(width, h), CornerRadius(2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TotalsTiles(totals: List<HistoryTotal>, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        totals.forEach { total ->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(stringResource(total.kind.title()), fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(historyTotalPrefix(total) + total.text(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

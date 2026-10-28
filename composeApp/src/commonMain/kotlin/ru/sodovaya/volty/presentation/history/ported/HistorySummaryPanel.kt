// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryStatsBar.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*
import kotlin.math.abs

/**
 * The ride's totals: distance, time, speeds, energy, battery, peaks. Retraction: this panel used to
 * repeat each selected chart's minimum and maximum, which now sit on the charts themselves; the
 * totals that sat under the ride title moved here. Expansion never starts more data reads.
 */
@Composable
fun HistorySummaryPanel(totals: HistoryTotalsState, expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, reduceMotion: Boolean = false) {
    val expandedLabel = stringResource(if (expanded) Res.string.history_summary_collapse else Res.string.history_summary_expand)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp) {
        Column(if (reduceMotion) Modifier else Modifier.animateContentSize()) {
            Surface(onClick = { onExpandedChange(!expanded) }, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().semantics { stateDescription = expandedLabel }) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.history_summary_recording), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, expandedLabel)
                }
            }
            when {
                totals.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
                totals.totals.isEmpty() -> Text(stringResource(Res.string.history_summary_unavailable), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall)
                // Collapsed: one sideways-scrolling row of headline numbers, the clipped tile fading out; expanded: all.
                expanded -> ru.sodovaya.volty.presentation.vehicle.TotalsTiles(
                    historyExpandedTotals(totals.totals), Modifier.padding(12.dp))
                else -> TotalsStrip(historyCollapsedTotals(totals.totals), Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun TotalsStrip(totals: List<HistoryTotal>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth()
            // An alpha mask rather than a painted gradient, so the fade matches whatever tonal surface is underneath.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (scroll.canScrollForward) {
                    val fade = FADE_WIDTH.toPx()
                    drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                        startX = size.width - fade, endX = size.width),
                        topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height),
                        blendMode = BlendMode.DstIn)
                }
            }
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        totals.forEach { ru.sodovaya.volty.presentation.vehicle.TotalTile(it) }
    }
}

private val FADE_WIDTH = 36.dp

@Composable
internal fun SummaryCell(title: String, value: HistorySummaryValue, unit: HistoryUnit?) {
    Column(Modifier.widthIn(min = 90.dp)) {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val number = value.value?.let {
            if (abs(it) >= 1e9) it.toString() else formatFixed(it.toFloat(), when (unit) {
                HistoryUnit.VOLT -> 2
                HistoryUnit.PERCENT, HistoryUnit.WATT, HistoryUnit.ERPM, HistoryUnit.MILLIVOLT -> 0
                else -> 1
            })
        }
        val qualifier = if (value.quality == SummaryQuality.PARTIAL) "≈ " else ""
        Text(if (number == null) "—" else "$qualifier$number ${historyUnitText(unit)}", style = MaterialTheme.typography.titleMedium)
        if (value.quality != SummaryQuality.EXACT) Text(stringResource(if (value.quality == SummaryQuality.PARTIAL)
            Res.string.history_summary_partial else Res.string.history_summary_unavailable),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun historyUnitText(unit: HistoryUnit?): String = when (unit) {
    HistoryUnit.PERCENT -> stringResource(Res.string.unit_percent)
    HistoryUnit.CELSIUS -> stringResource(Res.string.unit_celsius)
    HistoryUnit.KMH -> stringResource(Res.string.unit_kmh)
    HistoryUnit.VOLT -> stringResource(Res.string.history_unit_volt)
    HistoryUnit.AMP -> stringResource(Res.string.history_unit_amp)
    HistoryUnit.WATT -> stringResource(Res.string.history_unit_watt)
    HistoryUnit.MILLIVOLT -> stringResource(Res.string.history_unit_millivolt)
    HistoryUnit.ERPM -> stringResource(Res.string.history_unit_erpm)
    HistoryUnit.KM -> stringResource(Res.string.history_unit_km)
    HistoryUnit.WH -> stringResource(Res.string.history_unit_wh)
    HistoryUnit.AH -> stringResource(Res.string.history_unit_ah)
    null -> ""
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryStatsBar.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.ride.LocalLightHudPalette
import ru.sodovaya.volty.presentation.vehicle.TotalTile
import ru.sodovaya.volty.presentation.vehicle.TotalsTiles
import ru.sodovaya.volty.util.formatFixed
import volty.composeapp.generated.resources.*
import kotlin.math.abs

/**
 * The ride's totals: distance, time, speeds, energy, battery, peaks. Retraction: this panel used to
 * repeat each selected chart's minimum and maximum, which now sit on the charts themselves; the
 * totals that sat under the ride title moved here. Expansion never starts more data reads.
 */
@Composable
internal fun HistorySummaryPanel(
    totals: HistoryTotalsState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = false,
) {
    val palette = LocalLightHudPalette.current
    val tileColor = palette.surfaceStrong.copy(alpha = palette.surfaceStrong.alpha * 0.78f)
    HistoryHudSurface(modifier.fillMaxWidth()) {
        Column(if (reduceMotion) Modifier else Modifier.animateContentSize()) {
            when {
                totals.loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(12.dp))
                totals.totals.isEmpty() -> Text(
                    stringResource(Res.string.history_summary_unavailable), Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    val summary = historySummaryTotals(totals.totals)
                    val toggleLabel = stringResource(
                        if (expanded) Res.string.history_summary_collapse else Res.string.history_summary_expand,
                    )
                    val toggle = { onExpandedChange(!expanded) }
                    val tiles = Modifier.weight(1f, fill = false).padding(top = 10.dp)
                        .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = toggle)
                    if (expanded) {
                        TotalsTiles(
                            summary,
                            tiles.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                            containerColor = tileColor,
                        )
                    } else {
                        TotalsStrip(summary, tileColor, tiles)
                    }
                    Box(
                        Modifier.fillMaxWidth().height(36.dp)
                            .clickable(role = Role.Button, onClick = toggle),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = toggleLabel,
                            modifier = Modifier.size(20.dp),
                            tint = palette.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalsStrip(totals: List<HistoryTotal>, tileColor: Color, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Row(
        modifier.fillMaxWidth()
            // Mask the tiles themselves so the current map tint shows through the trailing edge.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val forward = scroll.canScrollForward
                val backward = scroll.canScrollBackward
                if (size.width > 0 && (forward || backward)) {
                    val fade = (36.dp.toPx() / size.width).coerceAtMost(0.49f)
                    val left = if (layoutDirection == LayoutDirection.Ltr) backward else forward
                    val right = if (layoutDirection == LayoutDirection.Ltr) forward else backward
                    drawRect(
                        Brush.horizontalGradient(
                            0f to if (left) Color.Transparent else Color.Black,
                            fade to Color.Black,
                            (1f - fade) to Color.Black,
                            1f to if (right) Color.Transparent else Color.Black,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        totals.forEach { TotalTile(it, tileColor) }
    }
}

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

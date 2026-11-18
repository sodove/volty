// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryStatsBar.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.*
import ru.sodovaya.volty.presentation.ride.LocalLightHudPalette
import ru.sodovaya.volty.presentation.vehicle.TotalTile
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
    val expandedLabel = stringResource(if (expanded) Res.string.history_summary_collapse else Res.string.history_summary_expand)
    val headline = historyCollapsedTotals(totals.totals)
    val details = historyExpandedTotals(totals.totals).filterNot { it in headline }
    val headlineScroll = rememberScrollState()
    val palette = LocalLightHudPalette.current
    val chevronRotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        animationSpec = if (reduceMotion) snap() else tween(200),
        label = "historySummaryChevron",
    )
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { stateDescription = expandedLabel }
            .clickable(role = Role.Button, onClickLabel = expandedLabel, onClick = { onExpandedChange(!expanded) }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            totals.loading -> LinearProgressIndicator(Modifier.width(40.dp).height(2.dp))
            headline.isEmpty() -> Text(stringResource(Res.string.history_summary_recording), color = palette.text,
                style = MaterialTheme.typography.labelMedium)
            else -> AnimatedContent(
                targetState = expanded && headlineScroll.maxValue in 1 until Int.MAX_VALUE,
                transitionSpec = {
                    if (reduceMotion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
                    else (fadeIn(tween(170)) togetherWith fadeOut(tween(120))).using(
                        SizeTransform { _, _ -> tween(220) },
                    )
                },
                label = "historySummaryHeadline",
            ) { wrap ->
                if (wrap) HistorySummaryTiles(headline) else HistorySummaryStrip(headline, headlineScroll)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            modifier = Modifier.weight(1f, fill = false),
            enter = if (reduceMotion) EnterTransition.None else expandVertically(tween(240), expandFrom = Alignment.Top) + fadeIn(tween(200)),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(tween(200), shrinkTowards = Alignment.Top) + fadeOut(tween(140)),
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
                if (details.isEmpty()) {
                    val label = if (totals.totals.isEmpty()) Res.string.history_summary_unavailable else Res.string.history_summary_recording
                    Text(stringResource(label), Modifier.align(Alignment.CenterHorizontally),
                        color = palette.text, style = MaterialTheme.typography.labelSmall)
                } else {
                    HistorySummaryTiles(details)
                }
            }
        }
        if (!totals.loading) Icon(
            Icons.Default.KeyboardArrowDown, contentDescription = null,
            Modifier.size(16.dp).rotate(chevronRotation), tint = palette.muted,
        )
    }
}

@Composable
private fun HistorySummaryStrip(totals: List<HistoryTotal>, scroll: ScrollState) {
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val forward = scroll.canScrollForward
                val backward = scroll.canScrollBackward
                if (size.width > 0 && (forward || backward)) {
                    val fade = (24.dp.toPx() / size.width).coerceAtMost(0.49f)
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
            .horizontalScroll(scroll),
    ) {
        HistorySummaryTileItems(totals)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistorySummaryTiles(totals: List<HistoryTotal>) {
    FlowRow(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HistorySummaryTileItems(totals)
    }
}

@Composable
private fun HistorySummaryTileItems(totals: List<HistoryTotal>) {
    val surface = LocalLightHudPalette.current.surfaceStrong
    totals.forEach { total ->
        key(total.kind) {
            TotalTile(total, compact = true, containerColor = surface.copy(alpha = surface.alpha * 0.78f))
        }
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

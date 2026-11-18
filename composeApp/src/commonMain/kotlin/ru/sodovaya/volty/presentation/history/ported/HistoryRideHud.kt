// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryPanelNav.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.history.HistorySeriesKey
import ru.sodovaya.volty.presentation.ride.LightCircleButton
import ru.sodovaya.volty.presentation.ride.LightLayoutMode
import ru.sodovaya.volty.presentation.ride.LightVehiclePill
import ru.sodovaya.volty.presentation.ride.LocalLightHudPalette
import volty.composeapp.generated.resources.*

@Composable
internal fun HistoryHudSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val palette = LocalLightHudPalette.current
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = palette.surfaceStrong,
        contentColor = palette.text,
        content = content,
    )
}

internal data class HistoryRideAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
internal fun HistoryRideHeader(
    title: String,
    subtitle: String,
    layoutMode: LightLayoutMode,
    onOpenList: () -> Unit,
    onBack: () -> Unit,
    actions: List<HistoryRideAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LightCircleButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.history_back), onClick = onBack)
        LightVehiclePill(
            title, layoutMode, onOpenList, Modifier.weight(1f),
            subtitle = subtitle, onClickLabel = stringResource(Res.string.history_open_rides),
        )
        if (actions.isEmpty()) Spacer(Modifier.size(48.dp))
        else Box {
            LightCircleButton(Icons.Default.MoreVert, stringResource(Res.string.history_ride_actions)) { menuOpen = true }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                actions.forEach { action ->
                    DropdownMenuItem(
                        text = { Text(action.label) },
                        leadingIcon = { Icon(action.icon, contentDescription = null) },
                        onClick = { menuOpen = false; action.onClick() },
                    )
                }
            }
        }
    }
}

@Composable
internal fun HistoryRideStepButtons(onPrevious: (() -> Unit)?, onNext: (() -> Unit)?) {
    IconButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(Res.string.history_previous_ride))
    }
    IconButton(onClick = { onNext?.invoke() }, enabled = onNext != null) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(Res.string.history_next_ride))
    }
}

@Composable
internal fun HistoryRideAnalysis(
    detail: HistoryDetailState,
    state: HistoryUiState,
    owner: HistoryComponent,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenMetrics: () -> Unit,
    cursorMaxAgeMillis: (HistorySeriesKey) -> Long,
    reduceMotion: Boolean,
    chartModifier: Modifier,
) {
    val expansion = stringResource(if (expanded) Res.string.history_charts_collapse else Res.string.history_charts_expand)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LightCircleButton(Icons.Default.ShowChart, expansion, onClick = onToggle)
            AnimatedVisibility(
                visible = expanded,
                enter = if (reduceMotion) EnterTransition.None else fadeIn(),
                exit = if (reduceMotion) ExitTransition.None else fadeOut(),
            ) {
                Row {
                    LightCircleButton(Icons.Default.BarChart, stringResource(Res.string.history_metrics_title), onClick = onOpenMetrics)
                    LightCircleButton(Icons.Default.OpenInFull, stringResource(Res.string.history_charts_fullscreen), onClick = owner::showCharts)
                }
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = if (reduceMotion) EnterTransition.None else expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = if (reduceMotion) ExitTransition.None else shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            HistoryHudSurface(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Column {
                    HistoryChartPanel(
                        detail, owner::selectTime, cursorMaxAgeMillis, chartModifier,
                        state.windowPreview, owner, compact = true, mapMetricKey = state.mapMetricKey,
                        onSelectMapMetric = owner::selectMapMetric,
                    )
                    HistoryHudTimeline(detail.copy(range = state.windowPreview ?: detail.range), owner::selectTime)
                }
            }
        }
    }
}

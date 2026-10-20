// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryPanelNav.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.*

/** Ride navigation adapted to Volty's chart and metric actions. */
@Composable
fun HistoryNavigationStrip(
    title: String,
    subtitle: String,
    canPrevious: Boolean,
    canNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenList: () -> Unit,
    onOpenCharts: () -> Unit,
    onOpenMetrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openRidesLabel = stringResource(Res.string.history_open_rides)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 360.dp || LocalDensity.current.fontScale > 1.3f
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryRideSelector(title, subtitle, openRidesLabel, onOpenList, Modifier.fillMaxWidth())
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HistoryNavButton(
                        Icons.Default.ShowChart,
                        stringResource(Res.string.history_charts_title),
                        onOpenCharts,
                    )
                    HistoryNavButton(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        stringResource(Res.string.history_previous_ride),
                        onPrevious,
                        enabled = canPrevious,
                    )
                    HistoryNavButton(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        stringResource(Res.string.history_next_ride),
                        onNext,
                        enabled = canNext,
                    )
                    HistoryNavButton(
                        Icons.Default.BarChart,
                        stringResource(Res.string.history_metrics_title),
                        onOpenMetrics,
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HistoryNavButton(
                    Icons.Default.ShowChart,
                    stringResource(Res.string.history_charts_title),
                    onOpenCharts,
                )
                HistoryNavButton(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    stringResource(Res.string.history_previous_ride),
                    onPrevious,
                    enabled = canPrevious,
                )
                HistoryRideSelector(title, subtitle, openRidesLabel, onOpenList, Modifier.weight(1f))
                HistoryNavButton(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    stringResource(Res.string.history_next_ride),
                    onNext,
                    enabled = canNext,
                )
                HistoryNavButton(
                    Icons.Default.BarChart,
                    stringResource(Res.string.history_metrics_title),
                    onOpenMetrics,
                )
            }
        }
    }
}

@Composable
private fun HistoryRideSelector(
    title: String,
    subtitle: String,
    openRidesLabel: String,
    onOpenList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onOpenList,
        modifier = modifier.heightIn(min = 54.dp).semantics {
            contentDescription = listOf(openRidesLabel, title, subtitle)
                .filter { it.isNotBlank() }
                .joinToString(separator = ". ")
        },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HistoryNavButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 54.dp, minHeight = 54.dp),
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

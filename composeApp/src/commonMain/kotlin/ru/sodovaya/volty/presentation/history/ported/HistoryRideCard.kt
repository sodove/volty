// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape HistoryRideRow.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class HistoryRideCardText(val title: String, val subtitle: String, val details: String?)

/** The upstream row hierarchy/preview proportions, adapted to Volty typography,
 * source-aware segmented routes and content that may grow with system font size.
 */
@Composable
fun HistoryRideCard(text: HistoryRideCardText, segments: List<HistoryMapSegment>, selected: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.heightIn(min = 88.dp).padding(end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(86.dp).height(88.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                HistoryRoutePreview(segments, accent, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(text.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                text.details?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from Vescape RouteSparkline.tsx, revision 2527c346bbdb25d4e018fdd68e916621e20fac27.
// Original project author: Kacper Kozak. See README.md and LICENSE in this directory.
package ru.sodovaya.volty.presentation.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity

/** Route-only fallback: no native map, tile request or guessed GPS position.
 * Separate source segments remain separate Canvas paths. Marks identify loaded
 * route points; no green/red full-trip endpoint claims for a partial preview.
 */
@Composable
fun HistoryRoutePreview(segments: List<HistoryMapSegment>, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx().toDouble() }
        val height = with(density) { maxHeight.toPx().toDouble() }
        val paths = remember(segments, width, height) { historyPreviewPaths(segments, width, height, 8 * density.density.toDouble()) }
        Canvas(Modifier.matchParentSize()) {
            if (paths.isEmpty()) drawLine(color.copy(alpha = 0.35f), Offset(size.width / 2 - 14, size.height / 2),
                Offset(size.width / 2 + 14, size.height / 2), strokeWidth = 2f, cap = StrokeCap.Round)
            paths.forEach { points ->
                val path = Path()
                points.forEachIndexed { index, point ->
                    if (index == 0) path.moveTo(point.x.toFloat(), point.y.toFloat()) else path.lineTo(point.x.toFloat(), point.y.toFloat())
                }
                drawPath(path, color, style = Stroke(width = 2 * density.density, cap = StrokeCap.Round))
                points.firstOrNull()?.let { drawCircle(color, radius = 2 * density.density, center = Offset(it.x.toFloat(), it.y.toFloat())) }
                points.lastOrNull()?.let { drawCircle(color, radius = 2 * density.density, center = Offset(it.x.toFloat(), it.y.toFloat())) }
            }
        }
    }
}

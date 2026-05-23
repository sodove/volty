package com.volty.app.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material3.MaterialTheme

@Composable
fun SparklineGraph(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    glowAlpha: Float = 0.15f
) {
    if (values.size < 2) return
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
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
        drawPath(path = fill, color = color.copy(alpha = glowAlpha))
        drawPath(path = path, color = color, style = Stroke(width = 2.5f))
    }
}

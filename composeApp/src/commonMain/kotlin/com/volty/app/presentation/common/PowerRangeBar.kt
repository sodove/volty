package com.volty.app.presentation.common

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PowerRangeBar(
    min: Float,
    peak: Float,
    now: Float,
    modifier: Modifier = Modifier,
    track: Color = Color.White.copy(alpha = 0.2f),
    fill: Color = Color.White.copy(alpha = 0.6f),
    marker: Color = Color.White
) {
    if (peak <= min) return
    val nowClamped = now.coerceIn(min, peak)
    val nowFraction = ((nowClamped - min) / (peak - min)).coerceIn(0f, 1f)

    Canvas(modifier = modifier) {
        val h = 4.dp.toPx()
        val w = size.width
        val y = size.height / 2f
        // Track
        drawRoundRect(
            color = track,
            topLeft = Offset(0f, y - h / 2),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
        )
        // Filled portion = full bar (since min..peak fills the whole track)
        drawRoundRect(
            color = fill,
            topLeft = Offset(0f, y - h / 2),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2)
        )
        // Now marker
        val markerX = nowFraction * w
        drawRoundRect(
            color = marker,
            topLeft = Offset(markerX - 1.dp.toPx(), y - 4.dp.toPx()),
            size = Size(2.dp.toPx(), 8.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx())
        )
    }
}

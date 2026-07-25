package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private const val START_ANGLE = 135f
private const val SWEEP = 270f

/**
 * The Clean hero: SPEED on the outer arc, the rider's chosen metric on the
 * inner one, with the readout composed in the middle. Two concentric rings
 * rather than two gauges because the two numbers a rider must not miss belong
 * in one glance (see the Part B spec, §7).
 */
@Composable
fun RadialGauge(
    speedFraction: Float,
    secondaryFraction: Float,
    speedColor: Color,
    secondaryColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val speed by animateFloatAsState(
        targetValue = speedFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220), label = "speedArc"
    )
    val secondary by animateFloatAsState(
        targetValue = secondaryFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 220), label = "secondaryArc"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outerWidth = 14.dp.toPx()
            val innerWidth = 12.dp.toPx()
            val gap = 10.dp.toPx()

            fun ring(inset: Float, width: Float, color: Color, fraction: Float) {
                val side = size.minDimension - 2f * (inset + width / 2f)
                if (side <= 0f) return // degenerate layout (box too small for this ring) — draw nothing rather than pass a negative Size to drawArc
                val topLeft = Offset(inset + width / 2f, inset + width / 2f)
                val arcSize = Size(side, side)
                drawArc(trackColor, START_ANGLE, SWEEP, false, topLeft, arcSize, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                if (fraction > 0f) {
                    drawArc(color, START_ANGLE, SWEEP * fraction, false, topLeft, arcSize, style = Stroke(width, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
            }
            ring(0f, outerWidth, speedColor, speed)
            ring(outerWidth + gap, innerWidth, secondaryColor, secondary)
        }
        content()
    }
}

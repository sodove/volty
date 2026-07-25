package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Colour roles for the Classic dial, all sourced from Material You so the
 * skeuomorphic style still themes with the app rather than hardcoding the
 * grey/white VESC palette the mockup used.
 */
data class DialColors(
    val face: Color,
    val rim: Color,
    val tickMinor: Color,
    val tickMajor: Color,
    val label: Color,
    val value: Color,
    val needle: Color,
    val arc: Color,
    val danger: Color
)

/** Maps the scheme onto [DialColors]; [accent] drives the needle/arc (severity colour later). */
@Composable
fun rememberDialColors(accent: Color = MaterialTheme.colorScheme.primary): DialColors {
    // Deliberately not wrapped in `remember` keyed on the ColorScheme instance: M3's
    // ColorScheme mutates its slots in place (dynamic theme / dark-light switches don't
    // change object identity), so keying on it would risk serving stale colours.
    val scheme = MaterialTheme.colorScheme
    return DialColors(
        face = scheme.surfaceContainerHighest,
        rim = scheme.outlineVariant,
        tickMinor = scheme.outlineVariant,
        tickMajor = scheme.onSurfaceVariant,
        label = scheme.onSurfaceVariant,
        value = scheme.onSurface,
        needle = accent,
        arc = accent,
        danger = scheme.error
    )
}

// Fixed text styles for the dial's own drawing (colour is applied per-draw via drawText's
// `color` param, not baked into the style, so a theme change never forces a re-measure).
private val NumberTextStyle = TextStyle(fontSize = 11.sp)
private val LabelTextStyle = TextStyle(fontSize = 11.sp, letterSpacing = 1.5.sp)
private val ValueTextStyle = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold)
private val UnitTextStyle = TextStyle(fontSize = 11.sp)

private val DialPadding = 4.dp

/**
 * The Classic dial: a skeuomorphic VESC-style instrument the rider can pick per vehicle
 * as an alternative to the Clean [RadialGauge]. Everything is sized off `size.minDimension`
 * so one composable serves every dial in an eight-up layout.
 *
 * The mockup this replaces (`docs/design/ride-dashboard-mockup.html`, "Classic VESC" toggle)
 * had its scale numbers collide with the centre readout. This implementation avoids that by
 * construction: the numbers sit on a radius strictly inside the tick marks (computed from
 * their own measured size, not a guessed inset), the centre readout is stacked from measured
 * text heights rather than hand-picked offsets, and — as a real guard rather than a hope —
 * the centre block is shrunk (never enlarged) if its measured footprint would otherwise reach
 * the number ring.
 */
@Composable
fun DialGauge(
    value: Float,
    scale: DialScale,
    label: String,
    unit: String,
    valueText: String,
    modifier: Modifier = Modifier,
    colors: DialColors = rememberDialColors(),
    dangerFrom: Float? = null,
    showValueArc: Boolean = true
) {
    val animatedFraction by animateFloatAsState(
        targetValue = DialGeometry.fraction(value, scale),
        animationSpec = tween(durationMillis = 220),
        label = "dialNeedle"
    )

    val textMeasurer = rememberTextMeasurer()
    val decimals = if (abs(scale.max) < 10f) 1 else 0

    // Text is measured up front (not inside the draw lambda, which cannot call @Composable
    // functions anyway) and keyed so it is NOT re-measured every animation frame — only the
    // needle's angle depends on the animated value, everything text-shaped is static per input.
    val numberLayouts: List<Pair<Float, TextLayoutResult>> = remember(scale, decimals) {
        DialGeometry.majorValues(scale).map { v ->
            v to textMeasurer.measure(formatFixed(v, decimals), NumberTextStyle)
        }
    }
    val labelLayout = remember(label) { textMeasurer.measure(label, LabelTextStyle) }
    val valueLayout = remember(valueText) { textMeasurer.measure(valueText, ValueTextStyle) }
    val unitLayout = remember(unit) { textMeasurer.measure(unit, UnitTextStyle) }

    Canvas(modifier) {
        if (size.minDimension <= 0f) return@Canvas // degenerate layout — nothing to draw
        val radius = size.minDimension / 2f - DialPadding.toPx()
        if (radius <= 0f) return@Canvas // box too small even for the padding — draw nothing rather than negative geometry

        val center = Offset(size.width / 2f, size.height / 2f)
        fun pointAt(angleDeg: Float, r: Float): Offset {
            val rad = angleDeg * (PI.toFloat() / 180f)
            return Offset(center.x + r * cos(rad), center.y + r * sin(rad))
        }

        // --- geometry, outer to inner (all fractions of `radius`, so the dial is self-similar at any size) ---
        val rimStrokeWidth = 1.5.dp.toPx()
        val rimInset = radius * 0.03f
        val tickOuterR = radius * 0.90f
        val majorTickLen = radius * 0.13f
        val minorTickLen = radius * 0.06f
        val tickMajorStroke = 2.5.dp.toPx()
        val tickMinorStroke = 1.5.dp.toPx()
        val arcR = radius * 0.78f
        val arcStrokeWidth = 4.dp.toPx()
        val needleTipR = arcR
        val hubR = radius * 0.07f

        // Numbers sit just inside the major ticks' inner end, offset by their OWN measured
        // half-height (not a guessed constant) plus a small fixed gap.
        val maxNumberHalfHeight = (numberLayouts.maxOfOrNull { it.second.size.height } ?: 0) / 2f
        val numberGap = 4.dp.toPx()
        val numberR = (tickOuterR - majorTickLen - numberGap - maxNumberHalfHeight).coerceAtLeast(0f)

        // 1. Face
        drawCircle(color = colors.face, radius = radius, center = center)
        drawCircle(
            color = colors.rim,
            radius = (radius - rimInset).coerceAtLeast(0f),
            center = center,
            style = Stroke(width = rimStrokeWidth)
        )

        // 2. Value arc (scale minimum .. current value), animated so it sweeps with the needle.
        if (showValueArc) {
            drawArc(
                color = colors.arc,
                startAngle = DialGeometry.START_ANGLE,
                sweepAngle = DialGeometry.SWEEP * animatedFraction,
                useCenter = false,
                topLeft = Offset(center.x - arcR, center.y - arcR),
                size = Size(arcR * 2f, arcR * 2f),
                style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round)
            )
        }

        // 3. Danger band (dangerFrom .. scale max) — a static zone marker, independent of the
        // current value; drawn on top of the value arc so the danger colour always wins there.
        dangerFrom?.let { threshold ->
            DialGeometry.dangerSweep(threshold, scale)?.let { (start, sweep) ->
                drawArc(
                    color = colors.danger,
                    startAngle = start,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(center.x - arcR, center.y - arcR),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = arcStrokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // 4. Ticks
        DialGeometry.tickAngles(scale).forEach { tick ->
            val len = if (tick.isMajor) majorTickLen else minorTickLen
            val outer = pointAt(tick.degrees, tickOuterR)
            val inner = pointAt(tick.degrees, tickOuterR - len)
            drawLine(
                color = if (tick.isMajor) colors.tickMajor else colors.tickMinor,
                start = outer,
                end = inner,
                strokeWidth = if (tick.isMajor) tickMajorStroke else tickMinorStroke,
                cap = StrokeCap.Round
            )
        }

        // 5. Numbers — anchored on `numberR`, strictly inside the tick marks.
        numberLayouts.forEach { (v, layoutResult) ->
            val angle = DialGeometry.angleFor(v, scale)
            val anchor = pointAt(angle, numberR)
            drawText(
                textLayoutResult = layoutResult,
                color = colors.label,
                topLeft = Offset(
                    anchor.x - layoutResult.size.width / 2f,
                    anchor.y - layoutResult.size.height / 2f
                )
            )
        }

        // 6. Needle — a filled triangle from a small hub to the animated angle.
        val needleAngle = DialGeometry.START_ANGLE + DialGeometry.SWEEP * animatedFraction
        val tip = pointAt(needleAngle, needleTipR)
        val baseHalf = hubR * 0.7f
        val baseA = pointAt(needleAngle + 90f, baseHalf)
        val baseB = pointAt(needleAngle - 90f, baseHalf)
        drawPath(
            path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(baseA.x, baseA.y)
                lineTo(baseB.x, baseB.y)
                close()
            },
            color = colors.needle
        )
        drawCircle(color = colors.needle, radius = hubR, center = center)

        // 7. Centre readout — stacked from MEASURED heights (never guessed offsets), then
        // shrunk (never enlarged) only if its measured footprint would otherwise reach the
        // number ring. This is the acceptance criterion: a real collision guard, not a hope
        // that the chosen proportions happen to leave enough room.
        val spacing = 2.dp.toPx()
        val centerTotalHeight =
            labelLayout.size.height + spacing + valueLayout.size.height + spacing + unitLayout.size.height
        val centerMaxWidth = maxOf(labelLayout.size.width, valueLayout.size.width, unitLayout.size.width)
        val centerHalfDiagonal = sqrt(
            (centerMaxWidth / 2f) * (centerMaxWidth / 2f) + (centerTotalHeight / 2f) * (centerTotalHeight / 2f)
        )

        val maxNumberHalfDiagonal = numberLayouts.maxOfOrNull { (_, l) ->
            val hw = l.size.width / 2f
            val hh = l.size.height / 2f
            sqrt(hw * hw + hh * hh)
        } ?: 0f
        val collisionMargin = 4.dp.toPx()
        val safeRadius = (numberR - maxNumberHalfDiagonal - collisionMargin).coerceAtLeast(0f)
        val centerScale = if (centerHalfDiagonal > 0f) {
            (safeRadius / centerHalfDiagonal).coerceIn(0.5f, 1f)
        } else {
            1f
        }

        var y = center.y - centerTotalHeight / 2f
        val labelTop = y
        y += labelLayout.size.height + spacing
        val valueTop = y
        y += valueLayout.size.height + spacing
        val unitTop = y

        // Scale about the dial centre via the canvas transform (not the DrawScope `scale(...)`
        // helper, which would be shadowed by this function's own `scale: DialScale` parameter).
        val canvas = drawContext.canvas
        canvas.save()
        canvas.translate(center.x, center.y)
        canvas.scale(centerScale, centerScale)
        canvas.translate(-center.x, -center.y)
        drawText(
            labelLayout,
            color = colors.label,
            topLeft = Offset(center.x - labelLayout.size.width / 2f, labelTop)
        )
        drawText(
            valueLayout,
            color = colors.value,
            topLeft = Offset(center.x - valueLayout.size.width / 2f, valueTop)
        )
        drawText(
            unitLayout,
            color = colors.label,
            topLeft = Offset(center.x - unitLayout.size.width / 2f, unitTop)
        )
        canvas.restore()
    }
}

package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.PI
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
    // change object identity), so keying a remember on it would risk serving stale colours.
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

private val DialPadding = 4.dp

// Font sizes are fractions of the dial's own radius — the same pattern as every other
// geometric constant below (tickOuterR, majorTickLen, arcR, hubR), and the same approach the
// reference mockup uses for its own dial text (`font-size = s * const`; see
// docs/design/ride-dashboard-mockup.html's `dial()` function — a reasonable typographic ratio
// to borrow even though its placement maths is exactly what this file avoids porting). This is
// what gives every instance in an eight-up cluster the same proportions, small dial or hero,
// instead of a fixed sp guess that only happened to leave headroom at one particular size.
// The fractions themselves live in `DialGeometry` (not here) so `DialGeometryTest` can derive
// real dial geometry from the exact numbers this composable draws with.

/**
 * Raw device pixels -> Sp, deliberately bypassing the system font-scale multiplier: the dial's
 * digits are geometry sized to fit the space this specific dial measured out for them, not body
 * text that should grow with an accessibility font-scale setting (a large font-scale would
 * otherwise blow past the collision guard's own margins). [Density.toSp] divides by
 * `density * fontScale`; Compose's text renderer multiplies back by that same `density *
 * fontScale` at draw time, so the two cancel and the rendered size lands at exactly [px]
 * regardless of the user's font-scale setting.
 */
private fun Density.pxToSp(px: Float): TextUnit = px.coerceAtLeast(0f).toSp()

/**
 * The Classic dial: a skeuomorphic VESC-style instrument the rider can pick per vehicle
 * as an alternative to the Clean [RadialGauge]. Everything is sized off the dial's own
 * measured radius so one composable serves every dial in an eight-up layout, from the small
 * corner dials up to the hero.
 *
 * The mockup this replaces (`docs/design/ride-dashboard-mockup.html`, "Classic VESC" toggle)
 * had its scale numbers collide with the centre readout because its numbers sat on a radius
 * too close to the centre for its centre-text size. This implementation avoids that with two
 * cooperating measures: (1) both the tick numbers and the centre readout are sized as fractions
 * of the dial's own radius (not fixed sp), which is what makes every instance in an eight-up
 * cluster — small corner dial or hero — keep the same proportions instead of a fixed sp guess
 * that only happened to leave headroom at one particular size; and (2) a shrink transform that
 * scales the centre block down (never up) whenever its measured footprint gets close to the
 * number ring. Sizing alone is not enough to guarantee 1f (no shrink) at every real dial: the
 * smallest corner slot (`ClusterPlacement`'s 0.32-of-cluster-width dials) with a localized
 * Russian label engages this mildly (~0.9, comfortably above the 0.5 floor) rather than sitting
 * at a flat 1.0 — see `DialGeometryTest`'s `centre_scale_on_the_real_localized_small_corner_dial_...`
 * test, which derives that number from the committed font fractions instead of hand-picking one.
 * The shrink transform is therefore a genuine, load-bearing part of how this stays
 * collision-free, not only a rare-case safety net for a pathologically long caller-supplied
 * value string (though it still floors at 0.5f and handles that case too).
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
    showValueArc: Boolean = true,
    /** True for the one dial `ClassicEmphasis` (presentation.ride) maps the rider's chosen
     * secondary gauge onto (spec §7.2's Classic half of the "Inner gauge" picker) — drawn with a
     * heavier rim so it reads as emphasised without touching colour, which severity alone owns. */
    emphasized: Boolean = false
) {
    val animatedFraction by animateFloatAsState(
        targetValue = DialGeometry.fraction(value, scale),
        animationSpec = tween(durationMillis = 220),
        label = "dialNeedle"
    )

    val textMeasurer = rememberTextMeasurer()
    val decimals = DialGeometry.decimalsFor(scale.max)
    val density = LocalDensity.current

    // The dial's own pixel size, captured once layout has happened (starts at zero before the
    // first pass; the guards in the draw lambda simply draw nothing until a real size arrives).
    // Text can only be measured from a @Composable context, so the radius-derived font sizes
    // and layouts live here, not in the draw lambda.
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val radius = with(density) {
        (minOf(canvasSize.width, canvasSize.height) / 2f) - DialPadding.toPx()
    }

    val numberStyle = remember(radius) {
        TextStyle(fontSize = density.pxToSp(radius * DialGeometry.NumberFontFraction))
    }
    val labelStyle = remember(radius) {
        TextStyle(
            fontSize = density.pxToSp(radius * DialGeometry.LabelFontFraction),
            letterSpacing = density.pxToSp(radius * DialGeometry.LabelLetterSpacingFraction)
        )
    }
    val valueStyle = remember(radius) {
        TextStyle(fontSize = density.pxToSp(radius * DialGeometry.ValueFontFraction), fontWeight = FontWeight.Bold)
    }
    val unitStyle = remember(radius) {
        TextStyle(fontSize = density.pxToSp(radius * DialGeometry.UnitFontFraction))
    }

    // Measured once per (radius, text) pair — not every animation frame, since only the
    // needle's angle depends on the animated value, nothing text-shaped does.
    val numberLayouts: List<Pair<Float, TextLayoutResult>> = remember(scale, decimals, numberStyle) {
        DialGeometry.majorValues(scale).map { v ->
            v to textMeasurer.measure(formatFixed(v, decimals), numberStyle)
        }
    }
    val labelLayout = remember(label, labelStyle) { textMeasurer.measure(label, labelStyle) }
    val valueLayout = remember(valueText, valueStyle) { textMeasurer.measure(valueText, valueStyle) }
    val unitLayout = remember(unit, unitStyle) { textMeasurer.measure(unit, unitStyle) }

    Canvas(modifier.onSizeChanged { canvasSize = it }) {
        if (size.minDimension <= 0f) return@Canvas // degenerate layout — nothing to draw
        if (radius <= 0f) return@Canvas // box too small even for the padding — draw nothing rather than negative geometry

        val center = Offset(size.width / 2f, size.height / 2f)
        fun pointAt(angleDeg: Float, r: Float): Offset {
            val rad = angleDeg * (PI.toFloat() / 180f)
            return Offset(center.x + r * cos(rad), center.y + r * sin(rad))
        }

        // --- geometry, outer to inner (all fractions of `radius`, so the dial is self-similar at any size) ---
        // Emphasis (spec §7.2) is stroke-width only, never a colour change — severity is the
        // only thing allowed to speak in red/amber, so the "chosen dial" cue has to be shape.
        val rimStrokeWidth = (if (emphasized) 3.75.dp else 1.5.dp).toPx()
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

        // Numbers sit just inside the major ticks' inner end. The margin is each label's own
        // measured HALF-DIAGONAL, not half-height: the 270° sweep passes near-horizontal
        // (labels sitting almost level with the dial centre), and a wide string like "-60" or
        // "120" reaches further outward there than its height alone would suggest.
        val maxNumberHalfDiagonal = numberLayouts.maxOfOrNull { (_, l) ->
            val hw = l.size.width / 2f
            val hh = l.size.height / 2f
            sqrt(hw * hw + hh * hh)
        } ?: 0f
        val numberGap = 4.dp.toPx()
        val numberR = DialGeometry.numberRadius(tickOuterR, majorTickLen, numberGap, maxNumberHalfDiagonal)

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

        // 7. Centre readout — stacked from MEASURED heights (never guessed offsets). Sized
        // proportionally (see DialGeometry.NumberFontFraction et al.), which keeps it close to
        // the number ring by design but does not guarantee zero shrink on its own — the shrink
        // below engages mildly even on the smallest real dial (see DialGeometry.centreScale's
        // KDoc), not only as a rare-case safety net for a pathologically long value string.
        val spacing = 2.dp.toPx()
        val centerTotalHeight =
            labelLayout.size.height + spacing + valueLayout.size.height + spacing + unitLayout.size.height
        val centerMaxWidth = maxOf(labelLayout.size.width, valueLayout.size.width, unitLayout.size.width)
        val centerHalfDiagonal = sqrt(
            (centerMaxWidth / 2f) * (centerMaxWidth / 2f) + (centerTotalHeight / 2f) * (centerTotalHeight / 2f)
        )

        val collisionMargin = 4.dp.toPx()
        val centerScale = DialGeometry.centreScale(numberR, maxNumberHalfDiagonal, collisionMargin, centerHalfDiagonal)

        var y = center.y - centerTotalHeight / 2f
        val labelTop = y
        y += labelLayout.size.height + spacing
        val valueTop = y
        y += valueLayout.size.height + spacing
        val unitTop = y

        // `scale` here resolves to DrawTransform's member function, not this composable's own
        // `scale: DialScale` parameter: DialScale has no `invoke` operator, so it is never a
        // candidate for call syntax — no shadowing risk. withTransform also saves/restores the
        // canvas transform in a finally block, so a throw between the three drawText calls
        // below can't leave a dirty transform for the rest of the frame.
        withTransform({
            scale(scaleX = centerScale, scaleY = centerScale, pivot = center)
        }) {
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
        }
    }
}

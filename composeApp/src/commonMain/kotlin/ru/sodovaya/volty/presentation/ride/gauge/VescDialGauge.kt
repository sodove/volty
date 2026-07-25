package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.animation.core.EaseOutCirc
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import ru.sodovaya.volty.util.formatFixed
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The six colour roles `mobile/CustomGauge.qml` names, mapped onto Material You so the instrument
 * themes with the app instead of hardcoding VESC's own palette.
 *
 * The important one is [face]. The QML paints the dial's disc in the *theme's own background*
 * (line 93: `darkBackground` in dark mode, `normalBackground` in light) — the same colour as the
 * page behind it. That is not laziness: it is why VESC's overlapping dials merge into a single
 * instrument panel rather than reading as a scatter of separate plates. A dial drawn in a
 * contrasting surface colour on top of a page gets a visible edge, and eight of them float apart.
 * So [face] is `surface`, never a tinted or accented container.
 */
data class VescDialColors(
    /** QML line 93. Deliberately the page's own background colour — see the class doc. */
    val face: Color,
    /** QML line 228: `lightestBackground`, the bright side of the bezel's metal gradient. */
    val bezelLight: Color,
    /** QML line 229: `darkBackground`, the shadowed side of the bezel's metal gradient. */
    val bezelDark: Color,
    /** QML line 53: `coveredColor` (`lightText`) — ticks the needle has already swept past. */
    val covered: Color,
    /** QML line 54: `uncoveredColor` (`disabledText`) — ticks still ahead of the needle. */
    val uncovered: Color,
    /** QML lines 108, 120, 136: `lightText`, the centre caption / value / unit. */
    val text: Color
)

/**
 * The default mapping of [VescDialColors] onto the active scheme.
 *
 * Not wrapped in `remember`: M3's `ColorScheme` mutates its slots in place (a dynamic-colour or
 * light/dark switch does not change the object's identity), so keying a `remember` on it would
 * risk serving stale colours — the same reasoning as `rememberDialColors`.
 */
@Composable
fun rememberVescDialColors(): VescDialColors {
    val scheme = MaterialTheme.colorScheme
    return VescDialColors(
        face = scheme.surface,
        bezelLight = scheme.surfaceBright,
        bezelDark = scheme.surfaceDim,
        covered = scheme.onSurface,
        // VESC's `disabledText` is a flat mid-grey against near-white `lightText`. M3 expresses
        // the same "present but inactive" step as its standard 38% disabled-content opacity,
        // which keeps the contrast ratio between swept and unswept ticks at every theme.
        uncovered = scheme.onSurface.copy(alpha = 0.38f),
        text = scheme.onSurface
    )
}

/**
 * A faithful Compose port of VESC Tool's `mobile/CustomGauge.qml` (463 lines): face (`z:0`) ->
 * ticks -> tick labels -> centre text -> needle (`z:2`) -> trace glow and bezel (`z:3`).
 *
 * The middle three layers are NOT in the original's own paint order. `CustomGauge.qml` declares
 * the centre `Text` block at :101-140 BEFORE the tick/label `Repeater`s at :378-460, and all three
 * sit at the same `z:0` — Qt paints same-`z` siblings in declaration order, so the original in fact
 * paints ticks and labels OVER the centre text; it is layered by declaration order, not unlayered.
 * This file paints centre text last instead. That never differs on screen — the `0.3R` centre
 * stack never reaches the `0.66R` label ring (see [VescDialMetrics.needleBaseRadius]'s doc for the
 * radii) — and is the safer order to keep: a future change to the ticks or labels can't accidentally
 * grow into the readout. Kept rather than reversed to match the QML's declaration order verbatim.
 *
 * Four things the original does that a from-memory "skeuomorphic dial" reliably gets wrong, all
 * of which this file reproduces exactly:
 *
 *  1. **The needle is a blade at the rim, not a line from the centre.** It is `0.22R` long, sits
 *     `0.05R` in from the rim, and rotates about the dial centre without ever coming near it
 *     (QML lines 142-153; see [VescDialMetrics.needleBaseRadius], which is `0.73R`). Nothing is
 *     drawn between the blade and the centre — no spindle, no hub cap. The readout can therefore
 *     never be covered, which is a property of the geometry rather than of the draw order.
 *  2. **The readout is `0.3R`** (QML line 107) — two and a half times every other font on the
 *     dial. The number is the loudest thing on the instrument and the scale is background.
 *  3. **The face is the theme's own background** — see [VescDialColors].
 *  4. **There is no red danger wedge.** VESC has none anywhere in `CustomGauge.qml`; severity is
 *     carried entirely by [nibColor], the needle's own colour, chosen per-gauge by the caller.
 *
 * **The glass highlight (QML lines 265-305) is not ported, because in the original it draws
 * nothing.** The QML builds it from a `RadialGradient` masked by a second `RadialGradient` through
 * an `OpacityMask`, none of which Compose has an equivalent for — but before inventing a
 * substitute it is worth doing the arithmetic, and the arithmetic says the effect is inert.
 * The source layer (`glassEffect1`) is not the thin rim glare it looks like: its stops are
 * `0.495` white then `0.5` transparent, and with no stop below `0.495` everything inside clamps to
 * white, so it is a solid white DISC of radius `0.495 * 0.96R = 0.475R` centred on the dial. The
 * mask (`glassEffect2`) is an ellipse of radius `1.9R` whose centre is offset to `(-0.16R,
 * +1.34R)` — `1.3495R` away from the dial centre — and whose first non-transparent stop is at
 * position `0.98`, i.e. `1.862R` out. The farthest point of the white disc from that centre is
 * `1.3495R + 0.475R = 1.825R`, which is *inside* `1.862R`, so the mask's alpha is zero across the
 * whole disc and the `OpacityMask` multiplies the entire layer away. Porting it faithfully means
 * drawing nothing; drawing a sheen anyway would be inventing a new effect and calling it a port,
 * and it would land right where the scale numbers and the readout live. If a highlight is wanted
 * later it should be a deliberate, separately-reviewed addition.
 *
 * Every length here — including all four font sizes — is a fraction of the dial's measured
 * radius, taken from [VescDialMetrics]. There is no `dp` and no `sp` constant in this file, which
 * is what lets one definition serve a small cluster dial and a hero dial with no per-size tuning.
 *
 * @param value the live reading. Animated with ease-out-circ over 100 ms (QML lines 95-100); the
 *   animated value drives the needle, the readout, the covered/uncovered ticks and the glow
 *   together, exactly as the QML's single `Behavior on value` does.
 * @param range the gauge's angle sweep and value range (Task 1's port of the QML property block).
 * @param typeText the caption above the readout, upper-cased (QML lines 126-140). Honours `\n`,
 *   and the block grows *upward* from the readout's top edge.
 * @param unitText the unit below the readout, upper-cased (QML lines 112-124).
 * @param nibColor the needle's colour, and — lightened 1.5x (QML line 50) — the trace glow's.
 * @param precision decimal places for the readout (QML line 105, `value.toFixed(precision)`).
 * @param centerTextVisible QML line 57. The Battery gauge sets this false and overlays its own
 *   centre content; the needle, scale, glow and bezel are unaffected.
 * @param tickmarkScale QML line 48: scale numbers are multiplied by this before rounding, so a
 *   0..15000 rpm dial can be labelled 0..15 with a "k" [tickmarkSuffix].
 * @param tickmarkSuffix QML line 47.
 * @param valueTextOverride replaces the formatted readout when non-null. Not in the QML, which
 *   always has a number to show; this app does not — a motor with no temperature sensor, a
 *   controller reporting no speed and a standing-still consumption are all genuinely UNKNOWN, and
 *   printing the `0` the needle rests at would be a lie the rider cannot tell from a real reading.
 */
@Composable
fun VescDialGauge(
    value: Float,
    range: VescGaugeRange,
    typeText: String,
    unitText: String,
    nibColor: Color,
    modifier: Modifier = Modifier,
    precision: Int = 0,
    centerTextVisible: Boolean = true,
    tickmarkScale: Double = 1.0,
    tickmarkSuffix: String = "",
    valueTextOverride: String? = null,
    colors: VescDialColors = rememberVescDialColors()
) {
    // QML lines 95-100: `Behavior on value { NumberAnimation { easing.type: Easing.OutCirc;
    // duration: 100 } }`. Deliberately short and deliberately decelerating — the needle should
    // arrive rather than drift, or a gauge reading 60 times a second never settles.
    val animated by animateFloatAsState(
        targetValue = value,
        animationSpec = tween(durationMillis = 100, easing = EaseOutCirc),
        label = "vescDialValue"
    )

    // Cache generously: one dial measures its caption, readout, unit and up to a dozen scale
    // numbers, and the default cache of 8 would thrash on every frame of the animation.
    val textMeasurer = rememberTextMeasurer(cacheSize = 64)

    Canvas(modifier) {
        val diameter = size.minDimension
        if (diameter <= 0f) return@Canvas // degenerate layout — draw nothing rather than NaNs
        // QML line 29: `outerRadius: width/2`, with `height: width` (line 30) forcing a square.
        val outerRadius = diameter / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val reading = animated.toDouble()

        drawVescFace(center, outerRadius, colors.face)
        drawVescTicks(center, outerRadius, range, reading, colors)
        drawVescTickLabels(
            center, outerRadius, range, reading, colors, textMeasurer, tickmarkScale, tickmarkSuffix
        )
        if (centerTextVisible) {
            drawVescCenterText(
                center = center,
                outerRadius = outerRadius,
                textMeasurer = textMeasurer,
                caption = typeText.uppercase(),
                valueText = valueTextOverride ?: formatFixed(animated, precision),
                unit = unitText.uppercase(),
                color = colors.text
            )
        }
        drawVescNeedle(center, outerRadius, range.valueToAngle(reading), nibColor)
        drawVescTrace(center, outerRadius, range, reading, nibColor)
        drawVescBezel(center, outerRadius, colors.bezelLight, colors.bezelDark)
    }
}

// ---------------------------------------------------------------------------------------------
// The layers, in the QML's draw order. Each one is a straight transcription: the arithmetic they
// need lives in VescDialMetrics / VescGaugeRange / VescDialGeometry, where it is under test.
// ---------------------------------------------------------------------------------------------

/**
 * A point on the dial at a given gauge angle and radius. The `cos`/`sin` here is the only
 * trigonometry in the port — Task 1's geometry deliberately stops one step short of it (see
 * [VescDialGeometry.labelRadius]), because that is the step where numbers become pixels.
 */
private fun radialOffset(center: Offset, gaugeAngleDegrees: Double, radius: Double): Offset {
    val radians = VescDialGeometry.screenAngleRadians(gaugeAngleDegrees)
    return Offset(
        center.x + (radius * cos(radians)).toFloat(),
        center.y + (radius * sin(radians)).toFloat()
    )
}

/** QML lines 85-93: the face, `z:0`. */
private fun DrawScope.drawVescFace(center: Offset, outerRadius: Float, face: Color) {
    drawCircle(color = face, radius = outerRadius, center = center)
}

/**
 * QML lines 316-335 and 378-422: the major and minor tick rectangles.
 *
 * The QML positions each one by translating `-outerRadius + tickmarkInset` along `y` (i.e. up)
 * and rotating about the dial centre, so the rectangle's TOP edge lands on
 * [VescDialMetrics.tickOuterRadius] and its body grows inward — hence a line drawn from the
 * inner radius out to that one. The QML's `- tickmarkWidthAsAngle / 2` (line 393) exists only to
 * re-centre a rectangle that grows rightward from the radial line; a stroked line is already
 * centred on its path, so that correction has no counterpart here.
 */
private fun DrawScope.drawVescTicks(
    center: Offset,
    outerRadius: Float,
    range: VescGaugeRange,
    reading: Double,
    colors: VescDialColors
) {
    if (!range.hasTickmarks) return
    val r = outerRadius.toDouble()
    val outer = VescDialMetrics.tickOuterRadius(r)

    val majorInner = VescDialMetrics.majorTickInnerRadius(r)
    val majorWidth = (r * VescDialMetrics.MAJOR_TICK_WIDTH_FRACTION).toFloat()
    for (index in 0 until range.tickmarkCount) {
        val angle = range.tickmarkAngleFromIndex(index)
        val covered = VescDialGeometry.isCovered(range.tickmarkValueFromIndex(index), reading)
        drawLine(
            color = if (covered) colors.covered else colors.uncovered,
            start = radialOffset(center, angle, majorInner),
            end = radialOffset(center, angle, outer),
            strokeWidth = majorWidth,
            cap = StrokeCap.Butt // a QML Rectangle has square ends, not round ones
        )
    }

    val minorInner = VescDialMetrics.minorTickInnerRadius(r)
    val minorWidth = (r * VescDialMetrics.MINOR_TICK_WIDTH_FRACTION).toFloat()
    for (index in 0 until range.totalMinorTickmarkCount) {
        val angle = range.minorTickmarkAngleFromIndex(index)
        val covered = VescDialGeometry.isCovered(range.tickmarkValueFromMinorIndex(index), reading)
        drawLine(
            color = if (covered) colors.covered else colors.uncovered,
            start = radialOffset(center, angle, minorInner),
            end = radialOffset(center, angle, outer),
            strokeWidth = minorWidth,
            cap = StrokeCap.Butt
        )
    }
}

/**
 * QML lines 306-315 and 423-460: the scale numbers, each CENTRED on
 * [VescDialGeometry.labelRadius] (the `- width/2` / `- height/2` in lines 456-457 make the
 * computed point the label's centre, not its corner) and coloured by the same covered/uncovered
 * rule as its tick.
 */
private fun DrawScope.drawVescTickLabels(
    center: Offset,
    outerRadius: Float,
    range: VescGaugeRange,
    reading: Double,
    colors: VescDialColors,
    textMeasurer: TextMeasurer,
    tickmarkScale: Double,
    tickmarkSuffix: String
) {
    if (!range.hasTickmarks) return
    val r = outerRadius.toDouble()
    val labelRadius = VescDialGeometry.labelRadius(r)
    val style = TextStyle(fontSize = fontSizeFor(outerRadius, VescDialMetrics.TICK_LABEL_FONT_FRACTION))

    for (index in 0 until range.tickmarkCount) {
        val tickValue = range.tickmarkValueFromIndex(index)
        // QML line 310: `parseFloat(value * tickmarkScale).toFixed(0) + tickmarkSuffix`.
        val text = formatFixed((tickValue * tickmarkScale).toFloat(), 0) + tickmarkSuffix
        val layout = textMeasurer.measure(text, style)
        val anchor = radialOffset(center, range.tickmarkAngleFromIndex(index), labelRadius)
        drawText(
            textLayoutResult = layout,
            color = if (VescDialGeometry.isCovered(tickValue, reading)) colors.covered else colors.uncovered,
            topLeft = Offset(anchor.x - layout.size.width / 2f, anchor.y - layout.size.height / 2f)
        )
    }
}

/**
 * QML lines 101-140: the centre stack.
 *
 * The anchoring is the whole point and is easy to get subtly wrong. The *value* is centred in the
 * dial (`anchors.centerIn: parent`, line 103) — it, not the stack, is what sits on the middle.
 * The caption hangs off the value's TOP edge (`anchors.bottom: speedLabel.top`, line 132) and
 * therefore grows upward, which is what makes a two-line caption push up into the empty upper
 * half instead of shoving the number down; the unit hangs off the value's BOTTOM edge
 * (`anchors.top: speedLabel.bottom`, line 117). All three are horizontally centred.
 *
 * There is no shrink-to-fit transform here, and there must not be one: at `0.3R` for the value
 * and `0.12R` for the other two, the whole stack is comfortably inside the `0.66R` number ring by
 * construction. A dial that needs to shrink its own readout to avoid its own scale has the wrong
 * proportions, and hiding that behind a scale factor hides the bug rather than fixing it.
 */
private fun DrawScope.drawVescCenterText(
    center: Offset,
    outerRadius: Float,
    textMeasurer: TextMeasurer,
    caption: String,
    valueText: String,
    unit: String,
    color: Color
) {
    val valueStyle = TextStyle(
        fontSize = fontSizeFor(outerRadius, VescDialMetrics.VALUE_FONT_FRACTION),
        textAlign = TextAlign.Center
    )
    // `TextAlign.Center` centres each line inside the layout's own width, which for an
    // unconstrained measure is the width of the widest line — so a multi-line caption comes out
    // centred without inventing a width to constrain it to.
    val captionStyle = TextStyle(
        fontSize = fontSizeFor(outerRadius, VescDialMetrics.CAPTION_FONT_FRACTION),
        textAlign = TextAlign.Center
    )
    val unitStyle = TextStyle(
        fontSize = fontSizeFor(outerRadius, VescDialMetrics.UNIT_FONT_FRACTION),
        textAlign = TextAlign.Center
    )

    val valueLayout = textMeasurer.measure(valueText, valueStyle)
    val captionLayout = if (caption.isNotEmpty()) textMeasurer.measure(caption, captionStyle) else null
    val unitLayout = if (unit.isNotEmpty()) textMeasurer.measure(unit, unitStyle) else null

    // The anchoring itself — is a pure function of centerY plus two measured heights, tested in
    // VescDialGeometryTest — not computed inline here.
    val stack = VescDialGeometry.centerTextLayout(
        centerY = center.y.toDouble(),
        valueHeight = valueLayout.size.height.toDouble(),
        captionHeight = (captionLayout?.size?.height ?: 0).toDouble()
    )

    drawText(
        textLayoutResult = valueLayout,
        color = color,
        topLeft = Offset(center.x - valueLayout.size.width / 2f, stack.valueTop.toFloat())
    )

    if (captionLayout != null) {
        drawText(
            textLayoutResult = captionLayout,
            color = color,
            topLeft = Offset(center.x - captionLayout.size.width / 2f, stack.captionTop.toFloat())
        )
    }

    if (unitLayout != null) {
        drawText(
            textLayoutResult = unitLayout,
            color = color,
            topLeft = Offset(center.x - unitLayout.size.width / 2f, stack.unitTop.toFloat())
        )
    }
}

/**
 * QML lines 142-201: the needle, `z:2`.
 *
 * **The pivot.** The QML never rotates a needle about its own base. It declares an Item that is
 * `0.12R` wide and `0.22R` tall parked at the TOP of the dial (`y: outerRadius * 0.05`,
 * line 144), then pushes that Item's rotation origin down to `origin.y: outerRadius*(1-0.05)`
 * (line 151) — `0.95R` measured from the blade's own top edge, which lands exactly on the dial
 * centre. The blade therefore swings around the centre on an invisible arm without ever
 * approaching it: its inner end stops at [VescDialMetrics.needleBaseRadius], `0.73R`.
 *
 * Compose expresses the same thing directly: `rotate(angle, pivot = dialCentre)` around a blade
 * drawn only in the band `0.73R .. 0.95R`. There is no arm and no hub to draw, because the QML
 * draws neither — the empty space between the blade and the readout is the design.
 *
 * **The fill.** Two mirrored halves (lines 165-180 and 183-197), each a straight edge out to the
 * blade's shoulder and two quadratics tapering back to the centre line. Each half is filled with
 * a linear gradient running from its outer side edge in to the blade's tip: the nib colour
 * shaded by the needle's current angle for the first 80%, then white for the last 8%. The two
 * white bands meet along the blade's spine and read as the specular highlight on a metal pointer,
 * and because the shading factor is a function of the angle (see [VescNibShading.shadeFactor])
 * the blade catches the light differently at each point of its sweep.
 */
private fun DrawScope.drawVescNeedle(
    center: Offset,
    outerRadius: Float,
    needleAngleDegrees: Double,
    nibColor: Color
) {
    val r = outerRadius.toDouble()
    val width = (r * VescDialMetrics.NEEDLE_WIDTH_FRACTION).toFloat()
    val height = (r * VescDialMetrics.NEEDLE_LENGTH_FRACTION).toFloat()
    if (width <= 0f || height <= 0f) return
    val left = center.x - width / 2f
    val top = center.y - VescDialMetrics.needleTipRadius(r).toFloat()

    fun shaded(offsetDegrees: Double): Color =
        nibColor.shadedNib(VescNibShading.shadeFactor(needleAngleDegrees, offsetDegrees))

    rotate(degrees = needleAngleDegrees.toFloat(), pivot = center) {
        // Right half — QML lines 165-180.
        val rightHalf = Path().apply {
            moveTo(left + width / 2f, top)
            lineTo(left + width, top + height * 0.015f)
            quadraticTo(
                left + width * 0.7f, top + height / 4f,
                left + width * 0.6f, top + height * 0.9f
            )
            quadraticTo(
                left + width * 0.6f, top + height,
                left + width / 2f, top + height
            )
            close()
        }
        drawPath(
            path = rightHalf,
            brush = Brush.linearGradient(
                0.0f to shaded(-36.0),
                0.80f to shaded(0.0),
                0.92f to Color.White,
                1.0f to Color.White,
                start = Offset(left + width, top + height * 0.02f),
                end = Offset(left + width / 2f, top)
            )
        )

        // Left half — QML lines 183-197, mirrored, and lit from the opposite side (+144 / +180).
        val leftHalf = Path().apply {
            moveTo(left + width / 2f, top)
            lineTo(left, top + height * 0.015f)
            quadraticTo(
                left + width * 0.3f, top + height / 4f,
                left + width * 0.4f, top + height * 0.9f
            )
            quadraticTo(
                left + width * 0.4f, top + height,
                left + width / 2f, top + height
            )
            close()
        }
        drawPath(
            path = leftHalf,
            brush = Brush.linearGradient(
                0.0f to shaded(144.0),
                0.80f to shaded(180.0),
                0.92f to Color.White,
                1.0f to Color.White,
                start = Offset(left, top + height * 0.02f),
                end = Offset(left + width / 2f, top)
            )
        )
    }
}

/**
 * QML lines 208-225: the trace glow, `z:3`.
 *
 * A very wide (`0.2R`) arc from the gauge's zero angle to the needle, stroked with a radial
 * gradient that is transparent at `0.87R` and full trace colour at `0.95R` — so what the rider
 * sees is a soft bloom hugging the rim behind the needle, fading to nothing before it reaches
 * the scale numbers. The QML's explicit "draw it the short way round" flag for negative readings
 * (line 223) is carried by the sign of [VescGaugeRange.traceSweepDegrees]; see its doc.
 *
 * Compose radial gradients always start their stop scale at the centre, unlike the QML's
 * `createRadialGradient(x, y, r0, x, y, r1)`, so the inner radius `r0` is re-expressed as a
 * transparent stop at [VescDialMetrics.traceGlowInnerStop].
 */
private fun DrawScope.drawVescTrace(
    center: Offset,
    outerRadius: Float,
    range: VescGaugeRange,
    reading: Double,
    nibColor: Color
) {
    val sweep = range.traceSweepDegrees(reading)
    if (abs(sweep) <= 0.0) return
    val r = outerRadius.toDouble()
    val traceRadius = VescDialMetrics.traceRadius(r).toFloat()
    if (traceRadius <= 0f) return

    val traceColor = nibColor.traceTint()
    val brush = Brush.radialGradient(
        0.0f to Color.Transparent,
        VescDialMetrics.traceGlowInnerStop.toFloat() to Color.Transparent,
        1.0f to traceColor,
        center = center,
        radius = VescDialMetrics.traceGlowOuterRadius(r).toFloat()
    )
    drawArc(
        brush = brush,
        startAngle = VescDialGeometry.screenAngleDegrees(range.valueToAngle(0.0)).toFloat(),
        sweepAngle = sweep.toFloat(),
        useCenter = false,
        topLeft = Offset(center.x - traceRadius, center.y - traceRadius),
        size = Size(traceRadius * 2f, traceRadius * 2f),
        style = Stroke(width = VescDialMetrics.traceStroke(r).toFloat(), cap = StrokeCap.Butt)
    )
}

/**
 * QML lines 226-262: the two-ring metal bezel, `z:3`, drawn last of all.
 *
 * Both rings are stroked `0.035R` wide along the same diagonal gradient axis — top-right to
 * bottom-left (`createLinearGradient(width, 0, 0, height)`, lines 236 and 249) — but with the
 * stop order MIRRORED between them: the outer ring runs light -> dark -> light and the inner one
 * dark -> light -> dark. That mirroring is the entire trick; it reads as a bevel catching light
 * on one edge and shadow on the other, from two flat strokes and no shader.
 *
 * The QML lists its stops out of order and relies on the canvas sorting them; they are written
 * ascending here because Compose does not sort.
 */
private fun DrawScope.drawVescBezel(
    center: Offset,
    outerRadius: Float,
    bezelLight: Color,
    bezelDark: Color
) {
    val r = outerRadius.toDouble()
    val stroke = VescDialMetrics.bezelStroke(r).toFloat()
    if (stroke <= 0f) return
    // The gradient axis spans the dial's own bounding box, not the (possibly larger) canvas.
    val axisStart = Offset(center.x + outerRadius, center.y - outerRadius)
    val axisEnd = Offset(center.x - outerRadius, center.y + outerRadius)

    val outerRing = VescDialMetrics.bezelOuterRingRadius(r).toFloat()
    if (outerRing > 0f) {
        drawCircle(
            brush = Brush.linearGradient(
                0.1f to bezelLight,
                0.7f to bezelDark,
                1.0f to bezelLight,
                start = axisStart,
                end = axisEnd
            ),
            radius = outerRing,
            center = center,
            style = Stroke(width = stroke)
        )
    }

    val innerRing = VescDialMetrics.bezelInnerRingRadius(r).toFloat()
    if (innerRing > 0f) {
        drawCircle(
            brush = Brush.linearGradient(
                0.0f to bezelDark,
                0.8f to bezelLight,
                1.0f to bezelDark,
                start = axisStart,
                end = axisEnd
            ),
            radius = innerRing,
            center = center,
            style = Stroke(width = stroke)
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Bridges between the Compose types and the pure arithmetic in VescDialGeometry.kt.
// ---------------------------------------------------------------------------------------------

/**
 * A radius fraction turned into a font size, deliberately bypassing the system font-scale
 * multiplier.
 *
 * The dial's digits are geometry — sized to the space this particular dial measured out for them
 * — not body text that should grow with an accessibility setting; a 1.3x font scale applied to a
 * `0.3R` readout would burst the instrument. `Float.toSp()` divides by `density * fontScale`, and
 * Compose's text renderer multiplies by exactly that again at draw time, so the two cancel and
 * the glyphs land at the requested pixel size on every device and at every font scale. This is
 * the only place in the file where a length stops being a fraction of the radius, and it stops
 * being one only to survive a round trip that puts it back.
 */
private fun DrawScope.fontSizeFor(outerRadius: Float, fraction: Double): TextUnit =
    (outerRadius * fraction.toFloat()).coerceAtLeast(0f).toSp()

/** QML lines 173-174 / 191-192: `Qt.darker(nibColor, factor)`, alpha preserved. */
private fun Color.shadedNib(factor: Double): Color = withRgb(VescNibShading.darker(toNibRgb(), factor))

/** QML line 50: `traceColor: Qt.lighter(nibColor, 1.5)`, alpha preserved. */
private fun Color.traceTint(): Color = withRgb(VescNibShading.traceColor(toNibRgb()))

private fun Color.toNibRgb(): VescNibShading.Rgb =
    VescNibShading.Rgb(red.toDouble(), green.toDouble(), blue.toDouble())

private fun Color.withRgb(rgb: VescNibShading.Rgb): Color = Color(
    red = rgb.r.toFloat().coerceIn(0f, 1f),
    green = rgb.g.toFloat().coerceIn(0f, 1f),
    blue = rgb.b.toFloat().coerceIn(0f, 1f),
    alpha = this.alpha
)

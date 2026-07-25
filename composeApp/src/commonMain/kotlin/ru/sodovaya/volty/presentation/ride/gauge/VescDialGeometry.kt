package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One VESC gauge's angle sweep and value range — the faithful port of the property block at the
 * top of VESC Tool's `mobile/CustomGauge.qml` (lines 27-57), plus the pure per-index tick maths
 * further down that file (lines 336-377). All angles are DEGREES, matching the QML.
 *
 * [minAngle]/[maxAngle] are intentionally NOT constrained to `minAngle < maxAngle`. Six of the
 * eight gauges VESC's own `mobile/RtDataSetup.qml` wires up are "normal" (e.g. Speed:
 * `minAngle=-225, maxAngle=45`), but Duty (`minAngle=210, maxAngle=-15`) and Motor-temp
 * (`minAngle=195, maxAngle=-30`) sweep the OTHER way — their tick labels literally run
 * counter-clockwise. [isInverted] mirrors the QML's own `isInverted` property (line 37) so a
 * caller (the Canvas layer, Task 2) can special-case the one place that actually needs to know
 * the direction (the covered-arc sweep, QML line 223: `(gauge.value*isInverted) < 0`). Every
 * function below handles the sign of `maxAngle - minAngle` on its own via plain signed
 * arithmetic — no other branching on inversion is needed, which is the elegance of the original.
 */
data class VescGaugeRange(
    val minAngle: Double,
    val maxAngle: Double,
    val minimumValue: Double,
    val maximumValue: Double,
    val labelStep: Double,
    /** QML line 41: `property int minorTickmarkCount: 4` — 4 minor ticks between each pair of majors. */
    val minorTickmarkCount: Int = 4
) {

    /** QML line 37: `property int isInverted: maxAngle > minAngle ? 1 : -1`, as a boolean. */
    val isInverted: Boolean get() = !(maxAngle > minAngle)

    /** QML line 39: `property real angleRange: maxAngle - minAngle`. Negative on an inverted range. */
    val angleRange: Double get() = maxAngle - minAngle

    /**
     * QML line 40:
     * `Math.min(100, Math.floor(labelStep > 0 ? (maximumValue - minimumValue) / labelStep + 1 : 0))`
     */
    val tickmarkCount: Int
        get() = if (labelStep > 0.0) {
            min(MAX_TICKMARK_COUNT, floor((maximumValue - minimumValue) / labelStep + 1.0).toInt())
        } else {
            0
        }

    /**
     * True when there are at least two major ticks, i.e. when [tickmarkSectionSize] and friends
     * are actually defined — every one of them divides by `tickmarkCount - 1`. The QML gets away
     * without this guard because a QML `Repeater` with a model of 0 or 1 simply instantiates
     * nothing interesting and JavaScript's `x/0` is a silent `Infinity`; Kotlin would happily
     * produce `NaN` angles and hand them to the Canvas, so the drawing layer asks this first.
     */
    val hasTickmarks: Boolean get() = tickmarkCount > 1

    /** QML lines 336-338: `rangeUsed(count, stepSize)`. */
    private fun rangeUsed(count: Int, stepSize: Double): Double =
        (((count - 1) * stepSize) / (maximumValue - minimumValue)) * angleRange

    /** QML line 340: the angular gap between two adjacent major ticks. Negative when inverted. */
    val tickmarkSectionSize: Double get() = rangeUsed(tickmarkCount, labelStep) / (tickmarkCount - 1)

    /** QML line 341: the value gap between two adjacent major ticks. */
    val tickmarkSectionValue: Double get() = (maximumValue - minimumValue) / (tickmarkCount - 1)

    /** QML line 342. */
    val minorTickmarkSectionSize: Double get() = tickmarkSectionSize / (minorTickmarkCount + 1)

    /** QML line 343. */
    val minorTickmarkSectionValue: Double get() = tickmarkSectionValue / (minorTickmarkCount + 1)

    /**
     * QML lines 344-352. The QML's `tickmarksNotDisplayed` term is always zero
     * (`tickmarkCount - tickmarkCount`), so it contributes nothing — this is the simplified,
     * behaviourally identical form: `minorTickmarkCount * (tickmarkCount - 1)`.
     */
    val totalMinorTickmarkCount: Int get() = minorTickmarkCount * (tickmarkCount - 1)

    /**
     * QML lines 61-65 (`valueToAngle`) — and, per QML line 152
     * (`angle: valueToAngle(gauge.value)`), this IS the needle's rotation angle. No separate
     * "needle angle" function exists because none is needed: a caller animates the needle's
     * rotation straight to this value.
     *
     * Normalises [value] into the gauge's value range, clamps to 0..1, then maps that fraction
     * onto the angle range. Clamping happens BEFORE the angle mapping, so a value beyond the
     * gauge's range pins the needle at [minAngle] or [maxAngle] rather than overshooting.
     *
     * Guards the degenerate `maximumValue <= minimumValue` span explicitly: every range this
     * project actually BUILDS has `max > min`, so this is unreachable today (like the QML, which
     * gets away with a silent `x/0 == Infinity` in JavaScript), but the predecessor this file
     * replaced (`DialGeometry.fraction`) carried the same guard — `span <= 0f` — and a test pinning
     * it, and both vanished together in the rewrite. Restored at parity with that guard (not
     * narrowed to `span == 0.0`, which would miss a negative span the same way): a zero-or-negative
     * span range now rests the needle at [minAngle] — the same answer a real, merely-very-narrow
     * range gives a [value] at its minimum — rather than propagating a `NaN` into the Canvas layer.
     */
    fun valueToAngle(value: Double): Double {
        val span = maximumValue - minimumValue
        if (span <= 0.0) return minAngle
        val normalised = (value - minimumValue) / span
        val clamped = when {
            normalised > 1.0 -> 1.0
            normalised < 0.0 -> 0.0
            else -> normalised
        }
        return angleRange * clamped + minAngle
    }

    /** QML lines 358-360: the angle of major tick [index] (0-based, 0 .. tickmarkCount - 1). */
    fun tickmarkAngleFromIndex(index: Int): Double = index * tickmarkSectionSize + minAngle

    /**
     * QML lines 362-367: the angle of minor tick [minorIndex] (0-based, 0 .. totalMinorTickmarkCount - 1).
     * The `+ minorTickmarkSectionSize` term is deliberate, not an off-by-one: without it the first
     * minor tick after a major would land exactly on top of that major.
     */
    fun minorTickmarkAngleFromIndex(minorIndex: Int): Double {
        val baseAngle = tickmarkAngleFromIndex(floor(minorIndex.toDouble() / minorTickmarkCount).toInt())
        val relativeMinorAngle = (minorIndex % minorTickmarkCount) * minorTickmarkSectionSize + minorTickmarkSectionSize
        return baseAngle + relativeMinorAngle
    }

    /** QML lines 369-371: the value major tick [majorIndex] is labelled with. */
    fun tickmarkValueFromIndex(majorIndex: Int): Double = majorIndex * tickmarkSectionValue + minimumValue

    /** QML lines 373-377: the value minor tick [minorIndex] represents. */
    fun tickmarkValueFromMinorIndex(minorIndex: Int): Double {
        val majorIndex = minorIndex / minorTickmarkCount
        val relativeMinorIndex = minorIndex % minorTickmarkCount
        return tickmarkValueFromIndex(majorIndex) + (relativeMinorIndex * minorTickmarkSectionValue + minorTickmarkSectionValue)
    }

    /**
     * The SIGNED sweep, in degrees, of the trace glow: QML lines 218-223 draw an arc from
     * `valueToAngle(0.0)` to the needle's angle.
     *
     * The QML has to pass HTML5 canvas `arc()` an explicit direction flag —
     * `(gauge.value * isInverted) < 0` (line 223) — because that API takes two absolute angles and
     * cannot know which way round to go. Compose's `drawArc` takes a *signed sweep* instead, and
     * the plain signed difference below already carries exactly the direction that flag selects,
     * so the flag needs no separate port:
     *
     *   sweep = angleRange * (normalise(value) - normalise(0))
     *
     * `sign(angleRange)` is `+1` precisely when `maxAngle > minAngle`, which is the QML's
     * `isInverted == 1`; and `normalise(value) - normalise(0)` carries the sign of `value` (the
     * normalisation is monotonically increasing in `value`, and where the clamp pins one of the
     * two terms to an endpoint it can only pin it on the side that already agrees). Their product
     * is therefore negative — an anticlockwise sweep — exactly when `value * isInverted < 0`.
     *
     * `|sweep| <= |angleRange|`, so this never needs the modulo-360 disambiguation the "short way
     * round" flag exists to provide.
     */
    fun traceSweepDegrees(value: Double): Double = valueToAngle(value) - valueToAngle(0.0)

    companion object {
        /**
         * QML line 40's own `Math.min(100, …)` — VESC Tool's hard ceiling on major-tick count,
         * ported verbatim. Named (rather than left as the bare literal it was) because
         * [ru.sodovaya.volty.presentation.ride.ClassicDialSpecs]'s session auto-scale derives its
         * own ceiling from exactly this number: once a range's NATURAL tick count (before this cap
         * applies) would exceed it, [tickmarkCount] silently swaps `labelStep`'s own division for
         * `(maximumValue - minimumValue) / (MAX_TICKMARK_COUNT - 1)` — a divisor a growing span was
         * never snapped to divide evenly, which is exactly the ragged-ring regression a session
         * scale that grows without bound would eventually walk into.
         */
        const val MAX_TICKMARK_COUNT = 100
    }
}

/**
 * Every length in `mobile/CustomGauge.qml` as a fraction of the dial's outer radius `R`, plus the
 * radii derived from them. Nothing here is a device-independent pixel or a scale-independent
 * pixel: the QML sizes its entire instrument — including all four of its fonts — off
 * `outerRadius`, which is what lets one dial definition serve a 40 dp corner gauge and a hero
 * gauge without a single per-size tweak. The drawing layer converts these to `sp` only at the
 * last moment, and only by cancelling the density it is about to be multiplied by again.
 */
object VescDialMetrics {

    // --- ticks: QML lines 33-34 (inset), 319-320 (major), 329-330 (minor), 390, 412 ---
    /** QML lines 33-34: `tickmarkInset`/`minorTickmarkInset`, both `outerRadius * 0.07`. */
    const val TICKMARK_INSET_FRACTION = 0.07
    /** QML line 319: major tick `implicitWidth`. */
    const val MAJOR_TICK_WIDTH_FRACTION = 0.02
    /** QML line 320: major tick `implicitHeight`. */
    const val MAJOR_TICK_LENGTH_FRACTION = 0.1
    /** QML line 329: minor tick `implicitWidth`. */
    const val MINOR_TICK_WIDTH_FRACTION = 0.015
    /** QML line 330: minor tick `implicitHeight`. */
    const val MINOR_TICK_LENGTH_FRACTION = 0.07

    // --- bezel: QML lines 230, 245, 258 ---
    /** QML line 230: `borderWidth: outerRadius * 0.035`, the stroke of BOTH bezel rings. */
    const val BEZEL_STROKE_FRACTION = 0.035

    // --- needle: QML lines 144-151 ---
    /** QML line 144: `y: outerRadius * 0.05` — the blade's tip sits this far IN from the rim. */
    const val NEEDLE_TIP_INSET_FRACTION = 0.05
    /** QML line 146: `height: outerRadius * 0.22` — the blade's length. */
    const val NEEDLE_LENGTH_FRACTION = 0.22
    /** QML line 147: `width: outerRadius * 0.12` — the blade's width at its base. */
    const val NEEDLE_WIDTH_FRACTION = 0.12

    // --- trace glow: QML lines 212-220 ---
    /** QML line 220: the arc's own radius, `outerRadius - outerRadius * 0.12`. */
    const val TRACE_RADIUS_INSET_FRACTION = 0.12
    /** QML line 217: `lineWidth = outerRadius * 0.2`. */
    const val TRACE_STROKE_FRACTION = 0.2
    /** QML line 212: the radial gradient's inner (fully transparent) radius. */
    const val TRACE_GLOW_INNER_INSET_FRACTION = 0.13
    /** QML line 212: the radial gradient's outer (fully coloured) radius. */
    const val TRACE_GLOW_OUTER_INSET_FRACTION = 0.05

    // --- fonts: QML lines 107, 119, 135, 309 ---
    /** QML line 107: the centre readout. The loudest thing on the dial, by a factor of 2.5. */
    const val VALUE_FONT_FRACTION = 0.3
    /** QML line 135: the caption above the readout. */
    const val CAPTION_FONT_FRACTION = 0.12
    /** QML line 119: the unit below the readout. */
    const val UNIT_FONT_FRACTION = 0.12
    /** QML line 309: the numbers on the scale. */
    const val TICK_LABEL_FONT_FRACTION = 0.12

    /**
     * QML lines 385-395: a tick's outer end. The `Translate { y: -outerRadius + tickmarkInset }`
     * puts the rectangle's TOP edge here and the rectangle then grows *inward*, so this is the
     * end nearest the rim for both major and minor ticks.
     */
    fun tickOuterRadius(outerRadius: Double): Double =
        outerRadius - outerRadius * TICKMARK_INSET_FRACTION

    /** The inner end of a major tick — [tickOuterRadius] less the 0.1R the rectangle is tall. */
    fun majorTickInnerRadius(outerRadius: Double): Double =
        tickOuterRadius(outerRadius) - outerRadius * MAJOR_TICK_LENGTH_FRACTION

    /** The inner end of a minor tick — [tickOuterRadius] less the 0.07R the rectangle is tall. */
    fun minorTickInnerRadius(outerRadius: Double): Double =
        tickOuterRadius(outerRadius) - outerRadius * MINOR_TICK_LENGTH_FRACTION

    /**
     * The radius of the needle's TIP — QML line 144, `y: outerRadius * 0.05`, measured from the
     * top of a `2R` box, i.e. `0.05R` in from the rim.
     */
    fun needleTipRadius(outerRadius: Double): Double =
        outerRadius - outerRadius * NEEDLE_TIP_INSET_FRACTION

    /**
     * The radius of the needle's BASE — its tip radius less the blade's own `0.22R` length.
     *
     * This is the number the whole "faithful port" turns on: it is `0.73R`, not `0`. VESC's
     * needle is a short blade riding at the rim on a rotation whose origin was pushed all the way
     * down to the dial centre (QML line 151, `origin.y: outerRadius*(1-0.05)`, which is `0.95R`
     * measured from the blade's own top edge and therefore lands exactly on the centre). The blade
     * pivots about the centre without ever reaching it, which is why the centre readout is never
     * covered. A needle drawn *from* the centre outward — the shape this project shipped and had
     * rejected — always sits on the readout, and no hub cap or z-order can undo that.
     */
    fun needleBaseRadius(outerRadius: Double): Double =
        needleTipRadius(outerRadius) - outerRadius * NEEDLE_LENGTH_FRACTION

    /**
     * QML line 230.
     *
     * Note for anyone reaching for this as a "pick this dial out" cue: there is no room. The two
     * rings this stroke draws already fill the entire band between [tickOuterRadius] (`0.93R`) and
     * the rim — `2 * 0.035R` is exactly the `0.07R` available (pinned in `VescDialMetricsTest`) —
     * so any heavier bezel grows inward over the tick marks and reads as a clipping bug. The
     * Classic cluster no longer has an emphasis cue at all: it renders exactly the composition
     * `RtDataSetup.qml` describes, with no size or paint-order deviation.
     */
    fun bezelStroke(outerRadius: Double): Double = outerRadius * BEZEL_STROKE_FRACTION

    /** QML line 245: `outerRadius - borderWidth/2` — the outer ring's centreline. */
    fun bezelOuterRingRadius(outerRadius: Double): Double =
        outerRadius - bezelStroke(outerRadius) / 2.0

    /**
     * QML line 258: `outerRadius - borderWidth*3/2 + 1` — the inner ring's centreline, so the two
     * rings sit flush. The trailing `+ 1` is a literal single pixel in the original (not a
     * fraction of anything, not a dp) that nudges the inner ring out to close the hairline seam
     * antialiasing leaves between two abutting strokes; it is ported verbatim rather than
     * "cleaned up", because dropping it reopens the seam and rounding it to a fraction of `R`
     * would make the seam size vary with the dial.
     */
    fun bezelInnerRingRadius(outerRadius: Double): Double =
        outerRadius - bezelStroke(outerRadius) * 3.0 / 2.0 + 1.0

    /** QML line 220: the radius of the trace glow's own arc. */
    fun traceRadius(outerRadius: Double): Double =
        outerRadius - outerRadius * TRACE_RADIUS_INSET_FRACTION

    /** QML line 217. */
    fun traceStroke(outerRadius: Double): Double = outerRadius * TRACE_STROKE_FRACTION

    /** QML line 212: `outerRadius - outerRadius * 0.13`, where the glow is still transparent. */
    fun traceGlowInnerRadius(outerRadius: Double): Double =
        outerRadius - outerRadius * TRACE_GLOW_INNER_INSET_FRACTION

    /** QML line 212: `outerRadius - outerRadius * 0.05`, where the glow reaches full colour. */
    fun traceGlowOuterRadius(outerRadius: Double): Double =
        outerRadius - outerRadius * TRACE_GLOW_OUTER_INSET_FRACTION

    /**
     * Where the glow's transparent inner edge falls as a STOP POSITION in a Compose
     * `Brush.radialGradient`, which — unlike the QML's `createRadialGradient(x,y,r0,x,y,r1)` —
     * always starts its stop scale at the centre rather than at an inner radius `r0`. Emitting a
     * transparent stop here reproduces `r0`: `0.87R / 0.95R`, a constant independent of the dial's
     * size.
     */
    val traceGlowInnerStop: Double
        get() = traceGlowInnerRadius(1.0) / traceGlowOuterRadius(1.0)
}

/**
 * The needle's angle-dependent shading and the trace's tint — the two `Qt.darker` / `Qt.lighter`
 * calls in `mobile/CustomGauge.qml` (lines 50, 173-174, 191-192), as pure arithmetic on plain
 * `0f..1f` RGB components so it stays testable and Compose-free like everything else in this file.
 *
 * Qt's colour math is not a per-channel multiply: it works in HSV and touches only the `value`
 * component, and when brightening would overflow it *desaturates* toward white instead of
 * clipping each channel separately. Clipping per channel would shift the hue of a saturated nib
 * colour (a red needle would drift orange as it brightened); Qt's rule keeps the hue and washes
 * the colour out, which is what makes the blade read as lit metal rather than as a flat triangle.
 */
object VescNibShading {

    /** A colour as three `0f..1f` components. Deliberately not `androidx.compose.ui.graphics.Color`. */
    data class Rgb(val r: Double, val g: Double, val b: Double)

    /**
     * QML lines 173-174 and 191-192: `1.0 + 0.5 * Math.sin(d2r(gAngle + offset))`, the factor fed
     * to `Qt.darker`. It sweeps 0.5..1.5 as the needle turns, so each blade half brightens on the
     * swing where it faces the (imaginary, fixed) light and dims on the other — the reason the
     * needle appears to catch the light as it moves rather than being a solid wedge of one colour.
     * The four offsets the QML uses are -36, 0 (right half) and +144, +180 (left half).
     */
    fun shadeFactor(needleAngleDegrees: Double, offsetDegrees: Double): Double =
        1.0 + 0.5 * sin((needleAngleDegrees + offsetDegrees) * PI / 180.0)

    /**
     * `Qt.darker(color, factor)` — `QColor::darker`, which divides the HSV *value* by `factor`.
     * Because HSV value is `max(r, g, b)` and hue/saturation are ratios within the triple,
     * dividing the value is exactly dividing all three components — until it would clip, which is
     * only possible in the brightening direction, and Qt delegates that case to [lighter] (its
     * `if (factor < 100) return lighter(10000/factor)`).
     */
    fun darker(color: Rgb, factor: Double): Rgb = when {
        factor <= 0.0 -> color
        factor < 1.0 -> lighter(color, 1.0 / factor)
        else -> Rgb(color.r / factor, color.g / factor, color.b / factor)
    }

    /**
     * `Qt.lighter(color, factor)` — `QColor::lighter`: multiply the HSV value by `factor`, and if
     * that overflows, pin the value at maximum and subtract the overflow from the saturation
     * (`s = s - (v - USHRT_MAX)`), i.e. wash out toward white while preserving hue.
     *
     * The rebuild avoids computing a hue angle at all: for any HSV triple, `max = v`,
     * `min = v*(1-s)`, and the middle component sits at a hue-determined fraction
     * `t = (mid - min)/(max - min)` of the way between them. `t` is invariant under any change of
     * `v` and `s`, so recolouring is `newMin = 1 - s'`, `newMax = 1`, `newMid = newMin + s'*t`.
     */
    fun lighter(color: Rgb, factor: Double): Rgb = when {
        factor <= 0.0 -> color
        factor < 1.0 -> darker(color, 1.0 / factor)
        else -> {
            val v = max(color.r, max(color.g, color.b))
            val scaled = v * factor
            if (scaled <= 1.0) {
                Rgb(color.r * factor, color.g * factor, color.b * factor)
            } else {
                val m = min(color.r, min(color.g, color.b))
                val saturation = if (v <= 0.0) 0.0 else (v - m) / v
                val newSaturation = max(0.0, saturation - (scaled - 1.0))
                val newMin = 1.0 - newSaturation
                if (v == m) {
                    // Achromatic: no hue to preserve, and the fraction below is 0/0.
                    Rgb(1.0, 1.0, 1.0)
                } else {
                    fun rebuild(component: Double) = newMin + newSaturation * ((component - m) / (v - m))
                    Rgb(rebuild(color.r), rebuild(color.g), rebuild(color.b))
                }
            }
        }
    }

    /** QML line 50: `traceColor: Qt.lighter(nibColor, 1.5)`. */
    fun traceColor(nib: Rgb): Rgb = lighter(nib, 1.5)
}

/**
 * Range-independent pure geometry from `mobile/CustomGauge.qml`: whether a tick is "covered" by
 * the needle, and where a tick's label sits. Deliberately NOT drawing: no Compose imports, no
 * colours, no dp/sp — the Canvas layer (Task 2) turns these numbers into pixels.
 *
 * Does NOT port the danger/red-zone concept (this project's own `DialGeometry.dangerSweep`):
 * VESC has no such thing — its only severity cue is the needle's colour (`nibColor`), computed
 * per-gauge in `RtDataSetup.qml`, not in `CustomGauge.qml` itself.
 */
object VescDialGeometry {

    /**
     * QML lines 72-83 (`isCovered`). [tickValue] is the value a given tick/label represents;
     * [gaugeValue] is the gauge's current needle reading. A positive [gaugeValue] covers ticks in
     * `0..gaugeValue`; a negative one covers ticks in `gaugeValue..0`. Note this means
     * `gaugeValue == 0.0` only covers the zero tick itself (the QML's `gauge.value > 0` branch is
     * false for exactly zero, so it falls into the `else` branch, whose closed range `0.0..0.0`
     * admits only zero).
     */
    fun isCovered(tickValue: Double, gaugeValue: Double): Boolean =
        if (gaugeValue > 0.0) tickValue in 0.0..gaugeValue else tickValue in gaugeValue..0.0

    private const val LABEL_INSET_INNER_FRACTION = 0.28
    private const val LABEL_INSET_OUTER_FRACTION = 0.06

    /** QML line 32: `labelInset = outerRadius * 0.28 + outerRadius * 0.06` (= 0.34 * outerRadius). */
    fun labelInset(outerRadius: Double): Double =
        outerRadius * LABEL_INSET_INNER_FRACTION + outerRadius * LABEL_INSET_OUTER_FRACTION

    /**
     * The radius at which a tick's label is CENTRED, per QML lines 456-457
     * (`outerRadius - labelInset`, the distance term multiplying `cos`/`sin` of the tick's angle
     * there) — i.e. `R - 0.34R` = `0.66R`. The angular placement (the actual `cos`/`sin` call)
     * is left to the Canvas layer, which is the only place trigonometry meets pixels.
     */
    fun labelRadius(outerRadius: Double): Double = outerRadius - labelInset(outerRadius)

    /**
     * Gauge angles -> the drawing surface's angles. The QML measures every gauge angle from
     * TWELVE o'clock (its ticks are laid out by translating `-outerRadius` along `y`, i.e. up,
     * and then rotating), while both HTML5 canvas and Compose measure from THREE o'clock — hence
     * the `- 90` that appears at QML lines 221-222 and 456-457 wherever an angle finally meets a
     * trigonometric call. Both conventions run clockwise-positive, so the offset is the whole of
     * the conversion.
     */
    fun screenAngleDegrees(gaugeAngleDegrees: Double): Double = gaugeAngleDegrees - 90.0

    /** [screenAngleDegrees] in radians, ready for `cos`/`sin`. */
    fun screenAngleRadians(gaugeAngleDegrees: Double): Double =
        screenAngleDegrees(gaugeAngleDegrees) * PI / 180.0

    /**
     * QML lines 101-140: the vertical anchoring of the centre stack's three text blocks, as pure
     * arithmetic on a centre Y and two measured heights — lifted out of `VescDialGauge`'s draw
     * lambda (Task 2 review), where it was the one piece of testable geometry left buried where no
     * test could reach it.
     *
     * The VALUE is centred on [centerY] (QML line 103: `anchors.centerIn: parent`) — it, not the
     * stack as a whole, sits on the dial's middle. The CAPTION's bottom edge sits on the value's
     * TOP edge (QML line 132: `anchors.bottom: speedLabel.top`) and so grows upward, using its own
     * [captionHeight]. The UNIT's top edge sits on the value's BOTTOM edge (QML line 117:
     * `anchors.top: speedLabel.bottom`) — using [valueHeight] again, not the unit's own height,
     * because it is the value's edge the unit anchors to, not its own.
     */
    fun centerTextLayout(centerY: Double, valueHeight: Double, captionHeight: Double): VescCenterTextLayout {
        val valueTop = centerY - valueHeight / 2.0
        return VescCenterTextLayout(
            valueTop = valueTop,
            captionTop = valueTop - captionHeight,
            unitTop = valueTop + valueHeight
        )
    }

    // ------------------------------------------------------------------------------------------
    // Fitting the caption and the unit — the one thing the QML never had to solve
    // ------------------------------------------------------------------------------------------

    /**
     * The floor [centerTextScale] will not shrink past.
     *
     * A caption that needs less than half its nominal size is not a layout problem any more, it is
     * a translation that does not belong on a dial face, and rendering it at a size nobody can read
     * hides that. Below this the text is left slightly wide instead: visibly wrong beats invisibly
     * present.
     */
    const val MIN_CENTER_TEXT_SCALE: Double = 0.5

    /**
     * The width available to a centre-stack line whose outer edge sits [halfHeight] away from the
     * dial's centre: the chord of [labelRadius] (`0.66R`, the ring the scale numbers are centred
     * on) at that height.
     *
     * `CustomGauge.qml` never needed this because every caption it draws is an English word of five
     * or six letters at `0.12R`, which fits any dial in the cluster with room to spare. Ours are
     * localized — `МОЩНОСТЬ` is eight characters and `CONSUMPTION` eleven — and the caption is the
     * one line of the stack that can afford to give ground: the readout is the reading and stays at
     * [VescDialMetrics.VALUE_FONT_FRACTION] (`0.3R`, QML line 107) whatever happens around it.
     *
     * The chord, not the diameter: the line's own far edge is what has to clear the ring, and at
     * `halfHeight` above (or below) the centre the ring is `2 * sqrt(0.66R² - halfHeight²)` across,
     * not `1.32R`. A caption at the top of the stack sits roughly a third of the radius up, where
     * that costs it about 12% of the width the diameter would suggest.
     */
    fun centerTextMaxWidth(outerRadius: Double, halfHeight: Double): Double {
        val ring = labelRadius(outerRadius)
        val h = abs(halfHeight)
        if (ring <= 0.0 || h >= ring) return 0.0
        return 2.0 * sqrt(ring * ring - h * h)
    }

    /**
     * The factor a centre-stack line of [measuredWidth] must be drawn at to fit [maxWidth] — `1.0`
     * when it already fits, never below [MIN_CENTER_TEXT_SCALE].
     *
     * Scaling, not wrapping. `МОЩНОСТЬ` is a single word: wrapping it means hyphenating it or
     * breaking it mid-word, and on a `g2` cluster dial a two-line caption also pushes the stack's
     * outer edge further from the centre, which shrinks the very chord it was trying to fit into.
     * (`ClassicDialSpecs.twoLineCaption` does split the two temperature captions, but at a real
     * space between two words, which is a different operation.)
     *
     * Deliberately takes the MEASURED width rather than the text: measurement needs a font, a
     * density and Compose; choosing a factor from two numbers needs none of that, and this is the
     * half that a test can hold still.
     */
    fun centerTextScale(measuredWidth: Double, maxWidth: Double): Double = when {
        measuredWidth <= 0.0 -> 1.0
        maxWidth <= 0.0 -> MIN_CENTER_TEXT_SCALE
        measuredWidth <= maxWidth -> 1.0
        else -> (maxWidth / measuredWidth).coerceAtLeast(MIN_CENTER_TEXT_SCALE)
    }

    // ------------------------------------------------------------------------------------------
    // The unit line's casing — narrowing CustomGauge.qml:123's blanket rule to Latin units
    // ------------------------------------------------------------------------------------------

    /**
     * True when every LETTER in [text] is a plain Latin/ASCII one (`A-Z` / `a-z`). Characters that
     * are not letters at all — `%`, `°`, `/`, `.`, digits — never disqualify it, since upper-casing
     * them is a no-op either way; a single Cyrillic (or other non-Latin) letter does.
     *
     * This is the predicate [uppercaseUnitIfLatin] narrows `Font.AllUppercase` with. VESC's own
     * units are short Latin/symbolic tokens (`A`, `W`, `%`, `KM/H`) where all-caps is the intended
     * look; a spelled-out, localized unit like `Вт·ч/км` is not one of those — SI symbol case is
     * DEFINED (capital `В`, lower `т`/`ч`/`км`), so upper-casing it to `ВТ·Ч/КМ` is wrong, not
     * merely a style choice, the same way it would be wrong to render `mph` as `MPH·BUT·WRONG` if
     * that were somehow the unit's actual spelling.
     */
    fun isAsciiLetters(text: String): Boolean = text.all { !it.isLetter() || it in 'A'..'Z' || it in 'a'..'z' }

    /**
     * `CustomGauge.qml:123`: `font.capitalization: Font.AllUppercase`, applied unconditionally to
     * every gauge's unit line. That blanket rule is correct for VESC's own Latin units and wrong
     * for a spelled-out non-Latin one — see [isAsciiLetters]. Narrowing it here, rather than at
     * every call site, keeps the QML's rule intact for every unit it was ever written for and
     * leaves an authored non-Latin unit exactly as the caller spelled it.
     */
    fun uppercaseUnitIfLatin(text: String): String = if (isAsciiLetters(text)) text.uppercase() else text
}

/**
 * The three top-edge Y positions [VescDialGeometry.centerTextLayout] computes for the centre
 * stack's value / caption / unit text blocks (QML lines 101-140).
 */
data class VescCenterTextLayout(val valueTop: Double, val captionTop: Double, val unitTop: Double)

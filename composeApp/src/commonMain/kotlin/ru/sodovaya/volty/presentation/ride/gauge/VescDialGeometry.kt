package ru.sodovaya.volty.presentation.ride.gauge

import kotlin.math.floor
import kotlin.math.min

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
            min(100, floor((maximumValue - minimumValue) / labelStep + 1.0).toInt())
        } else {
            0
        }

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
     */
    fun valueToAngle(value: Double): Double {
        val normalised = (value - minimumValue) / (maximumValue - minimumValue)
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
}

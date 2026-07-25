package ru.sodovaya.volty.presentation.ride.gauge

/**
 * One dial in VESC Tool's eight-gauge RT-Data cluster, `mobile/RtDataSetup.qml`'s `GridLayout`
 * (:58-537). Names match the QML's own `id`s (`currentGauge`, `dutyGauge`, ...).
 */
enum class VescClusterSlot {
    CURRENT, DUTY, POWER,
    SPEED, BATTERY,
    ESC_TEMP, MOTOR_TEMP, CONSUMPTION
}

/**
 * A dial's resolved centre and size inside the cluster's own coordinate space.
 *
 * [centerX] is measured from the cluster's shared vertical CENTRE LINE — negative is left of
 * centre, positive is right. [centerY] is measured from the TOP of the cluster (the top edge of
 * the top trio's row). Both are in whatever unit [VescClusterGeometry.place] was called with (the
 * same unit as `g`/`g2`) — a Compose caller converts to px/Dp once at the edge, same as
 * `VescDialGeometry`/`VescDialMetrics` do for a single dial.
 */
data class VescClusterBox(val centerX: Double, val centerY: Double, val size: Double)

/**
 * Flattened port of the nested `anchors.centerIn` / `horizontalCenterOffset` /
 * `verticalCenterOffset` chain that `mobile/RtDataSetup.qml`'s `GridLayout` (:58-537) uses to
 * arrange the eight RT-Data gauges: a top trio (Current, with Duty nested on it, with Power nested
 * on Duty), a middle pair (Speed, with Battery nested on it), and a bottom trio mirroring the top
 * one (ESC temp, Motor temp nested on it, Consumption nested on that).
 *
 * **Flattened, not nested — deliberately.** The QML expresses each dial's anchor relative to its
 * own PARENT dial, not to a shared container (e.g. `dutyGauge.anchors.horizontalCenterOffset:
 * gaugeSize2*1.35` is relative to `currentGauge`, its enclosing `Item`, not to the row). Compose's
 * `Layout` has no equivalent of "anchor to a specific sibling" for free; reproducing that literally
 * would mean one bespoke nested `Layout` per level of nesting (three deep, twice) for no behaviour
 * difference, because Qt's nested anchoring is ITSELF just a chain of additions — as the hand
 * derivation in [VescClusterGeometryTest] shows, chaining Current -> Duty -> Power by hand gives
 * exactly the same absolute point [place] computes. Flattening to one absolute offset per dial
 * keeps the whole cluster testable from a single pure function call and keeps
 * [ru.sodovaya.volty.presentation.ride.gauge.VescClusterLayout] a plain "measure each child fixed,
 * place it at its point" loop with no per-dial special casing. The RENDERED geometry is identical
 * either way; only the implementation shape differs.
 *
 * **Two sizes.** `g` is Speed's own size (QML :131-132: `width: parent.height`, i.e. the hero row's
 * height); `g2` is every other dial's size (`gaugeSize2` in the QML, :47: `gaugeSize * 0.55`, but
 * that derivation from the screen's own width/height is out of this file's scope — the two sizes
 * are inputs here, not derived).
 *
 * **Width independence.** Every horizontal offset below is relative to a dial's own PARENT centre,
 * and every dial in this cluster (directly or transitively) roots back to a full-width `Rectangle`
 * whose own horizontal centre is `anchors.centerIn`'d the same way regardless of that Rectangle's
 * actual width (`Layout.fillWidth: true`, QML :66, :124, :430). So the offsets computed here never
 * depend on the cluster's overall width — only on `g`/`g2` — and a Compose host places the whole
 * flattened cluster by centring [VescClusterBox.centerX]`== 0` at whatever horizontal centre it
 * has available. What DOES depend on the container is where that shared centre line falls on
 * screen, which is the Compose layer's job, not this one's.
 *
 * **Height is NOT container-dependent either.** Each row's height is fixed by the QML's own
 * `Layout.preferredHeight` (top/bottom trio: `gaugeSize2*1.1`, QML :68, :431; middle: `gaugeSize`,
 * QML :125), so the cluster's total height ([totalHeight]) falls out of `g`/`g2` alone.
 */
object VescClusterGeometry {

    /** QML :68, :431: `Layout.preferredHeight: gaugeSize2*1.1` on both trio containers. */
    const val TRIO_ROW_HEIGHT_FRACTION: Double = 1.1

    // Top trio, in units of g2 (QML :75-76 Current, :91 Duty, :106-107 Power; size :103-104).
    private const val CURRENT_DX = -0.675
    private const val CURRENT_DY = 0.1
    private const val DUTY_DX = 1.35
    private const val DUTY_DY = 0.0
    private const val POWER_DX = -0.675
    private const val POWER_DY = -0.1
    private const val POWER_SIZE_FRACTION = 1.05

    // Bottom trio: same magnitudes, vertical sign INVERTED relative to the top (QML :439-440 ESC,
    // :466 Motor, :492-493 Consumption; size :489-490).
    private const val ESC_DX = -0.675
    private const val ESC_DY = -0.1
    private const val MOTOR_DX = 1.35
    private const val MOTOR_DY = 0.0
    private const val CONSUMPTION_DX = -0.675
    private const val CONSUMPTION_DY = 0.1
    private const val CONSUMPTION_SIZE_FRACTION = 1.05

    /**
     * The eight dials' centres and sizes for a cluster built from Speed's size [g] and every other
     * dial's size [g2] (both must be positive; a non-positive size can only come from a caller
     * bug upstream and is rejected rather than silently propagating a NaN/degenerate layout).
     */
    fun place(g: Double, g2: Double): Map<VescClusterSlot, VescClusterBox> {
        require(g > 0.0) { "g must be positive, was $g" }
        require(g2 > 0.0) { "g2 must be positive, was $g2" }

        val trioHeight = TRIO_ROW_HEIGHT_FRACTION * g2
        val topRowCenterY = trioHeight / 2.0
        val middleRowCenterY = trioHeight + g / 2.0
        val bottomRowCenterY = trioHeight + g + trioHeight / 2.0

        // Top trio: Current is the row's only dial anchored to the row itself; Duty nests on
        // Current; Power nests on Duty. QML :74-76, :90-91, :105-107.
        val current = VescClusterBox(
            centerX = CURRENT_DX * g2,
            centerY = topRowCenterY + CURRENT_DY * g2,
            size = g2
        )
        val duty = VescClusterBox(
            centerX = current.centerX + DUTY_DX * g2,
            centerY = current.centerY + DUTY_DY * g2,
            size = g2
        )
        val power = VescClusterBox(
            centerX = duty.centerX + POWER_DX * g2,
            centerY = duty.centerY + POWER_DY * g2,
            size = POWER_SIZE_FRACTION * g2
        )

        // Middle: Speed anchors to the row; Battery nests on Speed. QML :133-134, :305-306.
        val speed = VescClusterBox(
            centerX = (g / 4.0 - g2) / 2.0,
            centerY = middleRowCenterY,
            size = g
        )
        val battery = VescClusterBox(
            centerX = speed.centerX + (g / 4.0 + g2 / 2.0),
            centerY = speed.centerY,
            size = g2
        )

        // Bottom trio: ESC anchors to the row; Motor nests on ESC; Consumption nests on Motor.
        // Same X magnitudes as the top trio, Y sign inverted. QML :438-440, :465-466, :491-493.
        val esc = VescClusterBox(
            centerX = ESC_DX * g2,
            centerY = bottomRowCenterY + ESC_DY * g2,
            size = g2
        )
        val motor = VescClusterBox(
            centerX = esc.centerX + MOTOR_DX * g2,
            centerY = esc.centerY + MOTOR_DY * g2,
            size = g2
        )
        val consumption = VescClusterBox(
            centerX = motor.centerX + CONSUMPTION_DX * g2,
            centerY = motor.centerY + CONSUMPTION_DY * g2,
            size = CONSUMPTION_SIZE_FRACTION * g2
        )

        return mapOf(
            VescClusterSlot.CURRENT to current,
            VescClusterSlot.DUTY to duty,
            VescClusterSlot.POWER to power,
            VescClusterSlot.SPEED to speed,
            VescClusterSlot.BATTERY to battery,
            VescClusterSlot.ESC_TEMP to esc,
            VescClusterSlot.MOTOR_TEMP to motor,
            VescClusterSlot.CONSUMPTION to consumption
        )
    }

    /**
     * The cluster's total height: two trio rows (QML :68, :431) plus the hero row between them
     * (QML :125), which is exactly `g` tall. Does not include the odometer/uptime/trip row (QML
     * :539-651) or the incline indicator (QML :372-424) — out of this task's scope.
     */
    fun totalHeight(g: Double, g2: Double): Double = 2.0 * TRIO_ROW_HEIGHT_FRACTION * g2 + g
}

package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt

/** Scope receiver for [VescClusterLayout]'s content — the only place [Modifier.clusterSlot] exists. */
@LayoutScopeMarker
interface VescClusterScope {
    /** Marks a child as belonging to [slot]; [VescClusterLayout] measures and places it accordingly. */
    fun Modifier.clusterSlot(slot: VescClusterSlot): Modifier
}

private object VescClusterScopeInstance : VescClusterScope {
    override fun Modifier.clusterSlot(slot: VescClusterSlot): Modifier =
        this.then(VescClusterSlotElement(slot))
}

private data class VescClusterSlotElement(val slot: VescClusterSlot) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = slot
}

/**
 * Places the eight RT-Data dials per [VescClusterGeometry] — a thin Compose [Layout] over that
 * pure placement function. No positioning arithmetic lives here; this only converts [g]/[g2] to
 * pixels, calls [VescClusterGeometry.place], and turns each returned centre/size into a
 * `measure`+`place` call.
 *
 * [g] and [g2] are the two gauge sizes from `mobile/RtDataSetup.qml` (:45-47) — Speed's own size
 * and every other dial's, respectively. Deriving them from the available screen space (the QML's
 * own `isHorizontal ? ... : ...` formula) is a later task's concern; this layout just consumes
 * whatever two sizes it is given.
 *
 * Children are measured with FIXED constraints — the slot decides the size, not the child, exactly
 * as [ClusterLayout] (the renderer this replaces) already does — and placed in
 * [VescClusterSlot.entries] declaration order. That order IS the QML's own paint order: within
 * each trio a later dial is nested inside the previous one (`dutyGauge` inside `currentGauge`,
 * `powerGauge` inside `dutyGauge`; `motTempGauge` inside `escTempGauge`, `efficiencyGauge` inside
 * `motTempGauge`) and therefore painted after it, i.e. on top; `batteryGauge` is nested inside
 * `speedGauge` the same way. Across trios/pairs the paint order does not matter because the
 * geometry keeps them apart (see [VescClusterGeometry]'s overlap doc) — so no separate z-index is
 * needed, unlike [ClusterLayout]'s explicit [SlotBox.zIndex].
 *
 * The layout's own size is: width = the incoming constraints' width (every [VescClusterBox.centerX]
 * is relative to that width's own centre, by construction — see [VescClusterGeometry]'s "width
 * independence" note); height = [VescClusterGeometry.totalHeight] at the given [g]/[g2], clamped
 * into the incoming height constraints. A caller that wants the cluster to fill more of the screen
 * should pick a larger [g]/[g2], not stretch this layout — stretching would reopen exactly the
 * "gaps between dials" bug this cluster geometry exists to close.
 */
@Composable
fun VescClusterLayout(
    g: Dp,
    g2: Dp,
    modifier: Modifier = Modifier,
    content: @Composable VescClusterScope.() -> Unit
) {
    Layout(content = { VescClusterScopeInstance.content() }, modifier = modifier) { measurables, constraints ->
        val gPx = g.toPx().toDouble()
        val g2Px = g2.toPx().toDouble()
        val boxes = VescClusterGeometry.place(gPx, g2Px)

        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            constraints.minWidth
        }
        val idealHeight = VescClusterGeometry.totalHeight(gPx, g2Px).roundToInt()
        val height = if (constraints.hasBoundedHeight) {
            idealHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        } else {
            idealHeight.coerceAtLeast(constraints.minHeight)
        }
        val centreX = width / 2.0

        val placed = measurables.map { measurable ->
            val slot = measurable.parentData as? VescClusterSlot
                ?: error("VescClusterLayout child is missing a Modifier.clusterSlot(...) — every child must declare its slot")
            val box = boxes.getValue(slot)
            val sizePx = box.size.roundToInt().coerceAtLeast(0)
            val placeable = measurable.measure(Constraints.fixed(sizePx, sizePx))
            Triple(slot, placeable, box)
        }

        layout(width, height) {
            placed.sortedBy { (slot, _, _) -> slot.ordinal }
                .forEach { (_, placeable, box) ->
                    val left = (centreX + box.centerX - box.size / 2.0).roundToInt()
                    val top = (box.centerY - box.size / 2.0).roundToInt()
                    placeable.placeRelative(left, top)
                }
        }
    }
}

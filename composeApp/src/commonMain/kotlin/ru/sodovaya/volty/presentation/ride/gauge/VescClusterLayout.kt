package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
 * pure placement function. No arithmetic lives here; this only reads the incoming constraints,
 * calls [VescClusterGeometry.fit] and [VescClusterGeometry.place], and turns each returned
 * centre/size into a `measure`+`place` call.
 *
 * **Sizing.** The two gauge sizes are DERIVED from the space available, not passed in. They used
 * to be `Dp` parameters, which left this layout unable to keep its promise: an ideal height was
 * computed from the given sizes, clamped into the incoming height constraint, and the dials were
 * then placed at their ideal positions regardless — so a cluster taller than its box reported the
 * clamped height and quietly let the container clip the bottom trio. [VescClusterGeometry.fit]
 * takes both axes, so `totalHeight` is inside the height constraint by construction and there is
 * nothing left to clamp away. An unbounded height (this cluster's normal case: it lives inside a
 * scrolling column) simply means the width decides, exactly as the QML's portrait branch does.
 *
 * Children are measured with FIXED constraints — the slot decides the size, not the child — and
 * painted in [VescClusterGeometry.paintOrder]. That order IS the QML's own: within each trio a
 * later dial is nested inside the previous one (`dutyGauge` inside `currentGauge`, `powerGauge`
 * inside `dutyGauge`; `motTempGauge` inside `escTempGauge`, `efficiencyGauge` inside
 * `motTempGauge`) and therefore painted after it, i.e. on top; `batteryGauge` is nested inside
 * `speedGauge` the same way. Across trios/pairs the paint order does not matter because the
 * geometry keeps them apart (see [VescClusterGeometry]'s overlap doc), so no z-index bookkeeping
 * is needed — Compose paints same-layer siblings in the order they were placed.
 *
 * @param emphasized the dial the rider's "Inner gauge" setting picks out, drawn at
 *   [VescClusterGeometry.EMPHASIS_SIZE_FACTOR] and brought to the front. Null for none.
 */
@Composable
fun VescClusterLayout(
    modifier: Modifier = Modifier,
    emphasized: VescClusterSlot? = null,
    content: @Composable VescClusterScope.() -> Unit
) {
    Layout(content = { VescClusterScopeInstance.content() }, modifier = modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val availableHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight.toDouble()
        } else {
            Double.POSITIVE_INFINITY
        }
        val sizes = VescClusterGeometry.fit(width.toDouble(), availableHeight)

        if (sizes.g <= 0.0) {
            // Degenerate box (zero width, or a zero-height slot): measure nothing, draw nothing —
            // better than handing `place` a non-positive size it is required to reject.
            return@Layout layout(width.coerceAtLeast(0), constraints.minHeight) {}
        }

        val boxes = VescClusterGeometry.place(sizes.g, sizes.g2, emphasized)
        // An emphasised Power or Consumption dial reaches slightly past its row, so the cluster is
        // sized and shifted by the REAL extent rather than by totalHeight — the same class of bug
        // as the one this layout's own sizing used to have, one level down.
        val extent = VescClusterGeometry.verticalExtent(sizes.g, sizes.g2, emphasized)
        // Inside the incoming height by construction (see the class doc); coerced only to satisfy
        // a minHeight larger than the cluster, which pads rather than clips.
        val height = (extent.endInclusive - extent.start)
            .roundToInt()
            .coerceAtLeast(constraints.minHeight)
        val centreX = width / 2.0

        val placed = measurables.associate { measurable ->
            val slot = measurable.parentData as? VescClusterSlot
                ?: error("VescClusterLayout child is missing a Modifier.clusterSlot(...) — every child must declare its slot")
            val box = boxes.getValue(slot)
            val sizePx = box.size.roundToInt().coerceAtLeast(0)
            slot to (measurable.measure(Constraints.fixed(sizePx, sizePx)) to box)
        }

        layout(width, height) {
            VescClusterGeometry.paintOrder(emphasized).forEach { slot ->
                val (placeable, box) = placed[slot] ?: return@forEach
                val left = (centreX + box.centerX - box.size / 2.0).roundToInt()
                val top = (box.centerY - box.size / 2.0 - extent.start).roundToInt()
                placeable.placeRelative(left, top)
            }
        }
    }
}

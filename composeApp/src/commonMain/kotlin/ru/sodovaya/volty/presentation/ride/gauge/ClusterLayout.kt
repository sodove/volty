package ru.sodovaya.volty.presentation.ride.gauge

import androidx.compose.foundation.layout.LayoutScopeMarker
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntRect
import kotlin.math.roundToInt

/**
 * One dial's position in the eight-up Classic VESC cluster: a top fan of three (a larger centre
 * dial forward of its two neighbours), a hero speed dial, a battery dial inset over the hero's
 * lower-right, and a mirrored bottom fan of three.
 */
enum class ClusterSlot {
    TOP_LEFT, TOP_CENTRE, TOP_RIGHT,
    HERO, HERO_INSET,
    BOTTOM_LEFT, BOTTOM_CENTRE, BOTTOM_RIGHT
}

/**
 * A slot's placement, expressed entirely in FRACTIONS so the cluster scales from a small phone to
 * a tablet instead of relying on absolute px (the thing the mockup this replaces got away with
 * only because it never had to render at more than one size).
 *
 * [xFraction] and [sizeFraction] are fractions of the cluster's WIDTH; [yFraction] is a fraction
 * of the cluster's HEIGHT. Dials are square and sized off width (`sizeFraction * width`) so they
 * stay circular regardless of the cluster's height. [zIndex] is this layout's own draw-order key
 * (unrelated to Compose's `Modifier.zIndex`) — higher values are placed later by [ClusterLayout],
 * which is what makes them paint on top of lower-zIndex neighbours.
 */
data class SlotBox(val xFraction: Float, val yFraction: Float, val sizeFraction: Float, val zIndex: Float)

/**
 * Pure placement math for the Classic cluster — no Compose dependency, so the composition (which
 * dial overlaps which, which sits on top, whether everything stays on-screen) is fully unit
 * tested without a UI test harness. [ClusterLayout] only turns this into actual child measurement
 * and placement; it does not decide any of the numbers itself.
 */
object ClusterPlacement {

    /**
     * The cluster's own height, as a multiple of its width — exposed so the screen hosting
     * [ClusterLayout] (Task 5) can size the cluster's box before this layout ever measures a
     * child, e.g. via `Modifier.aspectRatio(ClusterPlacement.ASPECT_RATIO)`.
     */
    const val ASPECT_RATIO: Float = 1.4f

    /**
     * Starting fractions tuned so the composition reads like the mockup's Classic view — a top
     * fan of three with the centre one larger and forward (pushed further off the top edge than
     * its neighbours), a hero speed dial with a battery dial overlapping its lower-right, and a
     * bottom fan of three mirroring the top one (its centre dial pushed further off the bottom
     * edge than its neighbours) — while keeping every dial inside the cluster bounds at the
     * [ASPECT_RATIO] this object declares (checked by `every_dial_stays_inside_the_cluster_bounds`
     * at a 1000x1400 cluster, i.e. exactly this ratio).
     */
    val slots: Map<ClusterSlot, SlotBox> = mapOf(
        ClusterSlot.TOP_LEFT to SlotBox(0.02f, 0.03f, 0.32f, 1f),
        ClusterSlot.TOP_CENTRE to SlotBox(0.31f, 0.00f, 0.36f, 2f),
        ClusterSlot.TOP_RIGHT to SlotBox(0.66f, 0.03f, 0.32f, 1f),
        ClusterSlot.HERO to SlotBox(0.10f, 0.22f, 0.56f, 0f),
        ClusterSlot.HERO_INSET to SlotBox(0.52f, 0.40f, 0.36f, 3f),
        ClusterSlot.BOTTOM_LEFT to SlotBox(0.02f, 0.70f, 0.32f, 1f),
        ClusterSlot.BOTTOM_CENTRE to SlotBox(0.31f, 0.73f, 0.36f, 2f),
        ClusterSlot.BOTTOM_RIGHT to SlotBox(0.66f, 0.70f, 0.32f, 1f)
    )

    /** [slot]'s pixel bounds inside a [width]x[height] cluster — square, sized off [width]. */
    fun place(slot: ClusterSlot, width: Int, height: Int): IntRect {
        val box = slots.getValue(slot)
        val left = (box.xFraction * width).roundToInt()
        val top = (box.yFraction * height).roundToInt()
        val size = (box.sizeFraction * width).roundToInt()
        return IntRect(left = left, top = top, right = left + size, bottom = top + size)
    }
}

/** Scope receiver for [ClusterLayout]'s content — the only place [Modifier.slot] is available. */
@LayoutScopeMarker
interface ClusterScope {
    /** Marks a child as belonging to [slot]; [ClusterLayout] measures and places it accordingly. */
    fun Modifier.slot(slot: ClusterSlot): Modifier
}

private object ClusterScopeInstance : ClusterScope {
    override fun Modifier.slot(slot: ClusterSlot): Modifier = this.then(ClusterSlotElement(slot))
}

private data class ClusterSlotElement(val slot: ClusterSlot) : ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?): Any = slot
}

/**
 * Lays out an eight-dial Classic cluster per [ClusterPlacement]: every child is measured with
 * FIXED constraints from `ClusterPlacement.place(...)` (dials don't get to choose their own size,
 * the slot does) and placed at that offset. Children are placed in ascending [SlotBox.zIndex]
 * order — Compose draws (and hit-tests) same-layer siblings in the order they were placed during
 * layout, so placing the highest-zIndex child last is what makes it paint on top; no
 * `Modifier.zIndex` is needed for this.
 *
 * The cluster sizes its own height from the incoming width via [ClusterPlacement.ASPECT_RATIO] —
 * callers just need to bound the width (e.g. `fillMaxWidth()`), not fight over height too.
 */
@Composable
fun ClusterLayout(modifier: Modifier = Modifier, content: @Composable ClusterScope.() -> Unit) {
    Layout(
        content = { ClusterScopeInstance.content() },
        modifier = modifier
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val idealHeight = (width * ClusterPlacement.ASPECT_RATIO).roundToInt()
        val height = if (constraints.hasBoundedHeight) {
            idealHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        } else {
            idealHeight.coerceAtLeast(constraints.minHeight)
        }

        val placed = measurables.map { measurable ->
            val slot = measurable.parentData as? ClusterSlot
                ?: error("ClusterLayout child is missing a Modifier.slot(...) — every child must declare its slot")
            val rect = ClusterPlacement.place(slot, width, height)
            val placeable = measurable.measure(Constraints.fixed(rect.width, rect.height))
            Triple(ClusterPlacement.slots.getValue(slot).zIndex, placeable, rect)
        }

        layout(width, height) {
            placed.sortedBy { (zIndex, _, _) -> zIndex }
                .forEach { (_, placeable, rect) ->
                    placeable.placeRelative(rect.left, rect.top)
                }
        }
    }
}

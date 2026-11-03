package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.abs

const val OVERLAY_MAX_SAVED = 32
private const val MAX_PRESET_NAME = 64
private const val MAX_WIDGETS = 24
private const val HISTORY_LIMIT = 50

/** Mock D: 5 % margins on the 1080-short design canvas. */
private const val SAFE_MARGIN = 54f

/** How close, in u, an edge or centre has to come to a guide before it is pulled onto it. */
const val OVERLAY_SNAP_DISTANCE = 20f

/** How far, in u, one press of an arrow moves a widget. */
const val OVERLAY_NUDGE_STEP = 4f

fun overlayDesignWidth(landscape: Boolean): Float = if (landscape) 1920f else 1080f
fun overlayDesignHeight(landscape: Boolean): Float = if (landscape) 1080f else 1920f

data class OverlayRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    fun movedTo(x: Float, y: Float) = OverlayRect(x, y, x + width, y + height)
}

/** The rectangle of [box] on a design canvas. */
fun overlayBoxRect(box: OverlayBox, width: Float, height: Float): OverlayRect {
    val x = when (box.anchor.horizontal) {
        -1 -> box.dx
        1 -> width - box.dx - box.width
        else -> width / 2 + box.dx - box.width / 2
    }
    val y = when (box.anchor.vertical) {
        -1 -> box.dy
        1 -> height - box.dy - box.height
        else -> height / 2 + box.dy - box.height / 2
    }
    return OverlayRect(x, y, x + box.width, y + box.height)
}

private fun zone(center: Float, length: Float): Int = when {
    center < length / 3 -> -1
    center > length * 2 / 3 -> 1
    else -> 0
}

private fun anchorOf(horizontal: Int, vertical: Int): OverlayAnchor =
    OverlayAnchor.entries.first { it.horizontal == horizontal && it.vertical == vertical }

/**
 * [box] moved to [rect], stored against the nearest of the nine anchors. A widget dragged to a corner stays in that
 * corner on any other aspect, which is why a position is never stored as a bare coordinate.
 */
fun overlayBoxAt(box: OverlayBox, rect: OverlayRect, width: Float, height: Float): OverlayBox {
    val h = zone(rect.centerX, width)
    val v = zone(rect.centerY, height)
    return box.copy(
        anchor = anchorOf(h, v),
        dx = when (h) { -1 -> rect.left; 1 -> width - rect.right; else -> rect.centerX - width / 2 },
        dy = when (v) { -1 -> rect.top; 1 -> height - rect.bottom; else -> rect.centerY - height / 2 },
        width = rect.width,
        height = rect.height,
    )
}

/** Keeps [rect] inside the 5 % margins; a rectangle bigger than the safe area is centred instead. */
fun overlayClamped(rect: OverlayRect, width: Float, height: Float): OverlayRect {
    fun clamp(start: Float, length: Float, canvas: Float): Float {
        val max = canvas - SAFE_MARGIN - length
        return if (max < SAFE_MARGIN) (canvas - length) / 2 else start.coerceIn(SAFE_MARGIN, max)
    }
    return rect.movedTo(clamp(rect.left, rect.width, width), clamp(rect.top, rect.height, height))
}

data class OverlayGuide(val vertical: Boolean, val position: Float)

data class OverlaySnap(val rect: OverlayRect, val guides: List<OverlayGuide>)

/**
 * Pulls [rect] onto the nearest guide on each axis: frame centre, thirds, the safe margins, and the edges and centres
 * of [others]. Each axis snaps at most once, to the closest line, so a widget never fights two guides.
 */
fun overlaySnap(
    rect: OverlayRect,
    others: List<OverlayRect>,
    width: Float,
    height: Float,
    distance: Float = OVERLAY_SNAP_DISTANCE,
): OverlaySnap {
    fun axis(edges: List<Float>, lines: List<Float>): Pair<Float, Float?> {
        var best = 0f
        var line: Float? = null
        for (edge in edges) for (candidate in lines) {
            val delta = candidate - edge
            if (abs(delta) <= distance && (line == null || abs(delta) < abs(best))) {
                best = delta
                line = candidate
            }
        }
        return best to line
    }
    val verticals = listOf(width / 2, width / 3, width * 2 / 3, SAFE_MARGIN, width - SAFE_MARGIN) +
        others.flatMap { listOf(it.left, it.centerX, it.right) }
    val horizontals = listOf(height / 2, height / 3, height * 2 / 3, SAFE_MARGIN, height - SAFE_MARGIN) +
        others.flatMap { listOf(it.top, it.centerY, it.bottom) }
    val (dx, x) = axis(listOf(rect.left, rect.centerX, rect.right), verticals)
    val (dy, y) = axis(listOf(rect.top, rect.centerY, rect.bottom), horizontals)
    return OverlaySnap(
        rect.movedTo(rect.left + dx, rect.top + dy),
        listOfNotNull(x?.let { OverlayGuide(true, it) }, y?.let { OverlayGuide(false, it) }),
    )
}

/** The widget under a touch: the smallest one containing it, so a card inside a big dial's box stays reachable. */
fun overlayHitTest(widgets: List<OverlayWidgetBounds>, x: Float, y: Float, slop: Float = 16f): String? =
    widgets
        .filter { x >= it.left - slop && x <= it.right + slop && y >= it.top - slop && y <= it.bottom + slop }
        .minByOrNull { (it.right - it.left) * (it.bottom - it.top) }
        ?.id

/** True when leaving the selected preset would lose it: it is neither a pristine built-in nor a saved preset. */
fun overlayPresetSwitchLosesEdits(document: OverlayDocument): Boolean {
    val selected = document.selected
    return selected != OverlayTemplates.byId(selected.id) && selected !in document.saved
}

/** The selected preset kept under a new id, or null when the saved list is full or the name is empty. */
fun overlayKeepAsCopy(document: OverlayDocument, id: String, name: String): OverlayDocument? {
    // The validator caps a name at 64; «…, копия» on a long name would otherwise be refused without a word.
    val trimmed = name.trim().take(MAX_PRESET_NAME).trim()
    if (trimmed.isEmpty() || document.saved.size >= OVERLAY_MAX_SAVED) return null
    val copy = document.selected.copy(id = id, name = trimmed)
    return document.copy(selected = copy, saved = document.saved + copy)
}

private val PLACED_FRAMES = listOf(1080 to 1920, 1920 to 1080)

/**
 * Gives every flowing widget a real place, taken from where the flow puts it now, in both orientations. Studio ports
 * and old presets flow, and a flow has no position to drag, so the first edit that needs one freezes the whole layout:
 * pulling one card out would otherwise reshuffle all the others under the rider's finger.
 */
fun overlayMaterialized(preset: OverlayPreset, strings: VideoOverlayStrings, system: UnitSystem, measure: OverlayTextMeasure): OverlayPreset {
    if (preset.widgets.all { it.placement != null }) return preset
    val scenes = PLACED_FRAMES.map { (w, h) -> buildOverlayScene(OverlaySceneInput(preset, null, strings, system, w, h), measure) }
    fun box(scene: OverlayScene, landscape: Boolean, id: String): OverlayBox? {
        val bounds = scene.widgets.firstOrNull { it.id == id } ?: return null
        val blank = OverlayBox(OverlayAnchor.CENTER, 0f, 0f, 1f, 1f, bounds.valueSize, bounds.unitSize, bounds.labelSize)
        val rect = OverlayRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        return overlayBoxAt(blank, rect, overlayDesignWidth(landscape), overlayDesignHeight(landscape))
    }
    fun spec(scene: OverlayScene, frameSpec: OverlayFrameSpec?): OverlayFrameSpec {
        if (frameSpec != null) return frameSpec
        val flowed = preset.widgets.filter { it.placement == null && !isDial(it.kind) }.map { it.id }
        val bottom = scene.widgets.filter { it.id in flowed }.maxOfOrNull { it.bottom } ?: 0f
        return OverlayFrameSpec(scrimTop = if (bottom > 0f) bottom + 120f else 0f, chipY = (bottom + 16f).coerceAtLeast(62f))
    }
    return preset.copy(
        widgets = preset.widgets.map { widget ->
            if (widget.placement != null) return@map widget
            val arc = if (isDial(widget.kind)) {
                // A placed dial's stroke is in u, a flowing one's in the old design pixels.
                widget.arc.copy(thickness = (widget.arc.thickness * 64f / 28f).coerceAtMost(50f))
            } else {
                widget.arc
            }
            widget.copy(placement = OverlayPlacement(box(scenes[0], false, widget.id), box(scenes[1], true, widget.id)), arc = arc)
        },
        layout = OverlayPresetLayout(spec(scenes[0], preset.layout?.portrait), spec(scenes[1], preset.layout?.landscape)),
    )
}

private fun isDial(kind: OverlayWidgetKind) =
    kind == OverlayWidgetKind.ARC || kind == OverlayWidgetKind.ARC_LEFT || kind == OverlayWidgetKind.ARC_RIGHT

/** A time or a distance has no scale, so a dial or a bar of one would be a drawing of nothing. */
fun overlayKindAllowed(kind: OverlayWidgetKind, metric: VideoMetric): Boolean {
    val trip = metric == VideoMetric.ELAPSED || metric == VideoMetric.DISTANCE
    return !trip || kind == OverlayWidgetKind.VALUE_CARD || kind == OverlayWidgetKind.ICON_VALUE || kind == OverlayWidgetKind.TEXT
}

/** The accent a metric wears in the built-in presets. */
fun overlayDefaultAccent(metric: VideoMetric): String = OverlayPalette.hex(
    when (metric) {
        VideoMetric.SPEED, VideoMetric.CONTROLLER_POWER, VideoMetric.BATTERY_POWER -> OverlayPalette.CYAN
        VideoMetric.DUTY -> OverlayPalette.GREEN
        VideoMetric.SOC, VideoMetric.VOLTAGE, VideoMetric.BATTERY_CURRENT, VideoMetric.CONTROLLER_CURRENT -> OverlayPalette.BATTERY
        VideoMetric.ESC_TEMPERATURE, VideoMetric.BATTERY_TEMPERATURE -> OverlayPalette.ORANGE
        VideoMetric.MOTOR_TEMPERATURE, VideoMetric.MOTOR_CURRENT -> OverlayPalette.PINK
        VideoMetric.ELAPSED, VideoMetric.DISTANCE -> OverlayPalette.TEXT
    },
)

private fun arcFor(metric: VideoMetric, thickness: Float): OverlayArc = OverlayArc(
    min = 0f,
    max = if (metric == VideoMetric.DUTY || metric == VideoMetric.SOC) 100f else 120f,
    thickness = thickness,
    autoScale = overlayHasLadder(metric),
)

private data class KindShape(val width: Float, val height: Float, val value: Float, val unit: Float, val label: Float)

private fun shapeOf(kind: OverlayWidgetKind): KindShape = when (kind) {
    OverlayWidgetKind.VALUE_CARD -> KindShape(313f, 166f, 64f, 30f, 24f)
    // A chip is one line, icon and number: telemetry-studio's icon_value height at a 64u number.
    OverlayWidgetKind.ICON_VALUE -> KindShape(320f, 121f, 64f, 51f, 0f)
    OverlayWidgetKind.ARC -> KindShape(400f, 400f, 110f, 32f, 26f)
    OverlayWidgetKind.ARC_LEFT, OverlayWidgetKind.ARC_RIGHT -> KindShape(452f, 452f, 150f, 32f, 26f)
    OverlayWidgetKind.BAR -> KindShape(408f, 170f, 110f, 46f, 28f)
    OverlayWidgetKind.TEXT -> KindShape(300f, 106f, 64f, 30f, 24f)
}

sealed interface OverlayEditorConfirm {
    data class SwitchTo(val preset: OverlayPreset) : OverlayEditorConfirm
    data object Close : OverlayEditorConfirm
    data class Delete(val id: String) : OverlayEditorConfirm
}

data class OverlayEditorDrag(
    val id: String,
    val before: OverlayPreset,
    val rawLeft: Float,
    val rawTop: Float,
    val guides: List<OverlayGuide> = emptyList(),
)

/** One step of history. A preset switch moves the baseline too, and undoing it has to move it back. */
data class OverlayEditorStep(val draft: OverlayPreset, val baseline: OverlayPreset)

/**
 * The editor is a draft on top of [document]: a video is only ever drawn from what Save (or a preset switch) put in the
 * document. **Retraction:** the first editor published every edit live, so one tap on a template chip silently replaced
 * a rider's work, and "editing HUD" rewrote the selected copy of a built-in with nothing saved anywhere.
 */
data class OverlayEditorState(
    val document: OverlayDocument,
    val draft: OverlayPreset,
    val baseline: OverlayPreset,
    val selectedId: String? = null,
    val landscape: Boolean = false,
    val undo: List<OverlayEditorStep> = emptyList(),
    val redo: List<OverlayEditorStep> = emptyList(),
    val drag: OverlayEditorDrag? = null,
    val confirm: OverlayEditorConfirm? = null,
    val closed: Boolean = false,
) {
    val edited: Boolean get() = draft != baseline
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val selected: OverlayWidget? get() = draft.widgets.firstOrNull { it.id == selectedId }

    /** True when Save rewrites a saved preset; a built-in or an orphan can only be saved as a copy. */
    val canOverwrite: Boolean get() = document.saved.any { it.id == draft.id }
    val canSaveCopy: Boolean get() = document.saved.size < OVERLAY_MAX_SAVED
    val isBuiltIn: Boolean get() = OverlayTemplates.byId(draft.id) != null && !canOverwrite

    companion object {
        fun open(document: OverlayDocument, landscape: Boolean = false) =
            OverlayEditorState(document, document.selected, document.selected, landscape = landscape)
    }
}

sealed interface OverlayEditorAction {
    data class Select(val id: String?) : OverlayEditorAction
    data class SetOrientation(val landscape: Boolean) : OverlayEditorAction
    data class DragStart(val id: String, val lift: Float) : OverlayEditorAction
    data class DragBy(val dx: Float, val dy: Float) : OverlayEditorAction
    data object DragEnd : OverlayEditorAction
    data object DragCancel : OverlayEditorAction
    data class Nudge(val dx: Float, val dy: Float) : OverlayEditorAction
    data object RestorePlace : OverlayEditorAction
    data class SetHidden(val hidden: Boolean) : OverlayEditorAction
    data class SetMetric(val metric: VideoMetric) : OverlayEditorAction
    data class SetLabel(val label: String) : OverlayEditorAction
    data class SetSize(val size: OverlayWidgetSize) : OverlayEditorAction
    data class SetKind(val kind: OverlayWidgetKind) : OverlayEditorAction
    data class SetAccent(val color: String) : OverlayEditorAction

    /** An [OverlayIcons] name, or null for the metric's own icon. An unknown name is ignored. */
    data class SetIcon(val name: String?) : OverlayEditorAction
    data class AddWidget(val id: String, val metric: VideoMetric) : OverlayEditorAction
    data object DeleteWidget : OverlayEditorAction
    data object Undo : OverlayEditorAction
    data object Redo : OverlayEditorAction
    data class RequestPreset(val preset: OverlayPreset) : OverlayEditorAction
    data object RequestClose : OverlayEditorAction
    data class RequestDelete(val id: String) : OverlayEditorAction
    data object ConfirmDiscard : OverlayEditorAction
    data object ConfirmCancel : OverlayEditorAction
    data object Save : OverlayEditorAction
    data class SaveAs(val id: String, val name: String) : OverlayEditorAction
}

/** What the reducer needs from outside: the flow is laid out with real words and a real font. */
class OverlayEditorEnv(
    val strings: VideoOverlayStrings = VideoOverlayStrings(),
    val system: UnitSystem = UnitSystem.METRIC,
    val measure: OverlayTextMeasure = EstimatedOverlayTextMeasure,
)

fun reduceOverlayEditor(state: OverlayEditorState, action: OverlayEditorAction, env: OverlayEditorEnv = OverlayEditorEnv()): OverlayEditorState =
    OverlayEditorReducer(state, env).reduce(action)

private class OverlayEditorReducer(private val state: OverlayEditorState, private val env: OverlayEditorEnv) {
    private val width = overlayDesignWidth(state.landscape)
    private val height = overlayDesignHeight(state.landscape)

    fun reduce(action: OverlayEditorAction): OverlayEditorState = when (action) {
        is OverlayEditorAction.Select -> state.copy(selectedId = action.id?.takeIf { id -> state.draft.widgets.any { it.id == id } })
        is OverlayEditorAction.SetOrientation -> state.copy(landscape = action.landscape, drag = null)
        is OverlayEditorAction.DragStart -> dragStart(action)
        is OverlayEditorAction.DragBy -> dragBy(action)
        OverlayEditorAction.DragEnd -> state.drag?.let { drag ->
            if (state.draft == drag.before) state.copy(drag = null)
            else recorded(drag.before, state.baseline).copy(drag = null)
        } ?: state
        OverlayEditorAction.DragCancel -> state.drag?.let { state.copy(draft = it.before, drag = null) } ?: state
        is OverlayEditorAction.Nudge -> editPlaced { widget ->
            val box = boxOf(widget) ?: return@editPlaced widget
            val rect = overlayBoxRect(box, width, height)
            val moved = overlayClamped(rect.movedTo(rect.left + action.dx, rect.top + action.dy), width, height)
            withBox(widget, overlayBoxAt(box, moved, width, height))
        }
        OverlayEditorAction.RestorePlace -> restorePlace()
        is OverlayEditorAction.SetHidden -> setHidden(action.hidden)
        is OverlayEditorAction.SetMetric -> edit { setMetric(it, action.metric) }
        is OverlayEditorAction.SetLabel -> edit { it.copy(label = action.label.take(MAX_LABEL)) }
        is OverlayEditorAction.SetSize -> editPlaced { resized(it, action.size) }
        is OverlayEditorAction.SetKind -> editPlaced { rekinded(it, action.kind) }
        is OverlayEditorAction.SetAccent -> edit { it.copy(style = it.style.copy(accent = action.color)) }
        is OverlayEditorAction.SetIcon ->
            if (action.name != null && OverlayIcons.byName(action.name) == null) state
            else edit { it.copy(iconName = action.name, icon = OverlayIcon.AUTO) }
        is OverlayEditorAction.AddWidget -> addWidget(action)
        OverlayEditorAction.DeleteWidget -> deleteWidget()
        OverlayEditorAction.Undo -> step(state.undo, state.redo) { rest, back -> state.copy(undo = rest, redo = back) }
        OverlayEditorAction.Redo -> step(state.redo, state.undo) { rest, back -> state.copy(redo = rest, undo = back) }
        is OverlayEditorAction.RequestPreset -> requestPreset(action.preset)
        OverlayEditorAction.RequestClose ->
            if (state.edited) state.copy(confirm = OverlayEditorConfirm.Close) else state.copy(closed = true)
        is OverlayEditorAction.RequestDelete -> state.copy(confirm = OverlayEditorConfirm.Delete(action.id))
        OverlayEditorAction.ConfirmDiscard -> resolve(state, state.confirm)
        OverlayEditorAction.ConfirmCancel -> state.copy(confirm = null)
        OverlayEditorAction.Save -> save()
        is OverlayEditorAction.SaveAs -> saveAs(action)
    }

    private fun boxOf(widget: OverlayWidget): OverlayBox? =
        widget.placement?.let { if (state.landscape) it.landscape else it.portrait }

    private fun withBox(widget: OverlayWidget, box: OverlayBox?): OverlayWidget {
        val placement = widget.placement ?: OverlayPlacement(null, null)
        return widget.copy(placement = if (state.landscape) placement.copy(landscape = box) else placement.copy(portrait = box))
    }

    private fun recorded(before: OverlayPreset, baseline: OverlayPreset): OverlayEditorState = state.copy(
        undo = (state.undo + OverlayEditorStep(before, baseline)).takeLast(HISTORY_LIMIT),
        redo = emptyList(),
    )

    private fun commit(next: OverlayPreset): OverlayEditorState {
        if (next == state.draft || !validOverlayPreset(next)) return state
        return recorded(state.draft, state.baseline).copy(draft = next)
    }

    private fun edit(transform: (OverlayWidget) -> OverlayWidget): OverlayEditorState {
        val id = state.selectedId ?: return state
        return commit(state.draft.copy(widgets = state.draft.widgets.map { if (it.id == id) transform(it) else it }))
    }

    /** As [edit], for a change that needs real boxes. The freeze and the change are one undo step. */
    private fun editPlaced(transform: (OverlayWidget) -> OverlayWidget): OverlayEditorState {
        val id = state.selectedId ?: return state
        val placed = overlayMaterialized(state.draft, env.strings, env.system, env.measure)
        return commit(placed.copy(widgets = placed.widgets.map { if (it.id == id) transform(it) else it }))
    }

    private fun dragStart(action: OverlayEditorAction.DragStart): OverlayEditorState {
        val placed = overlayMaterialized(state.draft, env.strings, env.system, env.measure)
        val widget = placed.widgets.firstOrNull { it.id == action.id } ?: return state
        val rect = overlayBoxRect(boxOf(widget) ?: return state, width, height)
        val lifted = state.copy(
            draft = placed,
            selectedId = action.id,
            drag = OverlayEditorDrag(action.id, state.draft, rect.left, rect.top),
        )
        return OverlayEditorReducer(lifted, env).dragBy(OverlayEditorAction.DragBy(0f, -action.lift))
    }

    private fun dragBy(action: OverlayEditorAction.DragBy): OverlayEditorState {
        val drag = state.drag ?: return state
        val left = drag.rawLeft + action.dx
        val top = drag.rawTop + action.dy
        val widget = state.draft.widgets.firstOrNull { it.id == drag.id } ?: return state
        val box = boxOf(widget) ?: return state
        val others = state.draft.widgets.filter { it.id != drag.id }.mapNotNull(::boxOf).map { overlayBoxRect(it, width, height) }
        val raw = overlayBoxRect(box, width, height).movedTo(left, top)
        val snapped = overlaySnap(overlayClamped(raw, width, height), others, width, height)
        val moved = overlayClamped(snapped.rect, width, height)
        val next = withBox(widget, overlayBoxAt(box, moved, width, height))
        return state.copy(
            draft = state.draft.copy(widgets = state.draft.widgets.map { if (it.id == drag.id) next else it }),
            drag = drag.copy(rawLeft = left, rawTop = top, guides = snapped.guides),
        )
    }

    private fun restorePlace(): OverlayEditorState {
        val original = state.baseline.widgets.firstOrNull { it.id == state.selectedId }?.placement ?: return state
        return edit { widget ->
            withBox(widget, if (state.landscape) original.landscape else original.portrait)
        }
    }

    private fun setHidden(hidden: Boolean): OverlayEditorState = editPlaced { widget ->
        when {
            hidden -> withBox(widget, null)
            boxOf(widget) != null -> widget
            else -> {
                val placement = widget.placement
                val other = (if (state.landscape) placement?.portrait else placement?.landscape)
                    ?: return@editPlaced widget
                val rect = overlayBoxRect(other, overlayDesignWidth(!state.landscape), overlayDesignHeight(!state.landscape))
                val centered = rect.movedTo((width - rect.width) / 2, (height - rect.height) / 2)
                withBox(widget, overlayBoxAt(other, overlayClamped(centered, width, height), width, height))
            }
        }
    }

    /**
     * The label and icon follow the metric ([overlayWidgetWithMetric]), and so do the rules and the scale: a rule that
     * turns a temperature orange at 60 means nothing over a voltage.
     */
    private fun setMetric(widget: OverlayWidget, metric: VideoMetric): OverlayWidget {
        if (metric == widget.metric) return widget
        val changed = overlayWidgetWithMetric(widget, metric)
        val kind = if (overlayKindAllowed(widget.kind, metric)) widget.kind else OverlayWidgetKind.VALUE_CARD
        val accent = if (widget.style.accent == overlayDefaultAccent(widget.metric)) overlayDefaultAccent(metric) else widget.style.accent
        return changed.copy(
            kind = kind,
            style = changed.style.copy(accent = accent),
            rules = OverlayTemplates.defaultRules(metric),
            arc = arcFor(metric, widget.arc.thickness),
            bar = widget.bar.copy(secondary = widget.bar.secondary.takeIf { metric == VideoMetric.SOC }),
        )
    }

    private fun scaledBox(box: OverlayBox?, ratio: Float, landscape: Boolean): OverlayBox? {
        box ?: return null
        val w = overlayDesignWidth(landscape)
        val h = overlayDesignHeight(landscape)
        val rect = overlayBoxRect(box, w, h)
        val newWidth = (rect.width * ratio).coerceAtMost(w - SAFE_MARGIN * 2)
        val newHeight = (rect.height * ratio).coerceAtMost(h - SAFE_MARGIN * 2)
        val grown = OverlayRect(
            rect.centerX - newWidth / 2, rect.centerY - newHeight / 2,
            rect.centerX + newWidth / 2, rect.centerY + newHeight / 2,
        )
        val sized = box.copy(valueSize = box.valueSize * ratio, unitSize = box.unitSize * ratio, labelSize = box.labelSize * ratio)
        return overlayBoxAt(sized, overlayClamped(grown, w, h), w, h)
    }

    private fun resized(widget: OverlayWidget, size: OverlayWidgetSize): OverlayWidget {
        val ratio = size.scale / widget.size.scale
        val placement = widget.placement ?: return widget
        return widget.copy(
            size = size,
            placement = OverlayPlacement(scaledBox(placement.portrait, ratio, false), scaledBox(placement.landscape, ratio, true)),
        )
    }

    private fun reshaped(box: OverlayBox?, kind: OverlayWidgetKind, scale: Float, landscape: Boolean): OverlayBox? {
        box ?: return null
        val w = overlayDesignWidth(landscape)
        val h = overlayDesignHeight(landscape)
        val shape = shapeOf(kind)
        val rect = overlayBoxRect(box, w, h)
        val boxWidth = shape.width * scale
        val boxHeight = shape.height * scale
        val centered = OverlayRect(rect.centerX - boxWidth / 2, rect.centerY - boxHeight / 2, rect.centerX + boxWidth / 2, rect.centerY + boxHeight / 2)
        val sized = box.copy(valueSize = shape.value * scale, unitSize = shape.unit * scale, labelSize = shape.label * scale)
        return overlayBoxAt(sized, overlayClamped(centered, w, h), w, h)
    }

    private fun rekinded(widget: OverlayWidget, kind: OverlayWidgetKind): OverlayWidget {
        if (kind == widget.kind || !overlayKindAllowed(kind, widget.metric)) return widget
        val placement = widget.placement ?: return widget
        val scale = widget.size.scale
        return widget.copy(
            kind = kind,
            arc = if (isDial(kind)) arcFor(widget.metric, 24f) else widget.arc,
            bar = if (kind == OverlayWidgetKind.BAR) OverlayBar() else widget.bar,
            placement = OverlayPlacement(reshaped(placement.portrait, kind, scale, false), reshaped(placement.landscape, kind, scale, true)),
        )
    }

    private fun addWidget(action: OverlayEditorAction.AddWidget): OverlayEditorState {
        if (state.draft.widgets.size >= MAX_WIDGETS || state.draft.widgets.any { it.id == action.id }) return state
        val placed = overlayMaterialized(state.draft, env.strings, env.system, env.measure)
        val shape = shapeOf(OverlayWidgetKind.VALUE_CARD)
        val middle = OverlayBox(OverlayAnchor.CENTER, 0f, 0f, shape.width, shape.height, shape.value, shape.unit, shape.label)
        val widget = OverlayWidget(
            id = action.id,
            metric = action.metric,
            kind = OverlayWidgetKind.VALUE_CARD,
            style = OverlayTemplates.cardStyle(overlayDefaultAccent(action.metric)),
            rules = OverlayTemplates.defaultRules(action.metric),
            arc = arcFor(action.metric, 24f),
            placement = OverlayPlacement(middle, middle),
        )
        return commit(placed.copy(widgets = placed.widgets + widget)).copy(selectedId = action.id)
    }

    private fun deleteWidget(): OverlayEditorState {
        val id = state.selectedId ?: return state
        return commit(state.draft.copy(widgets = state.draft.widgets.filter { it.id != id })).let { next ->
            if (next.draft.widgets.any { it.id == id }) next else next.copy(selectedId = null)
        }
    }

    private fun step(
        from: List<OverlayEditorStep>,
        to: List<OverlayEditorStep>,
        apply: (List<OverlayEditorStep>, List<OverlayEditorStep>) -> OverlayEditorState,
    ): OverlayEditorState {
        val target = from.lastOrNull() ?: return state
        val next = apply(from.dropLast(1), to + OverlayEditorStep(state.draft, state.baseline))
        return next.copy(
            draft = target.draft,
            baseline = target.baseline,
            document = state.document.copy(selected = target.baseline),
            selectedId = state.selectedId?.takeIf { id -> target.draft.widgets.any { it.id == id } },
            drag = null,
        )
    }

    private fun requestPreset(preset: OverlayPreset): OverlayEditorState {
        if (preset == state.draft) return state
        val losesWork = state.edited || overlayPresetSwitchLosesEdits(state.document.copy(selected = state.baseline))
        return if (losesWork) state.copy(confirm = OverlayEditorConfirm.SwitchTo(preset)) else load(state, preset)
    }

    private fun load(from: OverlayEditorState, preset: OverlayPreset): OverlayEditorState = from.copy(
        undo = (from.undo + OverlayEditorStep(from.draft, from.baseline)).takeLast(HISTORY_LIMIT),
        redo = emptyList(),
        draft = preset,
        baseline = preset,
        document = from.document.copy(selected = preset),
        selectedId = null,
        drag = null,
        confirm = null,
    )

    private fun delete(from: OverlayEditorState, id: String): OverlayEditorState {
        val saved = from.document.saved.filterNot { it.id == id }
        if (from.draft.id != id && from.baseline.id != id) {
            return from.copy(document = from.document.copy(saved = saved), confirm = null, undo = emptyList(), redo = emptyList())
        }
        val hud = OverlayTemplates.hud()
        return from.copy(
            document = from.document.copy(selected = hud, saved = saved),
            draft = hud,
            baseline = hud,
            selectedId = null,
            confirm = null,
            undo = emptyList(),
            redo = emptyList(),
        )
    }

    /** What a pending question was waiting for: after Discard, or after its work was saved. */
    private fun resolve(from: OverlayEditorState, confirm: OverlayEditorConfirm?): OverlayEditorState = when (confirm) {
        is OverlayEditorConfirm.SwitchTo -> load(from, confirm.preset)
        OverlayEditorConfirm.Close -> from.copy(confirm = null, closed = true)
        is OverlayEditorConfirm.Delete -> delete(from, confirm.id)
        null -> from
    }

    private fun afterSave(document: OverlayDocument, saved: OverlayPreset): OverlayEditorState {
        val next = state.copy(document = document, draft = saved, baseline = saved, undo = emptyList(), redo = emptyList())
        return if (state.confirm is OverlayEditorConfirm.Delete) next else resolve(next, state.confirm)
    }

    private fun save(): OverlayEditorState {
        if (!state.canOverwrite || !validOverlayPreset(state.draft)) return state
        val document = state.document.copy(
            selected = state.draft,
            saved = state.document.saved.map { if (it.id == state.draft.id) state.draft else it },
        )
        return afterSave(document, state.draft)
    }

    private fun saveAs(action: OverlayEditorAction.SaveAs): OverlayEditorState {
        if (state.document.saved.any { it.id == action.id } || OverlayTemplates.byId(action.id) != null) return state
        val kept = overlayKeepAsCopy(state.document.copy(selected = state.draft), action.id, action.name) ?: return state
        if (!validOverlayPreset(kept.selected)) return state
        return afterSave(kept, kept.selected)
    }
}

private const val MAX_LABEL = 32

/** The numbers the editor's stage is drawn with, the mock's own; the stage marks them as a sample. */
fun overlaySampleSnapshot(): VideoSnapshot = VideoSnapshot(
    timeMillis = 0,
    vehicleId = "sample",
    readings = mapOf(
        VideoMetric.SPEED to VideoReading(42.0),
        VideoMetric.DUTY to VideoReading(61.0, estimated = true),
        VideoMetric.ESC_TEMPERATURE to VideoReading(34.0),
        VideoMetric.MOTOR_TEMPERATURE to VideoReading(41.0),
        VideoMetric.MOTOR_CURRENT to VideoReading(-24.0),
        VideoMetric.CONTROLLER_CURRENT to VideoReading(-20.0),
        VideoMetric.CONTROLLER_POWER to VideoReading(1762.0),
        VideoMetric.SOC to VideoReading(78.0),
        VideoMetric.VOLTAGE to VideoReading(96.4),
        VideoMetric.BATTERY_CURRENT to VideoReading(-18.0),
        VideoMetric.BATTERY_POWER to VideoReading(1735.0),
        VideoMetric.BATTERY_TEMPERATURE to VideoReading(31.0),
    ),
)

val OVERLAY_SAMPLE_TRIP = OverlayTrip(elapsedSeconds = 768, distanceKm = 8.4)

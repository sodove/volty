package ru.sodovaya.volty.domain.video

import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/** Pixels of the upright frame. All colours are ARGB. */
sealed interface OverlayOp

data class OverlayScrimOp(val top: Float, val bottom: Float, val darkAtTop: Boolean) : OverlayOp
data class OverlayDiscOp(val cx: Float, val cy: Float, val radius: Float) : OverlayOp
data class OverlayArcOp(
    val cx: Float,
    val cy: Float,
    val radius: Float,
    val startDegrees: Float,
    val sweepDegrees: Float,
    val strokeWidth: Float,
    val color: Int,
) : OverlayOp

data class OverlayRectOp(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radius: Float,
    val fill: Int?,
    val stroke: Int? = null,
    val strokeWidth: Float = 0f,
) : OverlayOp

data class OverlayRun(val text: String, val size: Float, val color: Int, val bold: Boolean = true, val gapBefore: Float = 0f)

enum class OverlayTextAlign { START, CENTER, END }

data class OverlayTextOp(
    val runs: List<OverlayRun>,
    val x: Float,
    val baseline: Float,
    val align: OverlayTextAlign = OverlayTextAlign.START,
    val haloWidth: Float = 0f,
    val trackingEm: Float = 0f,
) : OverlayOp

data class OverlayIconOp(val icon: OverlayIcon, val x: Float, val y: Float, val size: Float, val color: Int) : OverlayOp

data class OverlayWidgetBounds(
    val id: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val valueSize: Float = 0f,
    val unitSize: Float = 0f,
    val labelSize: Float = 0f,
)

/** Everything one overlay frame draws. [widgets] is for tests and for picking a widget in the editor. */
data class OverlayScene(val width: Int, val height: Int, val ops: List<OverlayOp>, val widgets: List<OverlayWidgetBounds>)

fun interface OverlayTextMeasure {
    fun width(text: String, size: Float, bold: Boolean): Float
}

/** Roboto's proportions, close enough for a pure layout decision in tests. The app measures with the real font. */
object EstimatedOverlayTextMeasure : OverlayTextMeasure {
    override fun width(text: String, size: Float, bold: Boolean): Float = text.sumOf { char ->
        when (char) {
            ' ' -> 0.25
            '.', ':', ',' -> 0.3
            in '0'..'9' -> 0.57
            else -> 0.62
        }
    }.toFloat() * size * (if (bold) 1.03f else 1f)
}

data class OverlaySceneInput(
    val preset: OverlayPreset,
    val snapshot: VideoSnapshot?,
    val strings: VideoOverlayStrings,
    val system: UnitSystem,
    val width: Int,
    val height: Int,
    val trip: OverlayTrip = OverlayTrip(),
    val scales: Map<VideoMetric, Float> = emptyMap(),
    /** Smoothed values for sweeps only; see `VideoDialSmoother`. Numbers never read these. */
    val dials: Map<VideoMetric, Double> = emptyMap(),
)

/**
 * The overlay's geometry and every decision about it, with no Android in sight: the Android drawer only replays [ops],
 * which is what makes one drawer serve the preview, the recording and later the editor.
 *
 * The unit is the short edge: `u = short edge / 1080` (mock C: value 64u, label 24u). **Retraction:** the first port
 * scaled by width, which applied a landscape design to a narrow frame and left 10.5 px of value text in 720p portrait.
 *
 * [OverlayFrameScale.layoutScale] shrinks a layout on a frame shorter than the 16:9 it was drawn for, so a 4:3 frame
 * keeps its proportions instead of colliding.
 */
fun buildOverlayScene(input: OverlaySceneInput, measure: OverlayTextMeasure = EstimatedOverlayTextMeasure): OverlayScene =
    OverlaySceneBuilder(input, measure).build()

/** The two scales of a frame. */
data class OverlayFrameScale(val width: Int, val height: Int) {
    val landscape: Boolean = width > height
    val short: Float = min(width, height).toFloat()
    val unit: Float = short / 1080f
    private val long: Float = maxOf(width, height).toFloat()

    /** 1 at 16:9 and wider, below that on a shorter frame. */
    val layoutScale: Float = min(1f, long / unit / 1920f)
    val scaledUnit: Float = unit * layoutScale
    val margin: Float = short * 0.05f
}

private data class PixelBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val value: Float,
    val unit: Float,
    val label: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val cx: Float get() = (left + right) / 2
    val cy: Float get() = (top + bottom) / 2
}

private const val LEGACY_SIZE = 64f / 28f
private const val FLOW_GAP = 16f
private const val FLOW_CARD_HEIGHT = 148f
private const val FLOW_SHRINK = 0.85f
private const val FLOW_SHRINK_STEPS = 10
private const val CARD_PADDING = 24f
private const val MIN_FIT = 0.5f
private const val CHIP_HEIGHT = 66f
private const val CHIP_FONT = 34f
private const val CHIP_GAP = 12f
private const val CHIP_SIDE_PADDING = 28f
private const val CHIP_TRACKING = 0.03f
private const val LABEL_TRACKING = 0.08f
private const val FULL_START = 135f
private const val FULL_SWEEP = 270f
private const val LEFT_START = 225f
private const val RIGHT_START = 315f
private const val QUARTER_SWEEP = 90f

private class OverlaySceneBuilder(private val input: OverlaySceneInput, private val measure: OverlayTextMeasure) {
    private val frame = OverlayFrameScale(input.width, input.height)
    private val width = input.width.toFloat()
    private val height = input.height.toFloat()
    private val ops = mutableListOf<OverlayOp>()
    private val bounds = mutableListOf<OverlayWidgetBounds>()

    fun build(): OverlayScene {
        if (input.width <= 0 || input.height <= 0) return OverlayScene(input.width, input.height, emptyList(), emptyList())
        val spec = input.preset.layout?.let { if (frame.landscape) it.landscape else it.portrait }
        val flow = input.preset.widgets.filter { it.placement == null && !it.isArc() }
        val flowed = layoutFlow(flow)
        val flowBottom = flowed.values.maxOfOrNull { it.bottom }
        val scrimTop = spec?.scrimTop?.times(frame.scaledUnit) ?: flowBottom?.plus(120f * frame.unit) ?: 0f
        val scrimBottom = spec?.scrimBottom?.times(frame.scaledUnit) ?: 0f
        if (scrimBottom > 0f) ops += OverlayScrimOp(height - min(scrimBottom, height), height, darkAtTop = false)
        if (scrimTop > 0f) ops += OverlayScrimOp(0f, min(scrimTop, height), darkAtTop = true)

        for (widget in input.preset.widgets) {
            val box = flowed[widget.id] ?: resolve(widget) ?: continue
            draw(widget, box)
            bounds += OverlayWidgetBounds(widget.id, box.left, box.top, box.right, box.bottom, box.value, box.unit, box.label)
        }
        val chipTop = spec?.chipY?.times(frame.scaledUnit) ?: ((flowBottom ?: frame.margin) + FLOW_GAP * frame.unit)
        chips(chipTop)
        return OverlayScene(input.width, input.height, ops, bounds)
    }

    private fun OverlayWidget.isArc() =
        kind == OverlayWidgetKind.ARC || kind == OverlayWidgetKind.ARC_LEFT || kind == OverlayWidgetKind.ARC_RIGHT

    private fun OverlayWidget.reading() = overlayReading(metric, input.snapshot, input.trip)

    private fun resolve(widget: OverlayWidget): PixelBox? {
        val placement = widget.placement ?: return legacyArc(widget)
        val box = (if (frame.landscape) placement.landscape else placement.portrait) ?: return null
        val k = frame.scaledUnit
        val w = box.width * k
        val h = box.height * k
        val x = when (box.anchor.horizontal) {
            -1 -> box.dx * k
            1 -> width - box.dx * k - w
            else -> width / 2 + box.dx * k - w / 2
        }
        val y = when (box.anchor.vertical) {
            -1 -> box.dy * k
            1 -> height - box.dy * k - h
            else -> height / 2 + box.dy * k - h / 2
        }
        return PixelBox(x, y, x + w, y + h, box.valueSize * k, box.unitSize * k, box.labelSize * k)
    }

    /** The percent position saved presets use. Unlike the first port it never paints a card behind the dial. */
    private fun legacyArc(widget: OverlayWidget): PixelBox {
        val arc = widget.arc
        val limit = frame.short / 2 - frame.margin
        val radius = (arc.radius * LEGACY_SIZE * frame.unit).coerceIn(1f, limit)
        val x = (width * arc.x / 100f).coerceIn(radius + frame.margin, width - radius - frame.margin)
        val y = (height * arc.y / 100f).coerceIn(radius + frame.margin, height - radius - frame.margin)
        return PixelBox(x - radius, y - radius, x + radius, y + radius, radius * 0.62f, radius * 0.11f, radius * 0.09f)
    }

    /**
     * Studio's row flow, short-edge scaled. **Retraction:** the first port silently dropped a row that did not fit, so
     * a widget vanished from the file with no warning. The whole flow now shrinks until every row fits.
     */
    private fun layoutFlow(widgets: List<OverlayWidget>): Map<String, PixelBox> {
        if (widgets.isEmpty()) return emptyMap()
        val availableW = width - frame.margin * 2
        val hasArc = input.preset.widgets.any { it.placement == null && it.isArc() }
        val availableH = (height - frame.margin * 2) * (if (hasArc) 0.45f else 1f)
        var scale = 1f
        var rows = wrap(widgets, scale, availableW)
        var steps = 0
        while (rows.totalHeight(scale) > availableH && steps++ < FLOW_SHRINK_STEPS) {
            scale *= FLOW_SHRINK
            rows = wrap(widgets, scale, availableW)
        }
        val gap = FLOW_GAP * frame.unit * scale
        val result = linkedMapOf<String, PixelBox>()
        var y = frame.margin
        for (row in rows) {
            val total = row.sumOf { it.second.toDouble() }.toFloat() + gap * (row.size - 1)
            var x = if (input.preset.centerCards) (width - total) / 2 else frame.margin
            val rowHeight = row.maxOf { it.third }
            for ((widget, w, h) in row) {
                val value = widget.flowValue(scale)
                result[widget.id] = PixelBox(x, y, x + w, y + h, value, value * 0.47f, value * 0.375f)
                x += w + gap
            }
            y += rowHeight + gap
        }
        return result
    }

    private fun List<List<Triple<OverlayWidget, Float, Float>>>.totalHeight(scale: Float): Float =
        sumOf { row -> row.maxOf { it.third }.toDouble() }.toFloat() + FLOW_GAP * frame.unit * scale * (size - 1)

    private fun OverlayWidget.flowValue(scale: Float) = style.fontSize * LEGACY_SIZE * frame.unit * scale

    private fun wrap(widgets: List<OverlayWidget>, scale: Float, available: Float): List<List<Triple<OverlayWidget, Float, Float>>> {
        val gap = FLOW_GAP * frame.unit * scale
        val rows = mutableListOf<MutableList<Triple<OverlayWidget, Float, Float>>>()
        var used = 0f
        for (widget in widgets) {
            val value = widget.flowValue(scale)
            val f = value / 64f
            val unit = input.strings.unit(widget.metric, input.system)
            val valueWidth = measure.width(videoValueExemplar(widget.metric), value, true) +
                (if (unit.isEmpty()) 0f else 8f * f + measure.width(unit, value * 0.47f, false))
            val labelWidth = measure.width(label(widget), value * 0.375f, true) * (1 + LABEL_TRACKING)
            val iconWidth = if (widget.kind == OverlayWidgetKind.ICON_VALUE) value + 8f * f else 0f
            val w = (maxOf(valueWidth + iconWidth, labelWidth) + CARD_PADDING * 2 * f).coerceAtMost(available)
            val h = FLOW_CARD_HEIGHT * f
            if (rows.isEmpty() || used + gap + w > available) {
                rows += mutableListOf<Triple<OverlayWidget, Float, Float>>()
                used = 0f
            } else {
                used += gap
            }
            rows.last() += Triple(widget, w, h)
            used += w
        }
        return rows
    }

    private fun label(widget: OverlayWidget): String {
        val text = overlayLabel(widget, input.strings)
        return if (isAutoOverlayLabel(widget)) text.uppercase() else text
    }

    private fun draw(widget: OverlayWidget, box: PixelBox) = when (widget.kind) {
        OverlayWidgetKind.VALUE_CARD, OverlayWidgetKind.ICON_VALUE -> card(widget, box)
        OverlayWidgetKind.ARC -> fullDial(widget, box)
        OverlayWidgetKind.ARC_LEFT -> quarterDial(widget, box, left = true)
        OverlayWidgetKind.ARC_RIGHT -> quarterDial(widget, box, left = false)
        OverlayWidgetKind.BAR -> bar(widget, box)
        OverlayWidgetKind.TEXT -> text(widget, box)
    }

    /** The colours of a widget right now: a fired rule takes over the value and the accent. */
    private inner class Look(widget: OverlayWidget, reading: VideoReading?) {
        val style = overlayStyle(widget, reading)
        private val fired = overlayRuleFired(widget, reading)
        val foreground = overlayColorArgb(style.foreground)
        val background = overlayColorArgb(style.background)
        val accent = overlayColorArgb(if (fired) style.foreground else widget.style.accent ?: style.foreground)
        val unit = if (widget.style.accent != null) UNIT_COLOR else foreground
    }

    /** Scale for the value+unit pair, from the metric's widest text, so that it never changes while the number does. */
    private fun fit(metric: VideoMetric, unit: String, value: Float, unitSize: Float, available: Float, gap: Float): Float {
        val needed = measure.width(videoValueExemplar(metric), value, true) +
            (if (unit.isEmpty()) 0f else gap + measure.width(unit, unitSize, false))
        return if (needed <= available || needed <= 0f) 1f else (available / needed).coerceAtLeast(MIN_FIT)
    }

    /** Value and unit runs: `—` carries no unit, and the `~` of an estimate is smaller and orange. */
    private fun valueRuns(text: String, unit: String, value: Float, unitSize: Float, color: Int, unitColor: Int, gap: Float): List<OverlayRun> {
        if (text == NO_VALUE) return listOf(OverlayRun(text, value, color))
        val runs = mutableListOf<OverlayRun>()
        if (text.startsWith("~")) {
            runs += OverlayRun("~", value * 0.66f, ORANGE_COLOR)
            runs += OverlayRun(text.drop(1), value, color)
        } else {
            runs += OverlayRun(text, value, color)
        }
        if (unit.isNotEmpty()) runs += OverlayRun(unit, unitSize, unitColor, bold = false, gapBefore = gap)
        return runs
    }

    private fun card(widget: OverlayWidget, box: PixelBox) {
        val reading = widget.reading()
        val look = Look(widget, reading)
        val f = box.value / 64f
        val radius = (widget.style.cornerRadius * f).coerceAtMost(box.height / 2)
        ops += OverlayRectOp(
            box.left, box.top, box.right, box.bottom, radius,
            fill = look.background, stroke = STROKE_COLOR, strokeWidth = 2f * f,
        )
        val padding = CARD_PADDING * f
        val inner = box.width - padding * 2
        val label = label(widget)
        val labelSize = fitLabel(label, box.label, inner)
        ops += OverlayTextOp(
            listOf(OverlayRun(label, labelSize, look.accent)),
            box.left + padding, box.top + box.label * 1.83f, trackingEm = LABEL_TRACKING,
        )
        val iconed = widget.kind == OverlayWidgetKind.ICON_VALUE
        val iconSpace = if (iconed) box.value * 0.8f + 8f * f else 0f
        val unit = input.strings.unit(widget.metric, input.system)
        val scale = fit(widget.metric, unit, box.value, box.unit, inner - iconSpace, 8f * f)
        val baseline = box.bottom - 30f * f
        if (iconed) {
            val size = box.value * 0.8f
            ops += OverlayIconOp(overlayIconShown(widget, reading), box.left + padding, baseline - size * 0.9f, size, look.accent)
        }
        val text = videoValueText(widget.metric, reading, input.system)
        ops += OverlayTextOp(
            valueRuns(text, unit, box.value * scale, box.unit * scale, look.foreground, look.unit, 8f * f),
            box.left + padding + iconSpace, baseline,
        )
    }

    private fun fitLabel(text: String, size: Float, available: Float): Float {
        val needed = measure.width(text, size, true) * (1 + LABEL_TRACKING)
        return if (needed <= available || needed <= 0f) size else (size * available / needed).coerceAtLeast(size * 0.7f)
    }

    private fun gauge(widget: OverlayWidget, reading: VideoReading?, cx: Float, cy: Float, radius: Float, start: Float, sweep: Float, stroke: Float, color: Int) {
        ops += OverlayDiscOp(cx, cy, radius * 1.12f)
        val under = 12f * frame.scaledUnit
        ops += OverlayArcOp(cx, cy, radius, start, sweep, stroke + under, UNDER_COLOR)
        ops += OverlayArcOp(cx, cy, radius, start, sweep, stroke * 0.55f, TRACK_COLOR)
        val fraction = overlayFraction(widget, reading, input.system, input.scales, input.dials) ?: return
        if (fraction > 0.0) ops += OverlayArcOp(cx, cy, radius, start, sweep * fraction.toFloat(), stroke, color)
    }

    private fun quarterDial(widget: OverlayWidget, box: PixelBox, left: Boolean) {
        val reading = widget.reading()
        val look = Look(widget, reading)
        val radius = box.width / 2
        val stroke = widget.arc.thickness * radius / 226f
        gauge(
            widget, reading, box.cx, box.cy, radius,
            if (left) LEFT_START else RIGHT_START, if (left) -QUARTER_SWEEP else QUARTER_SWEEP, stroke, look.accent,
        )
        val f = box.value / 150f
        val x = box.cx + (if (left) 22f else -22f) * f
        val unit = input.strings.unit(widget.metric, input.system)
        val text = videoValueText(widget.metric, reading, input.system)
        val scale = fit(widget.metric, "", box.value, 0f, radius * 1.7f, 0f)
        ops += OverlayTextOp(listOf(OverlayRun(label(widget), box.label, look.accent)), x, box.cy - 84f * f, OverlayTextAlign.CENTER, box.label * 0.35f, LABEL_TRACKING)
        ops += OverlayTextOp(valueRuns(text, "", box.value * scale, 0f, look.foreground, look.unit, 0f), x, box.cy + 50f * f, OverlayTextAlign.CENTER, 14f * f)
        if (text != NO_VALUE && unit.isNotEmpty()) {
            ops += OverlayTextOp(listOf(OverlayRun(unit, box.unit, UNIT_COLOR, bold = false)), x, box.cy + 98f * f, OverlayTextAlign.CENTER, 5f * f)
        }
    }

    private fun fullDial(widget: OverlayWidget, box: PixelBox) {
        val reading = widget.reading()
        val look = Look(widget, reading)
        val radius = box.width / 2
        val stroke = widget.arc.thickness * (if (widget.placement != null) frame.scaledUnit else frame.unit * LEGACY_SIZE)
        gauge(widget, reading, box.cx, box.cy, radius, FULL_START, FULL_SWEEP, stroke, look.accent)
        if (widget.arc.ticks) ticks(widget, box, radius)
        val vs = box.value
        val unit = input.strings.unit(widget.metric, input.system)
        val text = videoValueText(widget.metric, reading, input.system)
        val scale = fit(widget.metric, "", vs, 0f, radius * 1.3f, 0f)
        val k = vs / 184f
        ops += OverlayTextOp(listOf(OverlayRun(label(widget), box.label, look.accent)), box.cx, box.cy - vs * 0.62f, OverlayTextAlign.CENTER, box.label * 0.35f, LABEL_TRACKING)
        ops += OverlayTextOp(valueRuns(text, "", vs * scale, 0f, look.foreground, look.unit, 0f), box.cx, box.cy + vs * 0.34f, OverlayTextAlign.CENTER, 13f * k)
        if (text != NO_VALUE && unit.isNotEmpty()) {
            ops += OverlayTextOp(listOf(OverlayRun(unit, box.unit, UNIT_COLOR, bold = false)), box.cx, box.cy + vs * 0.34f + box.unit * 1.3f, OverlayTextAlign.CENTER, 5f * frame.scaledUnit)
        }
    }

    private fun ticks(widget: OverlayWidget, box: PixelBox, radius: Float) {
        val max = overlayScaleMax(widget, input.scales)
        val step = overlayTickStep(max)
        val size = 28f * frame.scaledUnit
        val tickRadius = radius - 58f * frame.scaledUnit
        var value = widget.arc.min.toInt()
        while (value <= max.toInt()) {
            val angle = (FULL_START + FULL_SWEEP * (value - widget.arc.min) / (max - widget.arc.min)) * PI.toFloat() / 180f
            ops += OverlayTextOp(
                listOf(OverlayRun(value.toString(), size, UNIT_COLOR, bold = false)),
                box.cx + tickRadius * cos(angle), box.cy + tickRadius * sin(angle) + size * 0.36f,
                OverlayTextAlign.CENTER, 5f * frame.scaledUnit,
            )
            value += step
        }
    }

    private fun bar(widget: OverlayWidget, box: PixelBox) {
        val reading = widget.reading()
        val look = Look(widget, reading)
        val spec = widget.bar
        val unit = input.strings.unit(widget.metric, input.system)
        val text = videoValueText(widget.metric, reading, input.system)
        if (spec.valueBelow) {
            val f = box.value / 60f
            drawBar(widget, reading, box.left, box.top, box.right, box.top + spec.height * f, f, look.accent)
            spec.secondary?.let { metric ->
                val secondary = videoValueText(metric, overlayReading(metric, input.snapshot, input.trip), input.system)
                ops += OverlayTextOp(
                    valueRuns(secondary, input.strings.unit(metric, input.system), 54f * f, box.unit, TEXT_COLOR, UNIT_COLOR, 8f * f),
                    box.left, box.top + 82f * f, haloWidth = 7f * f,
                )
            }
            ops += OverlayTextOp(
                valueRuns(text, unit, box.value, box.unit, look.accent, look.accent, 8f * f),
                box.right, box.top + 82f * f, OverlayTextAlign.END, 7f * f,
            )
        } else {
            val f = box.value / 110f
            ops += OverlayTextOp(listOf(OverlayRun(label(widget), box.label, look.accent)), box.left, box.bottom - 104f * f, haloWidth = box.label * 0.3f, trackingEm = LABEL_TRACKING)
            ops += OverlayTextOp(
                valueRuns(text, unit, box.value, box.unit, look.foreground, UNIT_COLOR, 8f * f),
                box.right, box.bottom - 54f * f, OverlayTextAlign.END, 10f * f,
            )
            drawBar(widget, reading, box.left, box.bottom - spec.height * f, box.right, box.bottom, f, look.accent)
        }
    }

    private fun drawBar(widget: OverlayWidget, reading: VideoReading?, left: Float, top: Float, right: Float, bottom: Float, f: Float, color: Int) {
        val height = bottom - top
        val inset = height * 0.13f
        ops += OverlayRectOp(left, top, right, bottom, height / 2, BAR_TRACK_COLOR, BAR_STROKE_COLOR, 2f * f)
        val fraction = overlayFraction(widget, reading, input.system, input.scales, input.dials) ?: 0.0
        if (fraction > 0.0) {
            val fillRight = left + inset + (right - left - inset * 2) * fraction.toFloat()
            ops += OverlayRectOp(left + inset, top + inset, fillRight, bottom - inset, (height - inset * 2) / 2, color)
        }
        for (index in 1 until widget.bar.segments) {
            val x = left + (right - left) * index / widget.bar.segments
            ops += OverlayRectOp(x - f, top + inset * 0.6f, x + f, bottom - inset * 0.6f, 0f, BAR_TICK_COLOR)
        }
    }

    private fun text(widget: OverlayWidget, box: PixelBox) {
        val reading = widget.reading()
        val look = Look(widget, reading)
        val unit = input.strings.unit(widget.metric, input.system)
        val gap = box.unit * 0.27f
        val scale = fit(widget.metric, unit, box.value, box.unit, box.width, gap)
        val text = videoValueText(widget.metric, reading, input.system)
        val baseline = box.top + box.value * 0.79f
        ops += OverlayTextOp(
            valueRuns(text, unit, box.value * scale, box.unit * scale, look.foreground, UNIT_COLOR, gap),
            box.left, baseline, haloWidth = maxOf(5f * frame.scaledUnit, box.value * 0.07f),
        )
        if (box.label > 0f) {
            ops += OverlayTextOp(listOf(OverlayRun(label(widget), box.label, UNIT_COLOR)), box.left, baseline + box.label * 2f, haloWidth = box.label * 0.3f, trackingEm = LABEL_TRACKING)
        }
    }

    /** Burned-in state chips, stacked and centred: what a viewer needs to read a stretch of dashes correctly. */
    private fun chips(top: Float) {
        var y = top
        if (overlayNoLink(input.snapshot)) {
            chip(input.strings.noLink, y, PINK_COLOR, ON_PINK_COLOR)
            y += (CHIP_HEIGHT + CHIP_GAP) * frame.unit
        }
        overlayWarning(input.preset, input.snapshot)?.let { widget ->
            val reading = widget.reading()
            val unit = input.strings.unit(widget.metric, input.system)
            chip("${label(widget)} ${videoValueText(widget.metric, reading, input.system)} $unit".trim(), y, ORANGE_COLOR, ON_ORANGE_COLOR)
            y += (CHIP_HEIGHT + CHIP_GAP) * frame.unit
        }
        if (input.snapshot?.demo == true) chip(input.strings.demo, y, CYAN_COLOR, ON_CYAN_COLOR)
    }

    private fun chip(text: String, top: Float, fill: Int, onFill: Int) {
        val u = frame.unit
        val size = CHIP_FONT * u
        val w = measure.width(text, size, true) * (1 + CHIP_TRACKING) + CHIP_SIDE_PADDING * 2 * u
        val h = CHIP_HEIGHT * u
        val left = width / 2 - w / 2
        ops += OverlayRectOp(left, top, left + w, top + h, h / 2, fill)
        ops += OverlayTextOp(listOf(OverlayRun(text, size, onFill)), width / 2, top + h * 0.68f, OverlayTextAlign.CENTER, 0f, CHIP_TRACKING)
    }
}

private val TEXT_COLOR = OverlayPalette.TEXT.toInt()
private val UNIT_COLOR = OverlayPalette.UNIT.toInt()
private val ORANGE_COLOR = OverlayPalette.ORANGE.toInt()
private val PINK_COLOR = OverlayPalette.PINK.toInt()
private val CYAN_COLOR = OverlayPalette.CYAN.toInt()
private val ON_ORANGE_COLOR = OverlayPalette.ON_ORANGE.toInt()
private val ON_PINK_COLOR = OverlayPalette.ON_PINK.toInt()
private val ON_CYAN_COLOR = OverlayPalette.ON_CYAN.toInt()
private val STROKE_COLOR = OverlayPalette.CARD_STROKE.toInt()
private val UNDER_COLOR = OverlayPalette.GAUGE_UNDER.toInt()
private val TRACK_COLOR = OverlayPalette.GAUGE_TRACK.toInt()
private val BAR_TRACK_COLOR = OverlayPalette.BAR_TRACK.toInt()
private val BAR_STROKE_COLOR = OverlayPalette.BAR_STROKE.toInt()
private val BAR_TICK_COLOR = OverlayPalette.BAR_TICK.toInt()

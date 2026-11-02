package ru.sodovaya.volty.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import androidx.camera.effects.Frame
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.util.UnitSystem
import kotlin.math.min

/** One renderer for CameraX preview AND encoded video; UI controls never enter the output. */
internal class VideoOverlayRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private var strings = VideoOverlayStrings()
    private var system = UnitSystem.METRIC

    fun draw(frame: Frame, canvas: Canvas, preset: OverlayPreset, snapshot: VideoSnapshot?, strings: VideoOverlayStrings, system: UnitSystem) {
        this.strings = strings
        this.system = system
        textPaint.textAlign = Paint.Align.LEFT
        paint.clearShadowLayer()
        clear(canvas)
        val crop = frame.cropRect
        if (crop.isEmpty) return
        val rotated = frame.rotationDegrees % 180 != 0
        val width = (if (rotated) crop.height() else crop.width()).toFloat()
        val height = (if (rotated) crop.width() else crop.height()).toFloat()
        canvas.save()
        canvas.clipRect(crop)
        canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        when (frame.rotationDegrees) {
            90 -> { canvas.translate(0f, crop.height().toFloat()); canvas.rotate(-90f) }
            180 -> { canvas.translate(crop.width().toFloat(), crop.height().toFloat()); canvas.rotate(-180f) }
            270 -> { canvas.translate(crop.width().toFloat(), 0f); canvas.rotate(-270f) }
        }
        // Invert the output's mirror so text remains readable on the front camera too.
        if (frame.isMirroring) { canvas.translate(width, 0f); canvas.scale(-1f, 1f) }
        val scale = width / DESIGN_WIDTH
        canvas.scale(scale, scale)
        val designHeight = height / scale
        drawCards(canvas, preset, snapshot, designHeight)
        preset.widgets.filter { it.kind == OverlayWidgetKind.ARC }.forEach { widget ->
            drawArc(canvas, widget, snapshot?.readings?.get(widget.metric), designHeight)
        }
        if (snapshot?.demo == true) {
            textPaint.textSize = 26f
            textPaint.color = Color.YELLOW
            canvas.drawText(strings.demo, 24f, designHeight - 24f, textPaint)
        }
        canvas.restore()
    }

    /** Wipes the overlay surface, e.g. after a failed draw left it half painted. */
    fun clear(canvas: Canvas) = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    private data class Card(val widget: OverlayWidget, val reading: VideoReading?, val style: OverlayStyle,
        val text: String, val width: Float, val height: Float)

    private fun drawCards(canvas: Canvas, preset: OverlayPreset, snapshot: VideoSnapshot?, height: Float) {
        val margin = 24f
        val gap = 10f
        val rows = mutableListOf<MutableList<Card>>()
        var row = mutableListOf<Card>()
        var rowWidth = 0f
        preset.widgets.filter { it.kind != OverlayWidgetKind.ARC }.forEach { widget ->
            val reading = snapshot?.readings?.get(widget.metric)
            val style = overlayStyle(widget, reading)
            val unit = strings.unit(widget.metric, system)
            val text = "${videoValueText(widget.metric, reading, system)} $unit".trim()
            textPaint.textSize = style.fontSize
            val iconSpace = if (widget.kind == OverlayWidgetKind.ICON_VALUE) style.fontSize * 1.4f else 0f
            // Studio reserves a value box before laying out rows. Live data has no future maximum;
            // reserve digits by metric instead so 9 -> 10 or unknown -> measured never shifts a row.
            val exemplar = videoValueExemplar(widget.metric) + " " + unit
            val valueWidth = textPaint.measureText(exemplar) + iconSpace
            textPaint.textSize = style.fontSize * .55f
            val labelWidth = if (widget.kind == OverlayWidgetKind.VALUE_CARD) textPaint.measureText(overlayLabel(widget, strings)) else 0f
            val w = min(DESIGN_WIDTH - margin * 2, maxOf(valueWidth, labelWidth) + style.padding * 2)
            val labelHeight = if (widget.kind == OverlayWidgetKind.VALUE_CARD) style.fontSize * .75f else 0f
            val h = style.fontSize * 1.4f + style.padding * 2 + labelHeight
            if (row.isNotEmpty() && rowWidth + gap + w > DESIGN_WIDTH - margin * 2) {
                rows += row; row = mutableListOf(); rowWidth = 0f
            }
            row += Card(widget, reading, style, text, w, h)
            rowWidth += w + if (row.size > 1) gap else 0f
        }
        if (row.isNotEmpty()) rows += row
        var y = margin
        for (cards in rows) {
            val total = cards.sumOf { it.width.toDouble() }.toFloat() + gap * (cards.size - 1)
            var x = if (preset.centerCards) (DESIGN_WIDTH - total) / 2 else margin
            val rowHeight = cards.maxOf { it.height }
            if (y + rowHeight > height - margin) break
            for (card in cards) {
                background(canvas, RectF(x, y, x + card.width, y + card.height), card.style)
                textPaint.textSize = card.style.fontSize
                textPaint.color = Color.parseColor(card.style.foreground)
                val labelHeight = if (card.widget.kind == OverlayWidgetKind.VALUE_CARD) card.style.fontSize * .75f else 0f
                val baseline = y + labelHeight + (card.height - labelHeight - textPaint.fontMetrics.ascent - textPaint.fontMetrics.descent) / 2
                canvas.save()
                canvas.clipRect(x, y, x + card.width, y + card.height)
                val iconSpace = if (card.widget.kind == OverlayWidgetKind.ICON_VALUE) {
                    drawIcon(canvas, card.widget, x + card.style.padding, y + (card.height - card.style.fontSize) / 2,
                        card.style.fontSize, card.style.foreground)
                    card.style.fontSize * 1.4f
                } else 0f
                val contentWidth = textPaint.measureText(card.text) + iconSpace
                val textX = if (iconSpace > 0) x + card.style.padding + iconSpace else x + (card.width - contentWidth) / 2
                canvas.drawText(card.text, textX, baseline, textPaint)
                if (labelHeight > 0) {
                    textPaint.textSize = card.style.fontSize * .55f
                    textPaint.alpha = (textPaint.alpha * .8f).toInt()
                    val label = overlayLabel(card.widget, strings)
                    canvas.drawText(label, x + (card.width - textPaint.measureText(label)) / 2,
                        y + card.style.padding - textPaint.fontMetrics.ascent, textPaint)
                }
                canvas.restore()
                x += card.width + gap
            }
            y += rowHeight + gap
        }
    }

    private fun background(canvas: Canvas, bounds: RectF, style: OverlayStyle) {
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor(style.background)
        if (style.shadow) paint.setShadowLayer(5f, 0f, 2f, 0x66000000) else paint.clearShadowLayer()
        canvas.drawRoundRect(bounds, style.cornerRadius, style.cornerRadius, paint)
        paint.clearShadowLayer()
    }

    private fun drawArc(canvas: Canvas, widget: OverlayWidget, reading: VideoReading?, height: Float) {
        val arc = widget.arc
        val style = overlayStyle(widget, reading)
        val radius = min(arc.radius, min(DESIGN_WIDTH, height) / 2 - 24f).coerceAtLeast(1f)
        val x = (DESIGN_WIDTH * arc.x / 100f).coerceIn(radius + 12, DESIGN_WIDTH - radius - 12)
        val y = (height * arc.y / 100f).coerceIn(radius + 12, height - radius - 12)
        val bounds = RectF(x - radius, y - radius, x + radius, y + radius)
        background(canvas, bounds, style)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arc.thickness
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.parseColor(style.foreground).let { (it and 0x00ffffff) or 0x33000000 }
        canvas.drawArc(bounds, 135f, 270f, false, paint)
        val value = reading?.value?.takeIf { it.isFinite() }
        if (value != null) {
            paint.color = Color.parseColor(style.foreground)
            val fraction = ((value - arc.min) / (arc.max - arc.min)).coerceIn(0.0, 1.0).toFloat()
            canvas.drawArc(bounds, 135f, 270f * fraction, false, paint)
        }
        paint.style = Paint.Style.FILL
        textPaint.color = Color.parseColor(style.foreground)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = style.fontSize * 2
        canvas.drawText(videoValueText(widget.metric, reading, system), x, y + style.fontSize / 2, textPaint)
        textPaint.textSize = style.fontSize * .65f
        canvas.drawText(overlayLabel(widget, strings) + " · " + strings.unit(widget.metric, system), x, y + style.fontSize * 1.7f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawIcon(canvas: Canvas, widget: OverlayWidget, x: Float, y: Float, size: Float, color: String) {
        val icon = if (widget.icon != OverlayIcon.AUTO) widget.icon else autoOverlayIcon(widget.metric)
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(size / 24f, size / 24f)
        paint.clearShadowLayer()
        paint.color = Color.parseColor(color)
        paint.strokeWidth = 2f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (icon) {
            OverlayIcon.BATTERY -> {
                canvas.drawRoundRect(RectF(2f, 6f, 20f, 18f), 2f, 2f, paint)
                canvas.drawLine(23f, 10f, 23f, 14f, paint)
                canvas.drawLine(6f, 12f, 16f, 12f, paint)
            }
            OverlayIcon.BOLT -> {
                val path = Path().apply { moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(10f, 22f); lineTo(21f, 9f); lineTo(13f, 9f); close() }
                canvas.drawPath(path, paint)
            }
            OverlayIcon.TEMPERATURE -> {
                canvas.drawRoundRect(RectF(9f, 2f, 15f, 17f), 3f, 3f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(12f, 18f, 5f, paint)
                canvas.drawRect(11f, 8f, 13f, 18f, paint)
            }
            OverlayIcon.WARNING -> {
                val path = Path().apply { moveTo(12f, 2f); lineTo(23f, 22f); lineTo(1f, 22f); close() }
                canvas.drawPath(path, paint)
                canvas.drawLine(12f, 8f, 12f, 14f, paint)
                canvas.drawPoint(12f, 18f, paint)
            }
            OverlayIcon.CURRENT -> {
                val path = Path().apply { moveTo(1f, 12f); cubicTo(7f, -4f, 7f, 28f, 13f, 12f); cubicTo(19f, -4f, 19f, 28f, 23f, 12f) }
                canvas.drawPath(path, paint)
            }
            else -> {
                canvas.drawArc(RectF(2f, 3f, 22f, 23f), 140f, 260f, false, paint)
                canvas.drawLine(12f, 13f, 18f, 7f, paint)
                canvas.drawCircle(12f, 13f, 1f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        canvas.restore()
    }

    companion object { private const val DESIGN_WIDTH = 1920f }
}

package ru.sodovaya.volty.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import ru.sodovaya.volty.domain.video.OverlayArcOp
import ru.sodovaya.volty.domain.video.OverlayDiscOp
import ru.sodovaya.volty.domain.video.OverlayIcons
import ru.sodovaya.volty.domain.video.OverlayIconOp
import ru.sodovaya.volty.domain.video.OverlayOp
import ru.sodovaya.volty.domain.video.OverlayPalette
import ru.sodovaya.volty.domain.video.OverlayRectOp
import ru.sodovaya.volty.domain.video.OverlayRun
import ru.sodovaya.volty.domain.video.OverlayScene
import ru.sodovaya.volty.domain.video.OverlayScrimOp
import ru.sodovaya.volty.domain.video.OverlayTextAlign
import ru.sodovaya.volty.domain.video.OverlayTextMeasure
import ru.sodovaya.volty.domain.video.OverlayTextOp

/**
 * The bundled faces, so a video looks the same on every OEM's `sans-serif`. Roboto's digits are tabular by default,
 * which is what keeps a changing number from shimmering. A failed load falls back rather than ending a recording.
 */
internal class OverlayFonts(context: Context) {
    val bold: Typeface = load(context, "fonts/Roboto-Bold.ttf", Typeface.DEFAULT_BOLD)
    val medium: Typeface = load(context, "fonts/Roboto-Medium.ttf", Typeface.DEFAULT)

    /** Font Awesome Free 6.7.2 Solid, the glyphs telemetry-studio draws its icons from (OFL 1.1). Null: no icons. */
    val icons: Typeface? = runCatching { Typeface.createFromAsset(context.assets, ICON_FONT) }.getOrNull()

    companion object {
        const val ICON_FONT = "fonts/fa-solid-900.ttf"
    }

    private fun load(context: Context, path: String, fallback: Typeface): Typeface =
        runCatching { Typeface.createFromAsset(context.assets, path) }.getOrDefault(fallback)
}

/**
 * Replays an [OverlayScene] on any Canvas: the same code for the CameraX overlay surface (preview and file) and for
 * the editor's stage later on. It decides nothing; everything decided is in `buildOverlayScene` and tested there.
 * Not thread-safe: one instance per drawing thread.
 */
internal class OverlayCanvasDrawer(private val fonts: OverlayFonts) : OverlayTextMeasure {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val glyphBounds = android.graphics.Rect()

    override fun width(text: String, size: Float, bold: Boolean): Float {
        this.text.letterSpacing = 0f
        apply(size, bold)
        return this.text.measureText(text)
    }

    private fun apply(size: Float, bold: Boolean) {
        text.textSize = size
        text.typeface = if (bold) fonts.bold else fonts.medium
    }

    fun draw(canvas: Canvas, scene: OverlayScene) = scene.ops.forEach { draw(canvas, it) }

    private fun draw(canvas: Canvas, op: OverlayOp) {
        when (op) {
            is OverlayScrimOp -> scrim(canvas, op)
            is OverlayDiscOp -> disc(canvas, op)
            is OverlayArcOp -> {
                stroke.color = op.color
                stroke.strokeWidth = op.strokeWidth
                rect.set(op.cx - op.radius, op.cy - op.radius, op.cx + op.radius, op.cy + op.radius)
                canvas.drawArc(rect, op.startDegrees, op.sweepDegrees, false, stroke)
            }
            is OverlayRectOp -> {
                rect.set(op.left, op.top, op.right, op.bottom)
                op.fill?.let { fill.color = it; canvas.drawRoundRect(rect, op.radius, op.radius, fill) }
                op.stroke?.let {
                    stroke.color = it
                    stroke.strokeWidth = op.strokeWidth
                    canvas.drawRoundRect(rect, op.radius, op.radius, stroke)
                }
            }
            is OverlayTextOp -> text(canvas, op)
            is OverlayIconOp -> icon(canvas, op)
        }
    }

    private fun scrim(canvas: Canvas, op: OverlayScrimOp) {
        val solid = OverlayPalette.SCRIM.toInt()
        val colors = intArrayOf(withAlpha(solid, 0f), withAlpha(solid, 0.46f), withAlpha(solid, if (op.darkAtTop) 0.78f else 0.82f))
        val positions = floatArrayOf(0f, if (op.darkAtTop) 0.6f else 0.55f, 1f)
        val (from, to) = if (op.darkAtTop) op.bottom to op.top else op.top to op.bottom
        fill.shader = LinearGradient(0f, from, 0f, to, colors, positions, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, op.top, canvas.width.toFloat().coerceAtLeast(canvas.height.toFloat()) * 2, op.bottom, fill)
        fill.shader = null
    }

    private fun disc(canvas: Canvas, op: OverlayDiscOp) {
        val solid = OverlayPalette.SCRIM.toInt()
        fill.shader = RadialGradient(
            op.cx, op.cy, op.radius,
            intArrayOf(withAlpha(solid, 0.36f), withAlpha(solid, 0.36f), withAlpha(solid, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(op.cx, op.cy, op.radius, fill)
        fill.shader = null
    }

    private fun withAlpha(color: Int, alpha: Float): Int = Color.argb((alpha * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    private fun text(canvas: Canvas, op: OverlayTextOp) {
        text.letterSpacing = op.trackingEm
        val total = op.runs.sumOf { run -> (run.gapBefore + run.measured()).toDouble() }.toFloat()
        var x = when (op.align) {
            OverlayTextAlign.START -> op.x
            OverlayTextAlign.CENTER -> op.x - total / 2
            OverlayTextAlign.END -> op.x - total
        }
        // The halo goes first and separately, so a neighbouring run's halo never eats a glyph.
        if (op.haloWidth > 0f) {
            text.style = Paint.Style.STROKE
            text.strokeJoin = Paint.Join.ROUND
            text.strokeWidth = op.haloWidth
            text.color = OverlayPalette.HALO.toInt()
            var haloX = x
            for (run in op.runs) {
                haloX += run.gapBefore
                apply(run.size, run.bold)
                canvas.drawText(run.text, haloX, op.baseline, text)
                haloX += text.measureText(run.text)
            }
            text.style = Paint.Style.FILL
        }
        for (run in op.runs) {
            x += run.gapBefore
            apply(run.size, run.bold)
            text.color = run.color
            canvas.drawText(run.text, x, op.baseline, text)
            x += text.measureText(run.text)
        }
        text.letterSpacing = 0f
    }

    private fun OverlayRun.measured(): Float {
        apply(size, bold)
        return this@OverlayCanvasDrawer.text.measureText(text)
    }

    /** One glyph, centred on its ink in the op's square: FA glyphs differ in width, so the box, not the advance, aligns. */
    private fun icon(canvas: Canvas, op: OverlayIconOp) {
        val face = fonts.icons ?: return
        val info = OverlayIcons.byName(op.icon) ?: return
        val text = info.codepoint.toString()
        glyph.typeface = face
        glyph.textSize = op.size * ICON_EM
        glyph.color = op.color
        glyph.getTextBounds(text, 0, text.length, glyphBounds)
        if (glyphBounds.isEmpty) return
        val x = op.x + (op.size - glyphBounds.width()) / 2f - glyphBounds.left
        val y = op.y + (op.size - glyphBounds.height()) / 2f - glyphBounds.top
        canvas.drawText(text, x, y, glyph)
    }

    private companion object {
        /** The glyph's em is the op's size, as TS sizes its icon font; the ink is then centred in that square. */
        const val ICON_EM = 1.0f
    }
}

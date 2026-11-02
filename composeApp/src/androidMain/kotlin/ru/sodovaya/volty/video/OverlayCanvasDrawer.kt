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
import androidx.core.graphics.PathParser
import ru.sodovaya.volty.domain.video.OverlayArcOp
import ru.sodovaya.volty.domain.video.OverlayDiscOp
import ru.sodovaya.volty.domain.video.OverlayIcon
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
    private val iconPaths = HashMap<OverlayIcon, Path>()
    private val iconMatrix = android.graphics.Matrix()
    private val iconScratch = Path()

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

    private fun icon(canvas: Canvas, op: OverlayIconOp) {
        val path = iconPaths.getOrPut(op.icon) { PathParser.createPathFromPathData(ICON_PATHS.getValue(op.icon)) }
        iconMatrix.setScale(op.size / 24f, op.size / 24f)
        iconMatrix.postTranslate(op.x, op.y)
        path.transform(iconMatrix, iconScratch)
        fill.color = op.color
        canvas.drawPath(iconScratch, fill)
    }

    private companion object {
        /** Material Icons, 24-unit solid paths. The hand-stroked sketches they replace went to 0.9 px in 720p. */
        val ICON_PATHS: Map<OverlayIcon, String> = mapOf(
            OverlayIcon.AUTO to "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z",
            OverlayIcon.BATTERY to "M15.67 4H14V2h-4v2H8.33C7.6 4 7 4.6 7 5.33v15.33C7 21.4 7.6 22 8.33 22h7.33c.74 0 1.34-.6 1.34-1.33V5.33C17 4.6 16.4 4 15.67 4z",
            OverlayIcon.BOLT to "M7 2v11h3v9l7-12h-4l4-8z",
            OverlayIcon.TEMPERATURE to "M15 13V5c0-1.66-1.34-3-3-3S9 3.34 9 5v8c-1.21.91-2 2.37-2 4 0 2.76 2.24 5 5 5s5-2.24 5-5c0-1.63-.79-3.09-2-4zm-4-8c0-.55.45-1 1-1s1 .45 1 1h-1v1h1v2h-1v1h1v2h-2V5z",
            OverlayIcon.GAUGE to "M20.38 8.57l-1.23 1.85a8 8 0 0 1-.22 7.58H5.07A8 8 0 0 1 15.58 6.85l1.85-1.23A10 10 0 0 0 3.35 19a2 2 0 0 0 1.72 1h13.85a2 2 0 0 0 1.74-1 10 10 0 0 0-.27-10.44zm-9.79 6.84a2 2 0 0 0 2.83 0l5.66-8.49-8.49 5.66a2 2 0 0 0 0 2.83z",
            OverlayIcon.WARNING to "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
            OverlayIcon.CURRENT to "M3 12c2-6 4-6 6 0s4 6 6 0 4-6 6 0h-2c-1-3-2-3-3 0-2 7-5 7-7 0-1-3-2-3-3 0H3z",
        )
    }
}

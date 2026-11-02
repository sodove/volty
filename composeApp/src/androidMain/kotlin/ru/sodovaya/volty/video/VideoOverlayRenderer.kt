package ru.sodovaya.volty.video

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.camera.effects.Frame
import ru.sodovaya.volty.domain.video.OverlayPreset
import ru.sodovaya.volty.domain.video.OverlaySceneInput
import ru.sodovaya.volty.domain.video.OverlayTrip
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoOverlayStrings
import ru.sodovaya.volty.domain.video.VideoSnapshot
import ru.sodovaya.volty.domain.video.buildOverlayScene
import ru.sodovaya.volty.util.UnitSystem

/**
 * One renderer for CameraX preview AND encoded video; UI controls never enter the output. This class only knows the
 * camera's frame geometry; the picture comes from `buildOverlayScene` and [OverlayCanvasDrawer].
 */
internal class VideoOverlayRenderer(context: Context) {
    private val drawer = OverlayCanvasDrawer(OverlayFonts(context))

    fun draw(
        frame: Frame,
        canvas: Canvas,
        preset: OverlayPreset,
        snapshot: VideoSnapshot?,
        strings: VideoOverlayStrings,
        system: UnitSystem,
        trip: OverlayTrip,
        scales: Map<VideoMetric, Float>,
    ) {
        clear(canvas)
        val crop = frame.cropRect
        if (crop.isEmpty) return
        val rotated = frame.rotationDegrees % 180 != 0
        val width = if (rotated) crop.height() else crop.width()
        val height = if (rotated) crop.width() else crop.height()
        canvas.save()
        canvas.clipRect(crop)
        canvas.translate(crop.left.toFloat(), crop.top.toFloat())
        when (frame.rotationDegrees) {
            90 -> { canvas.translate(0f, crop.height().toFloat()); canvas.rotate(-90f) }
            180 -> { canvas.translate(crop.width().toFloat(), crop.height().toFloat()); canvas.rotate(-180f) }
            270 -> { canvas.translate(crop.width().toFloat(), 0f); canvas.rotate(-270f) }
        }
        // Invert the output's mirror so text remains readable on the front camera too.
        if (frame.isMirroring) { canvas.translate(width.toFloat(), 0f); canvas.scale(-1f, 1f) }
        drawer.draw(canvas, buildOverlayScene(OverlaySceneInput(preset, snapshot, strings, system, width, height, trip, scales), drawer))
        canvas.restore()
    }

    /** Wipes the overlay surface, e.g. after a failed draw left it half painted. */
    fun clear(canvas: Canvas) = canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
}

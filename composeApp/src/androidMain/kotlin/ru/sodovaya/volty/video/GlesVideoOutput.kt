package ru.sodovaya.volty.video

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.camera.core.SurfaceOutput
import ru.sodovaya.volty.domain.video.OverlayScene

/** FIFO consumer. Only this thread makes an encoder window current or submits to it. */
internal class GlesVideoOutput(
    private val context: Context,
    private val scene: (Int, Int, Long, Long, Long, Boolean) -> OverlayScene?,
    private val onError: (RuntimeException) -> Unit,
) {
    class Sink(val handle: SurfaceOutput) {
        @Volatile var retired = false
        var window: GlCameraSurface.Window? = null // Encoder thread only.
    }

    data class Frame(
        val texture: Int,
        val transform: FloatArray,
        val timestamp: Long,
        val frameMillis: Long,
        val clockMismatch: Boolean,
        val ready: Long,
    )

    private val thread = HandlerThread("RideVideoEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    private var gl: GlCameraSurface? = null
    private var shapes: GlHudShapes? = null
    private var text: GlHudText? = null
    private var failed = false
    private var active = false
    private var firstPresented = Long.MIN_VALUE
    private var lastPresented = Long.MIN_VALUE
    private var presented = 0

    fun open(sink: Sink, surface: Surface, shared: GlCameraSurface) {
        handler.post {
            if (sink.retired || failed) return@post
            try {
                val renderer = gl ?: GlCameraSurface(shared).also {
                    gl = it
                    shapes = GlHudShapes()
                    text = GlHudText(context)
                }
                val window = renderer.window(surface)
                sink.window = window
                lastPresented = Long.MIN_VALUE
                Log.i(TAG, "Encoder surface ${window.width}x${window.height}")
            } catch (error: RuntimeException) {
                fail(error)
            }
        }
    }

    fun enqueue(sink: Sink, frame: Frame, returned: (unusedReady: Long, reusable: Long) -> Unit) {
        handler.post {
            var ready = frame.ready
            var reusable = 0L
            var returnedEarly = false
            try {
                if (sink.retired || failed) return@post
                val renderer = checkNotNull(gl)
                val window = checkNotNull(sink.window)
                renderer.ensureCurrent()
                renderer.awaitFence(ready)
                ready = 0L
                check(frame.timestamp > lastPresented) { "Camera FIFO attempted to reorder a frame" }
                val begin = SystemClock.elapsedRealtimeNanos()
                renderer.begin(window, frame.texture, frame.transform)
                // The HUD and swap only use the window buffer. Return the camera slot now,
                // with a flushed GPU fence protecting its read from the next camera write.
                reusable = renderer.fence()
                renderer.flush()
                returnedEarly = true
                returned(0L, reusable)
                val started = SystemClock.elapsedRealtimeNanos()
                val overlay = scene(window.width, window.height, frame.timestamp, frame.frameMillis,
                    SystemClock.elapsedRealtime(), frame.clockMismatch)
                val calculated = SystemClock.elapsedRealtimeNanos()
                if (overlay != null) {
                    val shapeRenderer = checkNotNull(shapes)
                    val textRenderer = checkNotNull(text)
                    overlay.ops.forEach { op ->
                        shapeRenderer.draw(op, window.width, window.height)
                        textRenderer.draw(op, window.width, window.height)
                    }
                }
                val drawn = SystemClock.elapsedRealtimeNanos()
                renderer.present(window, frame.timestamp)
                lastPresented = frame.timestamp
                if (active && presented == 0) firstPresented = frame.timestamp
                if (active && (++presented and 7) == 0) {
                    val submitted = SystemClock.elapsedRealtimeNanos()
                    Log.i(TAG, "frame index=$presented timestampNs=${frame.timestamp} " +
                        "camera_us=${(started - begin) / 1000} calc_us=${(calculated - started) / 1000} " +
                        "draw_us=${(drawn - calculated) / 1000} submit_us=${(submitted - drawn) / 1000}")
                }
            } catch (error: RuntimeException) {
                fail(error)
            } finally {
                if (!returnedEarly) returned(ready, reusable)
            }
        }
    }

    fun setRecordingActive(active: Boolean) {
        handler.post {
            this.active = active
            if (active) {
                presented = 0
                firstPresented = Long.MIN_VALUE
            }
        }
    }

    fun flush(tail: Int, admitted: Int, ignored: Int, busyDrops: Int, completed: () -> Unit) {
        if (!handler.post {
                try {
                    Log.i(TAG, "FIFO drained tail=$tail admitted=$admitted presented=$presented ignored=$ignored " +
                        "busyDrops=$busyDrops firstNs=$firstPresented lastNs=$lastPresented")
                } finally {
                    completed()
                }
            }) completed()
    }

    fun retire(sink: Sink) {
        sink.retired = true
        handler.post {
            try {
                sink.window?.let { gl?.destroy(it) }
                sink.window = null
            } catch (error: RuntimeException) {
                fail(error)
            } finally {
                sink.handle.close()
            }
        }
    }

    fun close(releaseCapture: () -> Unit) {
        handler.post {
            try {
                gl?.let {
                    it.makeIdle()
                    shapes?.close()
                    text?.close()
                    it.close()
                }
            } catch (error: RuntimeException) {
                fail(error)
            } finally {
                try {
                    releaseCapture()
                } finally {
                    thread.quitSafely()
                }
            }
        }
    }

    private fun fail(error: RuntimeException) {
        if (failed) return
        failed = true
        onError(error)
    }

    private companion object { const val TAG = "RideGlHud" }
}

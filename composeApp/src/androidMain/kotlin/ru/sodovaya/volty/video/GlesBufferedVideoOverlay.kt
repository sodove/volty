package ru.sodovaya.volty.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.ProcessingException
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.Executor
import ru.sodovaya.volty.domain.video.OverlayScene
import ru.sodovaya.volty.domain.video.VideoFrameClock
import ru.sodovaya.volty.domain.video.videoFrameBufferDepth

/** CameraX owns capture and recording. This processor only owns the GPU FIFO between their surfaces. */
internal class GlesBufferedVideoOverlay(
    private val context: Context,
    private val handler: Handler,
    private val framesPerSecond: Int,
    realtime: Boolean,
    private val onError: (Throwable) -> Unit,
    private val scene: (width: Int, height: Int, timestampNanos: Long, frameMillis: Long, availableAtMillis: Long, clockMismatch: Boolean) -> OverlayScene?,
) : RideVideoOverlay, SurfaceProcessor {
    private class Slot(val target: GlCameraSurface.Target) {
        val transform = FloatArray(16)
        var timestamp = 0L
        var frameMillis = 0L
    }

    private data class Input(val textureId: Int, val texture: SurfaceTexture, val size: Size)
    private data class Output(val handle: SurfaceOutput, val window: GlCameraSurface.Window)

    private val executor = Executor { work ->
        if (Looper.myLooper() == handler.looper) work.run() else handler.post(work)
    }
    override val effect: CameraEffect = object : CameraEffect(CameraEffect.VIDEO_CAPTURE, executor, this, onError) {}

    private val clock = VideoFrameClock(realtime)
    private val captures = linkedMapOf<Long, Long>()
    private val free = ArrayDeque<Slot>()
    private val queued = ArrayDeque<Slot>()
    private val inputTransform = FloatArray(16)
    private var input: Input? = null
    private var output: Output? = null
    private var gl: GlCameraSurface? = null
    private var shapes: GlHudShapes? = null
    private var text: GlHudText? = null
    private var closed = false
    private var failed = false
    private var active = false
    private var lastAccepted = Long.MIN_VALUE
    private var lastPresented = Long.MIN_VALUE
    private var firstPresented = Long.MIN_VALUE
    private var admitted = 0
    private var presented = 0
    private var ignored = 0

    private fun surface(): GlCameraSurface = gl ?: GlCameraSurface().also { gl = it }

    override fun onInputSurface(request: SurfaceRequest) {
        if (closed || failed) {
            request.willNotProvideSurface()
            return
        }
        try {
            val renderer = surface()
            renderer.makeIdle()
            clearSlots(renderer)
            val size = request.resolution
            val depth = videoFrameBufferDepth(size.width, size.height, framesPerSecond)
            repeat(depth) { free += Slot(renderer.target(size.width, size.height)) }
            val textureId = renderer.inputTexture()
            val texture = SurfaceTexture(textureId).apply { setDefaultBufferSize(size.width, size.height) }
            val cameraSurface = Surface(texture)
            val next = Input(textureId, texture, size)
            input = next
            texture.setOnFrameAvailableListener({ accept(next) }, handler)
            request.provideSurface(cameraSurface, Executor { it.run() }) {
                // CameraX can finish this request after the effect handler has shut down.
                texture.setOnFrameAvailableListener(null)
                cameraSurface.release()
                texture.release()
                handler.post {
                    if (input === next) input = null
                    gl?.let { it.makeIdle(); it.deleteTexture(next.textureId) }
                }
            }
            Log.i(TAG, "Camera FIFO depth=$depth size=$size targetFps=$framesPerSecond")
        } catch (error: RuntimeException) {
            request.willNotProvideSurface()
            fail(error)
            throw ProcessingException()
        }
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (closed || failed) {
            surfaceOutput.close()
            return
        }
        try {
            val renderer = surface()
            val encoder = surfaceOutput.getSurface(executor) {
                if (output?.handle === surfaceOutput) clearOutput()
                else surfaceOutput.close()
            }
            clearOutput()
            val window = renderer.window(encoder)
            output = Output(surfaceOutput, window)
            Log.i(TAG, "Encoder surface ${window.width}x${window.height}")
        } catch (error: RuntimeException) {
            surfaceOutput.close()
            fail(error)
            throw ProcessingException()
        }
    }

    private fun accept(source: Input) {
        if (closed || failed || input !== source) return
        val renderer = gl ?: return
        try {
            renderer.ensureCurrent()
            source.texture.updateTexImage()
            val sink = output ?: return
            val timestamp = source.texture.timestamp
            if (timestamp <= 0L || timestamp <= lastAccepted) {
                if (active) ignored++
                return
            }
            if (free.isEmpty()) releaseOldest(sink, renderer)
            val slot = free.removeFirst()
            source.texture.getTransformMatrix(inputTransform)
            sink.handle.updateTransformMatrix(slot.transform, inputTransform)
            slot.timestamp = timestamp
            slot.frameMillis = captures.remove(timestamp) ?: clock.frameMillis(timestamp, SystemClock.elapsedRealtime())
            renderer.copy(source.textureId, slot.target, source.size.width, source.size.height)
            queued.addLast(slot)
            lastAccepted = timestamp
            if (active) admitted++
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    private fun releaseOldest(sink: Output, renderer: GlCameraSurface) {
        val slot = queued.removeFirst()
        try {
            check(slot.timestamp > lastPresented) { "Camera FIFO attempted to reorder a frame" }
            val begin = SystemClock.elapsedRealtimeNanos()
            renderer.begin(sink.window, slot.target, slot.transform)
            val width = sink.window.width
            val height = sink.window.height
            val started = SystemClock.elapsedRealtimeNanos()
            val overlay = scene(width, height, slot.timestamp, slot.frameMillis, SystemClock.elapsedRealtime(), clock.timebaseMismatch)
            val calculated = SystemClock.elapsedRealtimeNanos()
            if (overlay != null) {
                val shapeRenderer = shapes ?: GlHudShapes().also { shapes = it }
                val textRenderer = text ?: GlHudText(context).also { text = it }
                overlay.ops.forEach { op ->
                    shapeRenderer.draw(op, width, height)
                    textRenderer.draw(op, width, height)
                }
            }
            val drawn = SystemClock.elapsedRealtimeNanos()
            renderer.present(sink.window, slot.timestamp)
            lastPresented = slot.timestamp
            if (active && presented == 0) firstPresented = slot.timestamp
            if (active && (++presented and 7) == 0) {
                val submitted = SystemClock.elapsedRealtimeNanos()
                Log.i(TAG, "frame index=$presented timestampNs=${slot.timestamp} " +
                    "camera_us=${(started - begin) / 1000} calc_us=${(calculated - started) / 1000} " +
                    "draw_us=${(drawn - calculated) / 1000} submit_us=${(submitted - drawn) / 1000}")
            }
        } finally {
            free.addLast(slot)
        }
    }

    override fun captured(timestampNanos: Long, receivedAtMillis: Long) {
        handler.post {
            if (closed || failed || timestampNanos <= lastAccepted) return@post
            captures[timestampNanos] = clock.frameMillis(timestampNanos, receivedAtMillis)
            if (captures.size > 128) captures.remove(captures.keys.first())
        }
    }

    override fun setRecordingActive(active: Boolean) {
        handler.post {
            if (closed) return@post
            this.active = active
            if (active) {
                admitted = queued.size
                presented = 0
                ignored = 0
                firstPresented = Long.MIN_VALUE
            }
        }
    }

    override fun flush(completed: () -> Unit) {
        if (!handler.post {
                try {
                    val sink = output
                    val renderer = gl
                    if (!closed && !failed && sink != null && renderer != null) {
                        val tail = queued.size
                        while (queued.isNotEmpty()) releaseOldest(sink, renderer)
                        Log.i(TAG, "FIFO drained tail=$tail admitted=$admitted presented=$presented ignored=$ignored " +
                            "firstNs=$firstPresented lastNs=$lastPresented")
                    }
                } catch (error: RuntimeException) {
                    fail(error)
                } finally {
                    completed()
                }
            }) completed()
    }

    private fun clearOutput() {
        val old = output ?: return
        output = null
        while (queued.isNotEmpty()) free.addLast(queued.removeFirst())
        lastAccepted = Long.MIN_VALUE
        lastPresented = Long.MIN_VALUE
        gl?.destroy(old.window)
        old.handle.close()
    }

    private fun clearSlots(renderer: GlCameraSurface) {
        queued.forEach { renderer.destroy(it.target) }
        free.forEach { renderer.destroy(it.target) }
        queued.clear()
        free.clear()
        captures.clear()
        lastAccepted = Long.MIN_VALUE
    }

    private fun fail(error: RuntimeException) {
        if (failed) return
        failed = true
        onError(error)
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            input?.texture?.setOnFrameAvailableListener(null)
            clearOutput()
            gl?.let { renderer ->
                renderer.makeIdle()
                clearSlots(renderer)
                shapes?.close()
                text?.close()
                renderer.close()
            }
            gl = null
        }
    }

    private companion object { const val TAG = "RideGlHud" }
}

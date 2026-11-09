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
    private val stressReserve: Int? = null,
    private val scene: (width: Int, height: Int, timestampNanos: Long, frameMillis: Long, availableAtMillis: Long, clockMismatch: Boolean) -> OverlayScene?,
) : RideVideoOverlay, SurfaceProcessor {
    private class Slot(val target: GlCameraSurface.Target) {
        val transform = FloatArray(16)
        var timestamp = 0L
        var frameMillis = 0L
        var clockMismatch = false
        var ready = 0L
        var reusable = 0L
    }

    private data class Input(val textureId: Int, val texture: SurfaceTexture, val size: Size)

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
    private var output: GlesVideoOutput.Sink? = null
    private var encoder: GlesVideoOutput? = null
    private var gl: GlCameraSurface? = null
    private var depth = 0
    private var poolGeneration = 0
    private var closed = false
    private var failed = false
    private var active = false
    private var lastAccepted = Long.MIN_VALUE
    private var admitted = 0
    private var ignored = 0
    private var busyDrops = 0

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
            depth = videoFrameBufferDepth(size.width, size.height, framesPerSecond)
            // Keep the full telemetry lookahead plus a 64 MiB stall reserve, with at least one spare slot.
            val bytesPerFrame = size.width.toLong() * size.height * 4
            val reserve = stressReserve ?: (64L * 1024 * 1024 / bytesPerFrame).coerceIn(1, 4).toInt()
            repeat(depth + reserve) { free += Slot(renderer.target(size.width, size.height)) }
            val textureId = renderer.inputTexture()
            val texture = SurfaceTexture(textureId).apply { setDefaultBufferSize(size.width, size.height) }
            val cameraSurface = Surface(texture)
            val next = Input(textureId, texture, size)
            input = next
            texture.setOnFrameAvailableListener({ accept(next) }, handler)
            request.provideSurface(cameraSurface, Executor { it.run() }) {
                // CameraX can finish this request after the effect handler has shut down.
                fun release() {
                    texture.setOnFrameAvailableListener(null)
                    cameraSurface.release()
                    texture.release()
                }
                if (!handler.post {
                    if (input === next) input = null
                    release()
                    gl?.let { it.makeIdle(); it.deleteTexture(next.textureId) }
                }) release()
            }
            Log.i(TAG, "Camera FIFO depth=$depth reserve=$reserve size=$size targetFps=$framesPerSecond")
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
        val sink = GlesVideoOutput.Sink(surfaceOutput)
        try {
            val renderer = surface()
            clearOutput()
            val worker = encoder ?: GlesVideoOutput(context, scene, measureSwap = stressReserve != null) { error ->
                handler.post { if (!closed) fail(error) }
            }.also {
                encoder = it
                it.setRecordingActive(active)
            }
            output = sink
            val surface = surfaceOutput.getSurface(executor) {
                // A replaced sink has already queued its close behind the encoder's last swap.
                if (output === sink) clearOutput()
            }
            worker.open(sink, surface, renderer)
        } catch (error: RuntimeException) {
            if (output === sink) clearOutput() else if (!sink.retired) surfaceOutput.close()
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
            lastAccepted = timestamp
            val captureMillis = captures.remove(timestamp)
            if (free.isEmpty()) {
                if (active) busyDrops++
                return
            }
            if (queued.size == depth) {
                renderer.flush()
                releaseOldest(sink, renderer)
            }
            val slot = free.removeFirst()
            if (slot.ready != 0L) renderer.deleteFence(slot.ready)
            if (slot.reusable != 0L) {
                renderer.awaitFence(slot.reusable)
                slot.reusable = 0L
            }
            source.texture.getTransformMatrix(inputTransform)
            sink.handle.updateTransformMatrix(slot.transform, inputTransform)
            slot.timestamp = timestamp
            slot.frameMillis = captureMillis ?: clock.frameMillis(timestamp, SystemClock.elapsedRealtime())
            slot.clockMismatch = clock.timebaseMismatch
            renderer.copy(source.textureId, slot.target, source.size.width, source.size.height)
            slot.ready = renderer.fence()
            queued.addLast(slot)
            if (active) admitted++
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    private fun releaseOldest(sink: GlesVideoOutput.Sink, renderer: GlCameraSurface) {
        // The slot's fence covers its copy, even when newer copies are still running.
        val slot = queued.removeFirst()
        val generation = poolGeneration
        val frame = GlesVideoOutput.Frame(slot.target.texture, slot.transform, slot.timestamp,
            slot.frameMillis, slot.clockMismatch, slot.ready)
        slot.ready = 0L
        checkNotNull(encoder).enqueue(sink, frame) { unusedReady, reusable ->
            handler.post {
                if (!closed) {
                    renderer.ensureCurrent()
                    if (unusedReady != 0L) renderer.deleteFence(unusedReady)
                    slot.reusable = reusable
                    if (generation == poolGeneration) free.addLast(slot) else destroySlot(renderer, slot)
                }
            }
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
                ignored = 0
                busyDrops = 0
            }
            encoder?.setRecordingActive(active)
        }
    }

    override fun flush(completed: () -> Unit) {
        if (!handler.post {
                try {
                    val sink = output
                    val renderer = gl
                    if (!closed && !failed && sink != null && renderer != null) {
                        val tail = queued.size
                        renderer.flush()
                        while (queued.isNotEmpty()) releaseOldest(sink, renderer)
                        checkNotNull(encoder).flush(tail, admitted, ignored, busyDrops, completed)
                        return@post
                    }
                } catch (error: RuntimeException) {
                    fail(error)
                }
                completed()
            }) completed()
    }

    private fun clearOutput() {
        val old = output ?: return
        output = null
        while (queued.isNotEmpty()) free.addLast(queued.removeFirst())
        lastAccepted = Long.MIN_VALUE
        checkNotNull(encoder).retire(old)
    }

    private fun clearSlots(renderer: GlCameraSurface) {
        poolGeneration++
        queued.forEach { destroySlot(renderer, it) }
        free.forEach { destroySlot(renderer, it) }
        queued.clear()
        free.clear()
        captures.clear()
        lastAccepted = Long.MIN_VALUE
    }

    private fun destroySlot(renderer: GlCameraSurface, slot: Slot) {
        if (slot.ready != 0L) renderer.deleteFence(slot.ready)
        if (slot.reusable != 0L) renderer.deleteFence(slot.reusable)
        renderer.destroy(slot.target)
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
            val renderer = gl
            gl = null
            val worker = encoder
            if (worker == null) renderer?.close() else {
                // The capture handler may quit immediately after close. The encoder thread
                // releases both contexts after its last queued use of the shared textures.
                renderer?.detach()
                worker.close { renderer?.close() }
            }
            free.clear()
            queued.clear()
            captures.clear()
        }
    }

    private companion object { const val TAG = "RideGlHud" }
}

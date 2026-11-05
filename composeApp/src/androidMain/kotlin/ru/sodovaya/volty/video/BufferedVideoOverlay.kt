package ru.sodovaya.volty.video

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraEffect
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import java.util.concurrent.TimeUnit
import ru.sodovaya.volty.domain.video.VideoFrameClock
import ru.sodovaya.volty.domain.video.VideoFrameFlush
import ru.sodovaya.volty.domain.video.VideoFrameReleaseQueue

/**
 * Only GL textures are delayed: their original timestamps, and consequently audio sync, are unchanged.
 * SENSOR_TIMESTAMP is validated against a real effect frame before exact-timestamp dequeue is enabled.
 * This matters: CameraX discards older textures even when a requested timestamp is NOT found.
 * All mutable state is confined to [handler]; capture callbacks post their original receipt time.
 */
internal class BufferedVideoOverlay(
    queueDepth: Int,
    private val handler: Handler,
    realtime: Boolean,
    private val hasUpperBracket: (frameMillis: Long, availableAtMillis: Long) -> Boolean,
    onError: (Throwable) -> Unit,
    onDraw: (Frame, frameMillis: Long, availableAtMillis: Long) -> Boolean,
) : AutoCloseable {
    private data class Capture(val receivedAt: Long, val frameMillis: Long)
    private val pending = VideoFrameReleaseQueue()
    private val captures = mutableMapOf<Long, Capture>()
    val clock = VideoFrameClock(realtime)
    private var timestampsMatch = false
    private var mismatchReported = false
    private var closed = false
    private var tickPosted = false
    private var releaseMisses = 0
    private var recordingActive = false
    private var flushing: VideoFrameFlush? = null
    private val flushCompletions = mutableListOf<() -> Unit>()
    val effect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, queueDepth, handler, onError)

    init {
        effect.setOnDrawListener { frame ->
            if (!timestampsMatch && pending.contains(frame.timestampNanos)) {
                timestampsMatch = true
                Log.i(TAG, "Validated capture/effect timestamps; adaptive frame release enabled")
            } else if (!timestampsMatch && !mismatchReported) {
                mismatchReported = true
                Log.w(TAG, "Effect/capture timestamps have not matched; using bounded FIFO fallback")
            }
            val time = captures[frame.timestampNanos]?.frameMillis
                ?: clock.frameMillis(frame.timestampNanos, SystemClock.elapsedRealtime())
            pending.rendered(frame.timestampNanos)
            captures.keys.filter { it <= frame.timestampNanos }.forEach(captures::remove)
            onDraw(frame, time, SystemClock.elapsedRealtime())
        }
    }

    fun captured(timestampNanos: Long, receivedAtMillis: Long) {
        handler.post {
            if (closed) return@post
            // Observe BEFORE buffering: calibrating an UNKNOWN clock at draw would add queue latency to time.
            val time = clock.frameMillis(timestampNanos, receivedAtMillis)
            if (!recordingActive) return@post
            captures[timestampNanos] = Capture(receivedAtMillis, time)
            while (captures.size > 128) captures.remove(captures.keys.min())
            pending.offer(timestampNanos, receivedAtMillis)
            // Capture metadata can arrive during the bounded stop drain. Include it in that same
            // ordered boundary; the shared deadline still prevents an endlessly advancing tail.
            flushing?.append(listOf(timestampNanos))
            scheduleTick()
        }
    }

    fun setRecordingActive(active: Boolean) {
        handler.post {
            recordingActive = active
            if (!active) {
                handler.removeCallbacks(tick)
                tickPosted = false
                pending.flush()
                captures.clear()
            } else scheduleTick()
        }
    }

    private val tick = Runnable {
        tickPosted = false
        if (!closed) {
            if (timestampsMatch && flushing == null) releaseReadyFrames()
            scheduleTick()
        }
    }

    private fun scheduleTick() {
        if (tickPosted || closed || !recordingActive) return
        tickPosted = true
        handler.postDelayed(tick, 10)
    }

    private fun releaseReadyFrames() {
        val now = SystemClock.elapsedRealtime()
        repeat(64) {
            val timestamp = pending.next(now) { stamp ->
                captures[stamp]?.let { hasUpperBracket(it.frameMillis, now) } ?: true
            } ?: return
            val capture = captures[timestamp]
            val result = draw(timestamp)
            if (result == OverlayEffect.RESULT_FRAME_NOT_FOUND && capture != null && now - capture.receivedAt < 300) {
                pending.retry(timestamp, capture.receivedAt)
                return // Texture delivery may trail CaptureResult. Never overtake this frame.
            }
            captures.remove(timestamp)
        }
    }

    private fun draw(timestampNanos: Long): Int = try {
        // CameraX 1.5.3 executes immediately on its supplied GL handler. A zero timeout prevents a future
        // implementation change from deadlocking the GL thread during stop/flush.
        effect.drawFrameAsync(timestampNanos).get(0, TimeUnit.MILLISECONDS).also {
            if (it == OverlayEffect.RESULT_FRAME_NOT_FOUND) releaseMisses++
        }
    } catch (error: Exception) {
        Log.w(TAG, "Buffered frame release failed", error)
        OverlayEffect.RESULT_FRAME_NOT_FOUND
    }

    /**
     * Flush in order while the recording surface still exists, then request Recorder.stop on the main thread.
     * Do not wait for a new BLE sample at the tail; the final available value is held if there is no upper bracket.
     * Background source loss may invalidate a surface before this runs; completion still always runs.
     */
    fun flush(completed: () -> Unit) {
        val accepted = handler.post {
            if (closed || !timestampsMatch) {
                completed()
                return@post
            }
            flushCompletions += completed
            val active = flushing
            if (active != null) active.append(pending.flush())
            else {
                flushing = VideoFrameFlush(pending.flush(), SystemClock.elapsedRealtime())
                drainFlush.run()
            }
        }
        if (!accepted) completed() // A stopped GL looper must never strand Recorder.stop.
    }

    private val drainFlush = object : Runnable {
        override fun run() {
            val active = flushing ?: return
            val finished = closed || active.drain(SystemClock.elapsedRealtime()) {
                val result = draw(it)
                result != OverlayEffect.RESULT_FRAME_NOT_FOUND
            }
            if (finished || !handler.postDelayed(this, 10)) finishFlush()
        }
    }

    private fun finishFlush() {
        flushing?.lastTimestamp?.let { last ->
            pending.rendered(last)
            captures.keys.filter { it <= last }.forEach(captures::remove)
        }
        flushing = null
        val callbacks = flushCompletions.toList()
        flushCompletions.clear()
        Log.i(TAG, "Frame buffer flushed (timestampMatch=$timestampsMatch, releaseMisses=$releaseMisses)")
        callbacks.forEach { it() }
    }

    override fun close() {
        handler.post {
            closed = true
            handler.removeCallbacks(tick)
            handler.removeCallbacks(drainFlush)
            finishFlush()
            pending.flush()
            captures.clear()
            effect.close()
        }
    }

    private companion object { const val TAG = "RideVideoBuffer" }
}

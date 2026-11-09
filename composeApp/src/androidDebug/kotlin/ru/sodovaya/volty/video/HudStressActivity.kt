package ru.sodovaya.volty.video

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraEffect
import androidx.camera.core.DynamicRange
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.CameraInternal
import androidx.core.util.Consumer
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin
import ru.sodovaya.volty.domain.video.OverlayContentMotion
import ru.sodovaya.volty.domain.video.OverlayDrawGate
import ru.sodovaya.volty.domain.video.OverlayGeometry
import ru.sodovaya.volty.domain.video.OverlayScaleTracker
import ru.sodovaya.volty.domain.video.OverlayScene
import ru.sodovaya.volty.domain.video.OverlayTemplates
import ru.sodovaya.volty.domain.video.OverlayTrip
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.VideoOverlayStrings
import ru.sodovaya.volty.domain.video.VideoReading
import ru.sodovaya.volty.domain.video.VideoSnapshot
import ru.sodovaya.volty.domain.video.VideoTimeline
import ru.sodovaya.volty.domain.video.overlayDrawKey
import ru.sodovaya.volty.domain.video.videoFrameBufferDepth
import ru.sodovaya.volty.util.UnitSystem

/** Debug-only decoder -> CameraX effect -> encoder test, bypassing the emulator's camera HAL. */
@SuppressLint("RestrictedApi")
class HudStressActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val mode = intent.getStringExtra("mode")
        if (mode != "codec" && mode != "canvas" && mode != "gles") {
            Log.e(TAG, "Set mode to codec, canvas or gles")
            finish()
            return
        }
        val reserve = if (intent.hasExtra("reserve")) intent.getIntExtra("reserve", 0) else null
        val bitrateMbps = intent.getIntExtra("bitrate", 20)
        val keyFrameSeconds = intent.getIntExtra("keyFrameSeconds", 1)
        Thread({
            runCatching {
                require(reserve == null || (mode == "gles" && reserve in 1..8))
                require(bitrateMbps in 1..50)
                require(keyFrameSeconds in 1..10)
                runStress(mode, reserve, bitrateMbps * 1_000_000, keyFrameSeconds)
            }
                .onFailure { Log.e(TAG, "FAILED mode=$mode", it) }
            runOnUiThread { finish() }
        }, "HudStressDriver").start()
    }

    private fun runStress(mode: String, reserve: Int?, bitRate: Int, keyFrameSeconds: Int) {
        val input = File(filesDir, "hud-stress-source.mp4")
        check(input.isFile) { "Missing ${input.name}" }
        val file = File(filesDir, "hud-stress-$mode.mp4")
        if (mode == "codec") {
            StressEncoder(WIDTH, HEIGHT, FPS, file, bitRate, keyFrameSeconds).use { encoder ->
                Log.i(TAG, "START mode=$mode input=${WIDTH}x$HEIGHT targetFps=$FPS")
                val submitted = feedStressVideo(input, encoder.surface) {}
                encoder.finish()
                Log.i(TAG, "DONE mode=$mode inputFrames=$submitted bytes=${file.length()}")
            }
            return
        }
        val effectThread = HandlerThread("HudStressEffect").apply { start() }
        val handler = Handler(effectThread.looper)
        val error = AtomicReference<Throwable>()
        val onError: (Throwable) -> Unit = { error.compareAndSet(null, it); Log.e(TAG, "Effect failed", it) }
        val hud = StressHud()
        val depth = videoFrameBufferDepth(WIDTH, HEIGHT, FPS)
        try {
            StressEncoder(WIDTH, HEIGHT, FPS, file, bitRate, keyFrameSeconds).use { encoder ->
                val cameraRequest = syntheticRequest()
                val overlay = if (mode == "gles") {
                    GlesBufferedVideoOverlay(this, handler, FPS, true, onError, stressReserve = reserve) { width, height, _, time, available, _ ->
                        hud.scene(width, height, time, available)
                    }
                } else {
                    BufferedVideoOverlay(depth, handler, true, onError) { frame, time, available ->
                        hud.drawCanvas(frame, time, available)
                        true
                    }
                }
                try {
                    onEffectThread(handler) {
                        val processor = checkNotNull(overlay.effect.surfaceProcessor)
                        processor.onInputSurface(cameraRequest)
                        processor.onOutputSurface(EncoderOutput(encoder.surface))
                    }
                    overlay.setRecordingActive(true)
                    val surface = cameraRequest.deferrableSurface.surface.get(5, TimeUnit.SECONDS)
                    Log.i(TAG, "START mode=$mode input=${WIDTH}x$HEIGHT targetFps=$FPS depth=$depth")
                    var firstSourceNs = 0L
                    var lastSourceNs = 0L
                    val submitted = feedStressVideo(input, surface) { timestamp ->
                        if (firstSourceNs == 0L) firstSourceNs = timestamp
                        lastSourceNs = timestamp
                        hud.sample(timestamp)
                        overlay.captured(timestamp, SystemClock.elapsedRealtime())
                        error.get()?.let { throw IllegalStateException("Effect failed during decode", it) }
                    }
                    Log.i(TAG, "Source surface timestamps firstNs=$firstSourceNs lastNs=$lastSourceNs")
                    // The final decoder buffer must reach the effect before its existing stop drain runs.
                    Thread.sleep(300)
                    val drained = CountDownLatch(1)
                    overlay.flush { drained.countDown() }
                    check(drained.await(8, TimeUnit.SECONDS)) { "HUD FIFO did not drain" }
                    error.get()?.let { throw IllegalStateException("Effect failed", it) }
                    encoder.finish()
                    Log.i(TAG, "DONE mode=$mode inputFrames=$submitted bytes=${file.length()}")
                } finally {
                    overlay.close()
                    cameraRequest.deferrableSurface.close()
                    onEffectThread(handler) {}
                }
            }
        } finally {
            effectThread.quitSafely()
            effectThread.join(2_000)
        }
    }

    private fun syntheticRequest(): SurfaceRequest {
        // SurfaceRequest stores CameraInternal. No device operation is valid in this synthetic feed.
        val camera = Proxy.newProxyInstance(CameraInternal::class.java.classLoader, arrayOf(CameraInternal::class.java)) { _, method, _ ->
            error("Unexpected synthetic camera call ${method.name}")
        } as CameraInternal
        return SurfaceRequest(Size(WIDTH, HEIGHT), camera, DynamicRange.SDR, Range(FPS, FPS)) {}.apply {
            updateTransformationInfo(SurfaceRequest.TransformationInfo.of(
                Rect(0, 0, WIDTH, HEIGHT), 0, Surface.ROTATION_0, false, Matrix(), false,
            ))
        }
    }

    private fun <T> onEffectThread(handler: Handler, action: () -> T): T {
        val task = FutureTask(action)
        check(handler.post(task))
        return task.get(10, TimeUnit.SECONDS)
    }

    private class EncoderOutput(private val encoderSurface: Surface) : SurfaceOutput {
        override fun getSurface(executor: Executor, listener: Consumer<SurfaceOutput.Event>): Surface = encoderSurface
        override fun getTargets() = CameraEffect.VIDEO_CAPTURE
        override fun getSize() = Size(WIDTH, HEIGHT)
        override fun updateTransformMatrix(updated: FloatArray, original: FloatArray) { original.copyInto(updated) }
        override fun close() = Unit
    }

    private inner class StressHud {
        private val timeline = VideoTimeline()
        private val renderer = VideoOverlayRenderer(this@HudStressActivity)
        private val preset = OverlayTemplates.hud()
        private val strings = VideoOverlayStrings()
        private val scales = OverlayScaleTracker()
        private val motion = OverlayContentMotion()
        private val gate = OverlayDrawGate()
        private var first = Long.MIN_VALUE
        private var lastSample = Long.MIN_VALUE
        private var count = 0

        fun sample(timestamp: Long) {
            val now = timestamp / 1_000_000
            if (first == Long.MIN_VALUE) first = now
            if (lastSample != Long.MIN_VALUE && now - lastSample < 41) return
            lastSample = now
            val phase = (now - first) / 350.0
            fun reading(base: Double, amplitude: Double, offset: Double = 0.0) = VideoReading(base + sin(phase + offset) * amplitude)
            val readings = mapOf(
                VideoMetric.SPEED to reading(34.0, 20.0),
                VideoMetric.DUTY to reading(50.0, 33.0, 0.8),
                VideoMetric.BATTERY_CURRENT to reading(15.0, 24.0, 1.5),
                VideoMetric.ESC_TEMPERATURE to reading(44.0, 4.0),
                VideoMetric.MOTOR_TEMPERATURE to reading(36.0, 3.0, 2.0),
                VideoMetric.SOC to reading(74.0, 1.0),
                VideoMetric.VOLTAGE to reading(95.0, 4.0, 3.0),
            )
            synchronized(timeline) { timeline.offer(VideoSnapshot(now, "stress", readings)) }
        }

        private fun calculate(geometry: OverlayGeometry, time: Long, available: Long): Pair<OverlayScene, ru.sodovaya.volty.domain.video.OverlayDrawKey> {
            val (snapshot, values) = synchronized(timeline) { timeline.sample(time) to timeline.interpolatedValuesAt(time, available) }
            val system = UnitSystem.METRIC
            val ranges = scales.update(preset, snapshot, system)
            val trip = OverlayTrip()
            motion.beginFrame(geometry, time)
            val scene = renderer.scene(geometry, preset, snapshot, strings, system, trip, ranges, values, motion)
            val key = overlayDrawKey(preset, snapshot, strings, system, geometry, trip = trip, scales = ranges, dials = values)
                .copy(textPositions = motion.positions)
            return scene to key
        }

        fun scene(width: Int, height: Int, time: Long, available: Long): OverlayScene =
            calculate(OverlayGeometry(width, height, 0, false, 0, 0, width, height), time, available).first

        fun drawCanvas(frame: androidx.camera.effects.Frame, time: Long, available: Long) {
            val crop = frame.cropRect
            val geometry = OverlayGeometry(frame.size.width, frame.size.height, frame.rotationDegrees, frame.isMirroring,
                crop.left, crop.top, crop.right, crop.bottom)
            val started = SystemClock.elapsedRealtimeNanos()
            val (scene, key) = calculate(geometry, time, available)
            val calculated = SystemClock.elapsedRealtimeNanos()
            if (!gate.shouldDraw(key)) return
            renderer.draw(frame, frame.overlayCanvas, scene)
            if ((++count and 7) == 0) {
                Log.i(TAG, "canvas calc_us=${(calculated - started) / 1000} draw_us=${(SystemClock.elapsedRealtimeNanos() - calculated) / 1000}")
            }
        }
    }

    private companion object {
        const val TAG = "RideHudStress"
        const val WIDTH = 2560
        const val HEIGHT = 1440
        const val FPS = 60
    }
}

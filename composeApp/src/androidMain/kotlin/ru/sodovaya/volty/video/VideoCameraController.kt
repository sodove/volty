package ru.sodovaya.volty.video

import android.Manifest
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.display.DisplayManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraEffect
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.ExperimentalSessionConfig
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import ru.sodovaya.volty.BuildConfig
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.util.UnitSystem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class VideoCameraState(
    val ready: Boolean = false,
    val phase: VideoRecordingPhase = VideoRecordingPhase.IDLE,
    val qualities: List<Quality> = emptyList(),
    val quality: Quality = Quality.FHD,
    val front: Boolean = false,
    val canSwitch: Boolean = false,
    val durationSeconds: Long = 0,
    val saved: Uri? = null,
    /** How many files of the last ride were kept; above 1 only when segmentation is on. */
    val savedParts: Int = 0,
    val interrupted: Boolean = false,
    val error: String? = null,
    /** An overlay draw threw at least once this session; those frames were recorded without it. */
    val overlayFailed: Boolean = false,
    /** The camera's REALTIME timebase claim was caught lying; telemetry is matched by an estimated offset. */
    val clockMismatch: Boolean = false,
    val segmentMinutes: Int = VideoSegmentPolicy.DEFAULT_SEGMENT_MINUTES,
    /** Measured on the camera stream; null until a second of frames has gone by. */
    val frameRate: Int? = null,
    val frameRateChoices: List<Int> = emptyList(),
    /** Requested fixed session rate; [frameRate] remains a measurement, never this setting. */
    val targetFrameRate: Int? = null,
    val stabilizationAvailable: Boolean = false,
    val stabilizationModes: List<VideoStabilizationMode> = emptyList(),
    val stabilizationMode: VideoStabilizationMode = VideoStabilizationMode.OFF,
    /** Camera2's reported mode, not an inference from the request or device model. */
    val stabilizationActive: Boolean? = null,
    /** The current file's size and length so far, for the time-left estimate. */
    val recordedBytes: Long = 0,
    val recordedNanos: Long = 0,
    /**
     * The camera itself failed and needs a rebind. Kept apart from [error] and from the file's outcome: a camera that
     * fails mid-recording still finalizes a good file, and that must not make the failure look like a clean save.
     */
    val cameraFailed: Boolean = false,
    /** How the last recording ended; null until one has. */
    val stopOutcome: VideoStopOutcome? = null,
    /** The preview frame's width / height as displayed, once it streams; the view-tree overlay is fitted to it. */
    val previewAspect: Float? = null,
    /** The app stopped the last recording itself, to keep a minute of storage in hand. */
    val stoppedForStorage: Boolean = false,
)

/** Main-thread camera owner; only the overlay listener runs on its dedicated GL thread. */
@OptIn(ExperimentalCamera2Interop::class, ExperimentalSessionConfig::class)
internal class VideoCameraController(
    private val context: Context,
    private val owner: LifecycleOwner,
    private val settings: SharedPreferences,
) {
    private val useGlesHud = BuildConfig.GLES_RIDE_HUD &&
        (context.getSystemService(ActivityManager::class.java)?.deviceConfigurationInfo?.reqGlEsVersion ?: 0) >= 0x0003_0000
    private var preferredQuality = when (settings.getInt(QUALITY_KEY, 1080)) {
        2160 -> Quality.UHD
        720 -> Quality.HD
        480 -> Quality.SD
        else -> Quality.FHD
    }
    private val mutableState = MutableStateFlow(VideoCameraState(quality = preferredQuality))
    val state = mutableState.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var current: Segment? = null
    @Volatile private var bufferedOverlay: RideVideoOverlay? = null
    private var view: PreviewView? = null
    // The file's overlay has its own GL thread. The preview's is drawn in the view tree (see [previewInput]).
    private val overlayThread = HandlerThread("RideVideoOverlay").apply { start() }
    private val handler = Handler(overlayThread.looper)
    private val timeline = VideoTimeline()
    // The preview overlay's own trackers, main thread only: they must not share state with the GL thread's.
    private val previewScales = OverlayScaleTracker()
    private val previewTrips = VideoTripTracker()
    private val previewClock = VideoPresentationClock()
    private var previewVehicleId: String? = null
    private var storageStopRequested = false
    /** True while the editor takes a still, so the frame it gets has no old overlay burned into it. */
    @Volatile var overlayHidden = false
    @Volatile private var recordedSeconds = 0L
    @Volatile private var overlayRecording = false
    private val shutterGuard = VideoShutterGuard()
    /** The microphone choice, from the screen; the volume keys need it as much as the shutter does. */
    val audioWanted: Boolean get() = settings.getBoolean(AUDIO_KEY, false)
    @Volatile var preset: OverlayPreset = OverlayTemplates.hud()
    /** C3's time and distance: per recording by default, a user setting. */
    @Volatile var tripScope: OverlayTripScope = OverlayTripScope.RECORDING
    /** Localized words and units; set from the screen, which owns the resources. */
    @Volatile var strings: VideoOverlayStrings = VideoOverlayStrings()
    @Volatile var unitSystem: UnitSystem = UnitSystem.METRIC
    @Volatile private var disposed = false
    @Volatile private var generation = 0
    private var preferredFrameRate = settings.getInt(FPS_KEY, 60).takeIf { it == 30 || it == 60 } ?: 60
    private var preferredStabilization = VideoStabilizationMode.entries.firstOrNull {
        it.name == settings.getString(STABILIZATION_KEY, null)
    }
    private val finalization = VideoFinalizationGate(requestStop = ::stop, release = ::releaseResources)
    private val sessionLifecycle = VideoCameraSessionLifecycle(
        busy = { finalization.hasPendingRecordings },
        bind = { if (provider == null || view == null || disposed) false else { bind(); true } },
        release = ::unbindSession,
        stop = ::stop,
    )

    // One ride may span several files; see VideoSegmentPolicy for why, and for why rotation is off by default.
    private var segmentPolicy = VideoSegmentPolicy(VideoSegmentPolicy.DEFAULT_SEGMENT_MINUTES)
    private val ledger = VideoSegmentLedger()
    private var rideStamp = ""
    private var rotating = false
    private var audioEnabled = false

    // The file's rotation follows the sensor; the preview follows the display (PreviewView needs that).
    private val orientation = VideoOrientationPolicy()
    private var orientationListener: OrientationEventListener? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private class Segment(val file: File?) {
        var recording: Recording? = null
        var rotatedOut = false
    }

    fun offer(snapshot: VideoSnapshot?) = synchronized(timeline) {
        if (previewVehicleId != snapshot?.vehicleId) {
            previewVehicleId = snapshot?.vehicleId
            previewClock.reset()
        }
        if (snapshot == null) timeline.clear() else timeline.offer(snapshot)
    }

    fun setSegmentMinutes(minutes: Int) {
        val sanitized = VideoSegmentPolicy.sanitized(minutes)
        if (!canEditVideoOverlay(state.value.phase)) return
        segmentPolicy = VideoSegmentPolicy(sanitized)
        mutableState.update { it.copy(segmentMinutes = sanitized) }
    }

    fun attach(previewView: PreviewView) {
        view = previewView
        // Once per view: Retry calls attach again, and every extra observer would live until the screen closes.
        if (observedView !== previewView) {
            observedView = previewView
            previewView.previewStreamState.observe(owner) { stream ->
                if (stream == PreviewView.StreamState.STREAMING && sessionLifecycle.foreground && !disposed) {
                    refreshPreviewAspect()
                    mutableState.update { it.copy(ready = !it.cameraFailed) }
                }
            }
        }
        if (sessionLifecycle.foreground) startSensors()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            runCatching { provider = future.get(); sessionLifecycle.attached() }.onFailure(::failed)
        }, ContextCompat.getMainExecutor(context))
    }

    fun onHostStarted() {
        if (disposed) return
        if (view != null) startSensors()
        sessionLifecycle.started()
    }

    fun onHostStopped() {
        if (disposed) return
        mutableState.update { it.copy(ready = false, frameRate = null, previewAspect = null) }
        stopSensors()
        sessionLifecycle.stopped()
    }

    fun switchCamera() {
        if (!canEditVideoOverlay(state.value.phase) || !state.value.canSwitch) return
        mutableState.value = state.value.copy(front = !state.value.front)
        bind()
    }

    fun setQuality(quality: Quality) {
        if (!canEditVideoOverlay(state.value.phase) || quality !in state.value.qualities) return
        preferredQuality = quality
        settings.edit().putInt(QUALITY_KEY, videoQualityHeight(quality)).apply()
        bind()
    }

    fun setFrameRate(fps: Int) {
        if (!canEditVideoOverlay(state.value.phase) || fps !in state.value.frameRateChoices) return
        preferredFrameRate = fps
        settings.edit().putInt(FPS_KEY, fps).apply()
        bind()
    }

    fun setStabilization(mode: VideoStabilizationMode) {
        if (!canEditVideoOverlay(state.value.phase) || mode !in state.value.stabilizationModes) return
        preferredStabilization = mode
        settings.edit().putString(STABILIZATION_KEY, mode.name).apply()
        bind()
    }

    fun setAudioWanted(enabled: Boolean) {
        settings.edit().putBoolean(AUDIO_KEY, enabled).apply()
    }

    /** The display rotation changed: only the preview follows it. The file's rotation comes from the sensor. */
    fun updateRotation(rotation: Int) {
        if (!canEditVideoOverlay(state.value.phase)) return
        preview?.targetRotation = rotation
        refreshPreviewAspect()
    }

    private var observedView: PreviewView? = null

    /** The displayed frame's shape, from what the preview actually streams; refreshed on every stream or rotation change. */
    private fun refreshPreviewAspect() {
        val info = preview?.resolutionInfo ?: return
        val aspect = videoPreviewAspect(info.cropRect.width(), info.cropRect.height(), info.rotationDegrees)
        if (aspect != state.value.previewAspect) mutableState.update { it.copy(previewAspect = aspect) }
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int = view?.display?.rotation
        ?: (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation

    private fun startSensors() {
        if (orientationListener == null) {
            val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
                override fun onOrientationChanged(degrees: Int) {
                    val changed = orientation.onSensorDegrees(degrees) ?: return
                    // A recording keeps the rotation it started with.
                    if (canEditVideoOverlay(state.value.phase)) applyFileRotation(changed)
                }
            }
            if (listener.canDetectOrientation()) listener.enable()
            orientationListener = listener
        }
        if (displayListener == null) {
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                // A 90 to 270 flip fires no configuration change, only this.
                override fun onDisplayChanged(displayId: Int) = updateRotation(displayRotation())
            }
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(listener, Handler(Looper.getMainLooper()))
            displayListener = listener
        }
    }

    private fun applyFileRotation(rotation: Int) {
        val currentCapture = capture ?: return
        if (currentCapture.targetRotation == rotation) return
        // CameraX applies the initial rotation to the processor output. A later target rotation
        // can be written as MP4 metadata instead, which would turn the already composited HUD.
        if (useGlesHud) bind() else currentCapture.targetRotation = rotation
    }

    private fun stopSensors() {
        orientationListener?.disable(); orientationListener = null
        displayListener?.let { (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).unregisterDisplayListener(it) }
        displayListener = null
    }

    private fun geometryOf(frame: Frame): OverlayGeometry {
        val crop = frame.cropRect
        return OverlayGeometry(
            width = frame.size.width, height = frame.size.height,
            rotationDegrees = frame.rotationDegrees, mirrored = frame.isMirroring,
            cropLeft = crop.left, cropTop = crop.top, cropRight = crop.right, cropBottom = crop.bottom,
        )
    }

    /**
     * Everything one overlay target needs, confined to that target's GL thread.
     *
     * **Why two effects (I5).** The file's rotation follows the accelerometer and the preview's follows the display,
     * so with auto-rotate off and the phone clamped sideways they differ (preview 0, file 90). One effect for
     * PREVIEW and VIDEO_CAPTURE together is a sharing effect: CameraX feeds it one stream and one rotation, and the
     * overlay would come out sideways in one of the two. CameraX allows one effect per target as long as the targets
     * do not overlap ("More than one effects has targets %s" is its only check, besides "one sharing effect"), and
     * each then receives its own target's `Frame.rotationDegrees`.
     * Retraction: the later preview fix removed that second effect; preview is now drawn in the view tree.
     * The recorded overlay's former 15 Hz raster cap was removed to match Studio's per-frame animation.
     */
    private inner class OverlayPipeline(
        private val recorded: Boolean,
        private val renderer: VideoOverlayRenderer,
    ) {
        private val gate = OverlayDrawGate()
        private val scales = OverlayScaleTracker()
        private val trips = VideoTripTracker()
        private val contentMotion = OverlayContentMotion()
        private val meter = VideoFrameRateMeter()
        private var clockReported = false
        private val guard = OverlayDrawGuard { error ->
            Log.w(TAG, "Overlay draw failed; the frame is recorded without it, further failures are counted only", error)
            mutableState.update { it.copy(overlayFailed = true) }
        }

        /**
         * Runs on this target's GL thread. An exception that escapes it kills the process, and with it the whole
         * recording, so nothing in here may throw. Returning true even after a failure keeps the frame flowing to
         * the encoder (false would DROP it): it just carries the previous overlay or none.
         */
        fun onDraw(frame: Frame, time: Long, availableAtMillis: Long, clockMismatch: Boolean): Boolean {
            val drawn = guard.attempt { draw(frame, time, availableAtMillis, clockMismatch) }
            if (!drawn) gate.invalidate()
            return true
        }

        private fun draw(frame: Frame, time: Long, availableAtMillis: Long, clockMismatch: Boolean) {
            val calculated = calculate(geometryOf(frame), frame.timestampNanos, time, availableAtMillis, clockMismatch)
            if (calculated == null) {
                renderer.clear(frame.overlayCanvas)
                gate.invalidate()
                return
            }
            val (scene, key) = calculated
            // Touching the canvas costs a software raster plus a full-size upload; skip unchanged HUDs.
            if (!gate.shouldDraw(key)) return
            // Retraction (2026-11-05): a 15 Hz raster cap stepped both interpolated numbers and text motion.
            val canvas = frame.overlayCanvas
            try {
                renderer.draw(frame, canvas, scene)
            } catch (error: Exception) {
                runCatching { renderer.clear(canvas) }
                throw error
            }
        }

        fun glScene(width: Int, height: Int, timestamp: Long, time: Long, available: Long, mismatch: Boolean): OverlayScene? {
            val geometry = OverlayGeometry(width, height, 0, false, 0, 0, width, height)
            var scene: OverlayScene? = null
            guard.attempt { scene = calculate(geometry, timestamp, time, available, mismatch)?.first }
            return scene
        }

        private fun calculate(
            geometry: OverlayGeometry,
            timestamp: Long,
            time: Long,
            availableAtMillis: Long,
            clockMismatch: Boolean,
        ): Pair<OverlayScene, OverlayDrawKey>? {
            if (recorded) meter.onFrame(timestamp)?.let { rate ->
                if (rate != state.value.frameRate) mutableState.update { it.copy(frameRate = rate) }
            }
            if (clockMismatch && !clockReported) {
                clockReported = true
                Log.w(TAG, "Camera claims a REALTIME timebase but its frames disagree; estimating the offset instead")
                mutableState.update { it.copy(clockMismatch = true) }
            }
            if (overlayHidden) return null
            val (snapshot, values) = synchronized(timeline) { timeline.sample(time) to timeline.interpolatedValuesAt(time, availableAtMillis) }
            val preset = preset
            val trip = if (overlayUsesTrip(preset)) {
                trips.trip(
                    tripScope, overlayRecording, recordedSeconds,
                    RideSessionClock.secondsAt(System.currentTimeMillis()),
                    snapshot?.readings?.get(VideoMetric.DISTANCE)?.value,
                )
            } else {
                OverlayTrip()
            }
            val ranges = scales.update(preset, snapshot, unitSystem)
            val words = strings
            val system = unitSystem
            contentMotion.beginFrame(listOf(preset, geometry, snapshot?.vehicleId, words, system), time)
            val scene = renderer.scene(geometry, preset, snapshot, words, system, trip, ranges, values, contentMotion)
            val key = overlayDrawKey(preset, snapshot, words, system, geometry, trip = trip, scales = ranges, dials = values)
                .copy(textPositions = contentMotion.positions)
            return scene to key
        }
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val previewView = view ?: return
        if (disposed || !sessionLifecycle.foreground || !canEditVideoOverlay(state.value.phase)) return
        val token = ++generation
        mutableState.update { it.copy(ready = false, frameRate = null, previewAspect = null, stabilizationActive = null, cameraFailed = false,
            error = null, overlayFailed = false, clockMismatch = false) }
        try {
            val selector = if (state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val info = cameraProvider.getCameraInfo(selector)
            val capabilities = Recorder.getVideoCapabilities(info)
            val qualities = capabilities.getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            require(qualities.isNotEmpty()) { "No supported video quality" }
            val quality = preferredQuality.takeIf { it in qualities } ?: qualities.first()
            cameraProvider.unbind(*listOfNotNull(preview, capture).toTypedArray())
            bufferedOverlay?.close()
            bufferedOverlay = null
            val realtime = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val onError = { error: Throwable ->
                ContextCompat.getMainExecutor(context).execute { if (token == generation && !disposed) failed(error) }
            }
            // A retiring GLES worker can still finish a frame while the next session binds.
            val videoPipeline = OverlayPipeline(recorded = true, renderer = VideoOverlayRenderer(context))
            val display = displayRotation()
            val previewBuilder = Preview.Builder().setTargetRotation(display).setTargetAspectRatio(AspectRatio.RATIO_16_9)
            val previewStabilization = Preview.getPreviewCapabilities(info).isStabilizationSupported
            val stabilizationMode = selectVideoStabilizationMode(preferredStabilization,
                capabilities.isStabilizationSupported, previewStabilization)
            // Leave the other use case UNSPECIFIED: explicitly setting it OFF disables stabilization for both.
            if (stabilizationMode == VideoStabilizationMode.PREVIEW) previewBuilder.setPreviewStabilizationEnabled(true)
            if (stabilizationMode == VideoStabilizationMode.OFF) previewBuilder.setPreviewStabilizationEnabled(false)
            Camera2Interop.Extender(previewBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                private var previousMode: Int? = null
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (token != generation || disposed) return
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { bufferedOverlay?.captured(it, SystemClock.elapsedRealtime()) }
                    val mode = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                    if (mode != previousMode) {
                        Log.i(TAG, "Stabilization requested=$stabilizationMode actual=$mode ois=${result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)} fps=${result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)} crop=${result.get(CaptureResult.SCALER_CROP_REGION)}")
                        previousMode = mode
                    }
                    val active = mode?.let { it != CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE_OFF }
                    if (token != generation || disposed || (state.value.ready && state.value.stabilizationActive == active)) return
                    ContextCompat.getMainExecutor(context).execute {
                        if (token == generation && !disposed && sessionLifecycle.foreground) {
                            refreshPreviewAspect()
                            mutableState.update { it.copy(ready = !it.cameraFailed, stabilizationActive = active) }
                        }
                    }
                }
            })
            val nextPreview = previewBuilder.build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = VoltyRideRecorder.create(quality)
            val captureBuilder = VideoCapture.Builder(recorder).setTargetRotation(orientation.rotation ?: display)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
            if (stabilizationMode == VideoStabilizationMode.VIDEO) captureBuilder.setVideoStabilizationEnabled(true)
            val nextCapture = captureBuilder.build()
            preview = nextPreview
            capture = nextCapture
            // Preview uses FIT_CENTER, so the whole encoded composition is visible in either aspect ratio.
            // Query FPS before allocating GPU buffers; using 60-frame depth at 30 FPS doubles latency/memory.
            val probeEffect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, handler, onError)
            val session = try { videoCameraSession(info, nextPreview, nextCapture, probeEffect, preferredFrameRate) }
                finally { probeEffect.close() }
            val height = videoQualityHeight(quality)
            val depth = videoFrameBufferDepth(height * 16 / 9, height, session.frameRate ?: 60)
            val nextOverlay = if (useGlesHud) {
                GlesBufferedVideoOverlay(context, handler, session.frameRate ?: 60, realtime, onError, scene = videoPipeline::glScene)
            } else {
                lateinit var canvasOverlay: BufferedVideoOverlay
                canvasOverlay = BufferedVideoOverlay(depth, handler, realtime, onError) { frame, time, available ->
                    videoPipeline.onDraw(frame, time, available, canvasOverlay.clock.timebaseMismatch)
                }
                canvasOverlay
            }
            bufferedOverlay = nextOverlay
            val bufferedSession = videoCameraSession(info, nextPreview, nextCapture, nextOverlay.effect, session.frameRate ?: preferredFrameRate)
            cameraProvider.bindToLifecycle(owner, selector, bufferedSession.config)
            Log.i(TAG, "Camera session quality=$quality fps=${bufferedSession.frameRate} frameQueue=$depth " +
                "stabilization=$stabilizationMode gles=$useGlesHud rotation=${nextCapture.targetRotation} " +
                "degrees=${nextCapture.resolutionInfo?.rotationDegrees}")
            mutableState.update { it.copy(cameraFailed = false, qualities = qualities, quality = quality,
                frameRateChoices = session.choices, targetFrameRate = session.frameRate,
                stabilizationAvailable = previewStabilization || capabilities.isStabilizationSupported,
                stabilizationModes = supportedVideoStabilizationModes(capabilities.isStabilizationSupported, previewStabilization),
                stabilizationMode = stabilizationMode,
                canSwitch = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) }
        } catch (error: Exception) { failed(error) }
    }

    /**
     * The preview's overlay input, for the view tree to draw over the PreviewView with the same scene builder and
     * drawer as the file; null while the overlay is off for the editor's still. Main thread only.
     *
     * **Retraction (I5, 2026-11-03):** the preview used to get its own CameraX `OverlayEffect`. On a cold process that
     * effect's input stream never reached its GL thread: the camera kept producing frames (dumpsys media.camera) and
     * the file still recorded, but the preview stayed blank until the screen was reopened about a minute later. It
     * reproduced 6 of 6 times on the emulator at the base commit and on this branch, and 0 of 3 with only the preview
     * effect removed. Drawing the preview's overlay in the view tree removes that pipeline, its second GL raster per
     * frame, and the rotation mismatch I5 had to solve: the view tree is in the display's orientation already.
     */
    fun previewInput(width: Int, height: Int, nowMillis: Long): OverlaySceneInput? {
        if (overlayHidden || width <= 0 || height <= 0) return null
        val (snapshot, values) = synchronized(timeline) {
            val target = previewClock.frameMillis(nowMillis, timeline.interpolationDelayMillis())
            timeline.sample(nowMillis) to timeline.interpolatedValues(nowMillis, (nowMillis - target).coerceAtLeast(0))
        }
        val preset = preset
        val trip = if (overlayUsesTrip(preset)) {
            previewTrips.trip(
                tripScope, overlayRecording, recordedSeconds,
                RideSessionClock.secondsAt(System.currentTimeMillis()),
                snapshot?.readings?.get(VideoMetric.DISTANCE)?.value,
            )
        } else {
            OverlayTrip()
        }
        val ranges = previewScales.update(preset, snapshot, unitSystem)
        return OverlaySceneInput(preset, snapshot, strings, unitSystem, width, height, trip, ranges, values)
    }

    /** The one thing the on-screen shutter and the volume keys both press. Only ever called from a press. */
    fun shutter() {
        val action = videoShutterAction(state.value.phase, state.value.ready, blocked = overlayHidden)
        if (action == VideoShutterAction.NONE || !shutterGuard.allow(SystemClock.elapsedRealtime())) return
        if (action == VideoShutterAction.START) {
            val microphoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            start(audioWanted && microphoneGranted)
        } else stop()
    }

    /** Free bytes where the video goes, or null when that cannot be read. */
    fun freeBytes(): Long? = runCatching {
        @Suppress("DEPRECATION") val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        StatFs((directory.takeIf { it.exists() } ?: Environment.getExternalStorageDirectory()).path).availableBytes
    }.getOrNull()

    /** The app's own stop when storage runs short; the file then finalizes with a minute's room for its index. */
    fun stopForStorage() {
        if (state.value.phase != VideoRecordingPhase.RECORDING) return
        Log.w(TAG, "Stopping: less than a minute of storage left")
        storageStopRequested = true
        stop()
    }

    private fun start(audio: Boolean) {
        if (!state.value.ready || !canEditVideoOverlay(state.value.phase) || recording != null) return
        // Never record with the overlay switched off for the editor's still: it would stay off for the whole file.
        if (overlayHidden) return
        storageStopRequested = false
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failed(SecurityException("Microphone permission required")); return
        }
        audioEnabled = audio
        ledger.reset()
        rotating = false
        rideStamp = "Volty_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}"
        // GLES bakes orientation into the output at bind time. Canvas can set MP4 rotation here.
        if (!useGlesHud) orientation.rotation?.let { capture?.targetRotation = it }
        mutableState.update { it.copy(phase = VideoRecordingPhase.STARTING, saved = null, savedParts = 0, interrupted = false, error = null, durationSeconds = 0, recordedBytes = 0, recordedNanos = 0, stopOutcome = null, stoppedForStorage = false) }
        finalization.recordingStarted()
        bufferedOverlay?.setRecordingActive(true)
        try {
            launchSegment()
        } catch (error: Exception) {
            bufferedOverlay?.setRecordingActive(false)
            finalization.finalized()
            failed(error)
        }
    }

    /** Starts the next (or only) file of the current ride. Throws if the recorder cannot be prepared. */
    private fun launchSegment() {
        val recorder = capture?.output ?: throw IllegalStateException("Camera is not bound")
        val name = videoSegmentName(rideStamp, ledger.partIndex, segmentPolicy.enabled) + ".mp4"
        var file: File? = null
        val pending = if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Volty")
            }
            recorder.prepareRecording(context, MediaStoreOutputOptions.Builder(context.contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(values).build())
        } else {
            @Suppress("DEPRECATION") val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Volty")
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create video directory" }
            file = File(directory, name)
            recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
        }
        val segment = Segment(file)
        val started = (if (audioEnabled) pending.withAudioEnabled() else pending)
            .start(ContextCompat.getMainExecutor(context)) { event -> onEvent(segment, event) }
        segment.recording = started
        recording = started
        current = segment
    }

    private fun onEvent(segment: Segment, event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> if (segment === current) {
                overlayRecording = true
                mutableState.update {
                    it.copy(phase = if (it.phase == VideoRecordingPhase.STOPPING) VideoRecordingPhase.STOPPING else VideoRecordingPhase.RECORDING)
                }
            }
            is VideoRecordEvent.Status -> if (segment === current) {
                val nanos = event.recordingStats.recordedDurationNanos
                val totalSeconds = ledger.totalNanos(nanos) / 1_000_000_000
                recordedSeconds = totalSeconds
                val bytes = event.recordingStats.numBytesRecorded
                mutableState.update { it.copy(durationSeconds = totalSeconds, recordedBytes = bytes, recordedNanos = nanos) }
                if (state.value.phase == VideoRecordingPhase.RECORDING && segmentPolicy.shouldRotate(nanos, rotating)) rotate(segment)
            }
            is VideoRecordEvent.Finalize -> finalizeSegment(segment, event)
        }
    }

    /**
     * Ends [old] and starts the next file at once. CameraX queues a recording started while the previous one is
     * stopping, so the stop MUST be requested first (starting while one is active throws); the queued start runs
     * as soon as the recorder has finalized and re-configured. That wait is the gap in picture and sound: it is
     * CameraX's, it has not been measured, and it is why segmentation defaults to off.
     */
    private fun rotate(old: Segment) {
        rotating = true
        flushFrames {
            // A manual stop may have superseded this queued rotation.
            if (current !== old || state.value.phase != VideoRecordingPhase.RECORDING || disposed) {
                rotating = false
                return@flushFrames
            }
            old.rotatedOut = true
            old.recording?.stop()
            ledger.nextPart()
            finalization.segmentStarted()
            try {
                launchSegment()
            } catch (error: Exception) {
                finalization.finalized()
                old.rotatedOut = false
                rotating = false
                current = old
                failed(error)
            }
        }
    }

    fun stop() {
        if (state.value.phase == VideoRecordingPhase.IDLE || state.value.phase == VideoRecordingPhase.STOPPING) return
        mutableState.update { it.copy(phase = VideoRecordingPhase.STOPPING) }
        val stopping = recording
        flushFrames { if (recording === stopping) stopping?.stop() }
    }

    private fun flushFrames(completed: () -> Unit) {
        val overlay = bufferedOverlay
        if (overlay == null) completed()
        else overlay.flush { ContextCompat.getMainExecutor(context).execute(completed) }
    }

    fun closeScreen(onClosed: () -> Unit) {
        // The Activity may leave immediately. dispose() keeps the recorder/effect alive until every Finalize.
        stop()
        onClosed()
    }

    private fun finalizeSegment(segment: Segment, event: VideoRecordEvent.Finalize) {
        segment.recording?.close()
        ledger.segmentFinished(event.recordingStats.recordedDurationNanos)
        val file = segment.file
        val uri = if (file != null) Uri.fromFile(file) else event.outputResults.outputUri
        val recoverable = event.error in setOf(VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE)
        // CameraX VideoRecordEvent.java documents output for limits/source-inactive and for
        // storage exhaustion DURING recording. Starting without frames/output must still fail.
        val outcome = videoOutputStatus(!event.hasError(), recoverable,
            if (file != null) file.isFile && file.length() > 0 else uri != Uri.EMPTY,
            event.recordingStats.recordedDurationNanos)
        if (outcome == VideoOutputStatus.DISCARD) {
            file?.delete()
            if (uri != Uri.EMPTY) runCatching { context.contentResolver.delete(uri, null, null) }
        } else if (file != null) {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }

        if (segment.rotatedOut) {
            // A middle file of a split ride. The ride goes on in the next one, so this is no end: the phase,
            // the camera and the close request are untouched, and a bad segment costs only itself.
            if (outcome == VideoOutputStatus.DISCARD) {
                Log.w(TAG, "Segment discarded (error ${event.error}): ${event.cause?.message.orEmpty()}")
            } else {
                mutableState.update { it.copy(saved = uri, savedParts = it.savedParts + 1) }
            }
            rotating = false
            finalization.finalized()
            if (!disposed) sessionLifecycle.finalized()
            return
        }

        recording = null
        current = null
        bufferedOverlay?.setRecordingActive(false)
        overlayRecording = false
        recordedSeconds = 0
        val forStorage = storageStopRequested
        storageStopRequested = false
        if (outcome == VideoOutputStatus.DISCARD) {
            mutableState.update { it.copy(phase = VideoRecordingPhase.IDLE, saved = it.saved.takeIf { _ -> it.savedParts > 0 },
                error = "${event.error}: ${event.cause?.message.orEmpty()}",
                stopOutcome = videoStopOutcome(it.cameraFailed, discarded = true, interrupted = false, stoppedForStorage = forStorage),
                stoppedForStorage = forStorage) }
        } else {
            // A camera failure keeps its error: the file being fine says nothing about the camera (I1).
            mutableState.update { it.copy(phase = VideoRecordingPhase.IDLE, saved = uri, savedParts = it.savedParts + 1,
                interrupted = outcome == VideoOutputStatus.INTERRUPTED || forStorage,
                error = if (it.cameraFailed) it.error else null,
                stopOutcome = videoStopOutcome(it.cameraFailed, discarded = false, interrupted = outcome == VideoOutputStatus.INTERRUPTED, stoppedForStorage = forStorage),
                stoppedForStorage = forStorage) }
        }
        rotating = false
        finalization.finalized()
        if (!disposed) {
            sessionLifecycle.finalized()
            if (useGlesHud && sessionLifecycle.foreground) orientation.rotation?.let(::applyFileRotation)
        }
    }

    private fun failed(error: Throwable) {
        Log.w(TAG, "Camera failed", error)
        mutableState.update { it.copy(ready = false, cameraFailed = true, error = error.message ?: error.javaClass.simpleName) }
        if (recording != null) stop() else mutableState.update { it.copy(phase = VideoRecordingPhase.IDLE) }
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        generation++
        // stop/close only REQUEST finalization. Keep the stream and GL thread until the
        // Finalize callback, including when Android destroys the Activity after onStop.
        finalization.close()
    }

    private fun releaseResources() {
        stopSensors()
        unbindSession()
        overlayThread.quitSafely()
    }

    private fun unbindSession() {
        generation++
        provider?.unbind(*listOfNotNull(preview, capture).toTypedArray())
        preview = null
        capture = null
        // close queues GL resource disposal onto the supplied handler before quitSafely.
        bufferedOverlay?.close(); bufferedOverlay = null
        previewClock.reset()
    }

    private companion object {
        const val TAG = "RideVideo"
        const val AUDIO_KEY = "audio_enabled"
        const val QUALITY_KEY = "quality_height"
        const val FPS_KEY = "preferred_fps"
        const val STABILIZATION_KEY = "stabilization_mode"
    }
}

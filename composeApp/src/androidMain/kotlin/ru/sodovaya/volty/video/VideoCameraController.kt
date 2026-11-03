package ru.sodovaya.volty.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
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
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraEffect
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
@OptIn(ExperimentalCamera2Interop::class)
internal class VideoCameraController(private val context: Context, private val owner: LifecycleOwner) {
    private val mutableState = MutableStateFlow(VideoCameraState())
    val state = mutableState.asStateFlow()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var current: Segment? = null
    private var effects: List<OverlayEffect> = emptyList()
    private var view: PreviewView? = null
    // The file's overlay has its own GL thread. The preview's is drawn in the view tree (see [previewInput]).
    private val overlayThread = HandlerThread("RideVideoOverlay").apply { start() }
    private val handler = Handler(overlayThread.looper)
    private val timeline = VideoTimeline()
    private val videoRenderer = VideoOverlayRenderer(context)
    // The preview overlay's own trackers, main thread only: they must not share state with the GL thread's.
    private val previewScales = OverlayScaleTracker()
    private val previewTrips = VideoTripTracker()
    private val previewDials = ru.sodovaya.volty.presentation.video.VideoDialSmoother()
    private var storageStopRequested = false
    /** True while the editor takes a still, so the frame it gets has no old overlay burned into it. */
    @Volatile var overlayHidden = false
    @Volatile private var recordedSeconds = 0L
    @Volatile private var overlayRecording = false
    private val shutterGuard = VideoShutterGuard()
    /** The microphone choice, from the screen; the volume keys need it as much as the shutter does. */
    @Volatile var audioWanted = false
    @Volatile var preset: OverlayPreset = OverlayTemplates.hud()
    /** C3's time and distance: per recording by default, a user setting. */
    @Volatile var tripScope: OverlayTripScope = OverlayTripScope.RECORDING
    /** Localized words and units; set from the screen, which owns the resources. */
    @Volatile var strings: VideoOverlayStrings = VideoOverlayStrings()
    @Volatile var unitSystem: UnitSystem = UnitSystem.METRIC
    private var disposed = false
    private var generation = 0
    private var afterFinalize: (() -> Unit)? = null
    private val finalization = VideoFinalizationGate(requestStop = ::stop, release = ::releaseResources)

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
        previewView.previewStreamState.observe(owner) { stream ->
            if (stream != PreviewView.StreamState.STREAMING) return@observe
            val info = preview?.resolutionInfo ?: return@observe
            val aspect = videoPreviewAspect(info.cropRect.width(), info.cropRect.height(), info.rotationDegrees)
            if (aspect != state.value.previewAspect) mutableState.update { it.copy(previewAspect = aspect) }
        }
        startSensors()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            runCatching { provider = future.get(); bind() }.onFailure(::failed)
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCamera() {
        if (!canEditVideoOverlay(state.value.phase) || !state.value.canSwitch) return
        mutableState.value = state.value.copy(front = !state.value.front)
        bind()
    }

    fun setQuality(quality: Quality) {
        if (!canEditVideoOverlay(state.value.phase) || quality !in state.value.qualities) return
        mutableState.value = state.value.copy(quality = quality)
        bind()
    }

    /** The display rotation changed: only the preview follows it. The file's rotation comes from the sensor. */
    fun updateRotation(rotation: Int) {
        if (!canEditVideoOverlay(state.value.phase)) return
        preview?.targetRotation = rotation
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
                    if (canEditVideoOverlay(state.value.phase)) capture?.targetRotation = changed
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
     * each then receives its own target's `Frame.rotationDegrees`. The cost is a second raster at the preview's size
     * on its own thread; the recorded one is capped by [OverlayRepaintLimiter] and the preview is not.
     */
    private inner class OverlayPipeline(
        private val recorded: Boolean,
        private val renderer: VideoOverlayRenderer,
        realtime: Boolean,
    ) {
        private val clock = VideoFrameClock(realtime)
        private val gate = OverlayDrawGate()
        private val limiter = OverlayRepaintLimiter()
        private val scales = OverlayScaleTracker()
        private val trips = VideoTripTracker()
        private val dials = ru.sodovaya.volty.presentation.video.VideoDialSmoother()
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
        fun onDraw(frame: Frame): Boolean {
            val drawn = guard.attempt { draw(frame) }
            if (!drawn) gate.invalidate()
            return true
        }

        private fun draw(frame: Frame) {
            if (recorded) meter.onFrame(frame.timestampNanos)?.let { rate ->
                if (rate != state.value.frameRate) mutableState.update { it.copy(frameRate = rate) }
            }
            val now = SystemClock.elapsedRealtime()
            val time = clock.frameMillis(frame.timestampNanos, now)
            if (clock.timebaseMismatch && !clockReported) {
                clockReported = true
                Log.w(TAG, "Camera claims a REALTIME timebase but its frames disagree; estimating the offset instead")
                mutableState.update { it.copy(clockMismatch = true) }
            }
            val snapshot = synchronized(timeline) { timeline.sample(time) }
            if (overlayHidden) {
                renderer.clear(frame.overlayCanvas)
                gate.invalidate()
                return
            }
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
            val smoothed = dials.advance(snapshot, time)
            val key = overlayDrawKey(preset, snapshot, strings, unitSystem, geometryOf(frame), trip = trip, scales = ranges, dials = smoothed)
            // Touching the canvas costs a software raster plus a full-size upload; skipping it leaves the previous
            // overlay in place, which is correct whenever the key has not changed.
            if (!gate.wouldDraw(key)) return
            // While a file is being written its overlay repaints at most 15 times a second (I6); a held-back change
            // is drawn on a later frame, since the gate has not recorded it.
            if (!limiter.allow(now, limited = recorded && overlayRecording)) return
            gate.shouldDraw(key)
            val canvas = frame.overlayCanvas
            try {
                renderer.draw(frame, canvas, preset, snapshot, strings, unitSystem, trip, ranges, smoothed)
            } catch (error: Exception) {
                runCatching { renderer.clear(canvas) }
                throw error
            }
        }
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val previewView = view ?: return
        if (disposed || !canEditVideoOverlay(state.value.phase)) return
        val token = ++generation
        mutableState.update { it.copy(ready = false, error = null, overlayFailed = false, clockMismatch = false) }
        try {
            val selector = if (state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val info = cameraProvider.getCameraInfo(selector)
            val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            require(qualities.isNotEmpty()) { "No supported video quality" }
            val quality = state.value.quality.takeIf { it in qualities } ?: qualities.first()
            cameraProvider.unbind(*listOfNotNull(preview, capture).toTypedArray())
            effects.forEach { it.close() }
            val realtime = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val onError = { error: Throwable ->
                ContextCompat.getMainExecutor(context).execute { if (token == generation && !disposed) failed(error) }
            }
            // A new effect has a new, empty overlay surface: its own gate and guard, never the previous bind's.
            val videoEffect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, handler, onError)
            val videoPipeline = OverlayPipeline(recorded = true, renderer = videoRenderer, realtime = realtime)
            videoEffect.setOnDrawListener(videoPipeline::onDraw)
            effects = listOf(videoEffect)
            val display = displayRotation()
            val nextPreview = Preview.Builder().setTargetRotation(display)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
            val nextCapture = VideoCapture.Builder(recorder).setTargetRotation(orientation.rotation ?: display)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
            preview = nextPreview
            capture = nextCapture
            // Preview uses FIT_CENTER, so the whole encoded composition is visible in either aspect ratio.
            cameraProvider.bindToLifecycle(owner, selector, UseCaseGroup.Builder()
                .addUseCase(nextPreview).addUseCase(nextCapture).addEffect(videoEffect).build())
            mutableState.update { it.copy(ready = true, cameraFailed = false, qualities = qualities, quality = quality,
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
        val snapshot = synchronized(timeline) { timeline.sample(nowMillis) }
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
        val smoothed = previewDials.advance(snapshot, nowMillis)
        return OverlaySceneInput(preset, snapshot, strings, unitSystem, width, height, trip, ranges, smoothed)
    }

    /** The one thing the on-screen shutter and the volume keys both press. Only ever called from a press. */
    fun shutter() {
        val action = videoShutterAction(state.value.phase, state.value.ready, blocked = overlayHidden)
        if (action == VideoShutterAction.NONE || !shutterGuard.allow(SystemClock.elapsedRealtime())) return
        if (action == VideoShutterAction.START) start(audioWanted) else stop()
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
        // The file's rotation is fixed when a recording starts: take the sensor's latest word now.
        orientation.rotation?.let { capture?.targetRotation = it }
        mutableState.update { it.copy(phase = VideoRecordingPhase.STARTING, saved = null, savedParts = 0, interrupted = false, error = null, durationSeconds = 0, recordedBytes = 0, recordedNanos = 0, stopOutcome = null, stoppedForStorage = false) }
        finalization.recordingStarted()
        try {
            launchSegment()
        } catch (error: Exception) {
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
        old.rotatedOut = true
        old.recording?.stop()
        ledger.nextPart()
        finalization.segmentStarted()
        try {
            launchSegment()
        } catch (error: Exception) {
            // The ride ends with what the old file holds; make its Finalize an ordinary end again.
            finalization.finalized()
            old.rotatedOut = false
            rotating = false
            current = old
            failed(error)
        }
    }

    fun stop() {
        if (state.value.phase == VideoRecordingPhase.IDLE || state.value.phase == VideoRecordingPhase.STOPPING) return
        mutableState.update { it.copy(phase = VideoRecordingPhase.STOPPING) }
        recording?.stop()
    }

    fun closeWhenFinalized(onClosed: () -> Unit) {
        if (recording == null) onClosed() else { afterFinalize = onClosed; stop() }
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
            return
        }

        recording = null
        current = null
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
        if (disposed) {
            afterFinalize = null
        }
        finalization.finalized()
        if (!disposed) afterFinalize?.also { afterFinalize = null; it() }
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
        provider?.unbind(*listOfNotNull(preview, capture).toTypedArray())
        preview = null
        capture = null
        // close queues GL resource disposal onto the supplied handler before quitSafely.
        effects.forEach { it.close() }; effects = emptyList()
        overlayThread.quitSafely()
    }

    private companion object {
        const val TAG = "RideVideo"
    }
}

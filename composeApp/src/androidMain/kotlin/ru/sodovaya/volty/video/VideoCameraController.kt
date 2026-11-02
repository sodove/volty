package ru.sodovaya.volty.video

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraEffect
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.sodovaya.volty.domain.video.*
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
    val interrupted: Boolean = false,
    val error: String? = null,
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
    private var effect: OverlayEffect? = null
    private var view: PreviewView? = null
    private val overlayThread = HandlerThread("RideVideoOverlay").apply { start() }
    private val handler = Handler(overlayThread.looper)
    private val timeline = VideoTimeline()
    private val renderer = VideoOverlayRenderer()
    @Volatile var preset: OverlayPreset = OverlayTemplates.hud()
    private var disposed = false
    private var generation = 0
    private var afterFinalize: (() -> Unit)? = null
    private var pendingFile: File? = null
    private val finalization = VideoFinalizationGate(requestStop = ::stop, release = ::releaseResources)

    fun offer(snapshot: VideoSnapshot?) = synchronized(timeline) {
        if (snapshot == null) timeline.clear() else timeline.offer(snapshot)
    }

    fun attach(previewView: PreviewView) {
        view = previewView
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

    fun updateRotation(rotation: Int) {
        if (!canEditVideoOverlay(state.value.phase)) return
        preview?.targetRotation = rotation
        capture?.targetRotation = rotation
    }

    private fun bind() {
        val cameraProvider = provider ?: return
        val previewView = view ?: return
        if (disposed || !canEditVideoOverlay(state.value.phase)) return
        val token = ++generation
        mutableState.value = state.value.copy(ready = false, error = null)
        try {
            val selector = if (state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val info = cameraProvider.getCameraInfo(selector)
            val qualities = Recorder.getVideoCapabilities(info).getSupportedQualities(androidx.camera.core.DynamicRange.SDR)
            require(qualities.isNotEmpty()) { "No supported video quality" }
            val quality = state.value.quality.takeIf { it in qualities } ?: qualities.first()
            cameraProvider.unbind(*listOfNotNull(preview, capture).toTypedArray())
            effect?.close()
            val realtime = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
            val clock = VideoFrameClock(realtime)
            val nextEffect = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, handler) { error ->
                ContextCompat.getMainExecutor(context).execute { if (token == generation && !disposed) failed(error) }
            }
            nextEffect.setOnDrawListener { frame ->
                val now = SystemClock.elapsedRealtime()
                val time = clock.frameMillis(frame.timestampNanos, now)
                val snapshot = synchronized(timeline) { timeline.sample(time) }
                renderer.draw(frame, preset, snapshot)
                true
            }
            effect = nextEffect
            @Suppress("DEPRECATION")
            val rotation = previewView.display?.rotation
                ?: (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
            val nextPreview = Preview.Builder().setTargetRotation(rotation)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9).build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val recorder = Recorder.Builder().setQualitySelector(QualitySelector.from(quality)).build()
            val nextCapture = VideoCapture.Builder(recorder).setTargetRotation(rotation)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY).build()
            preview = nextPreview
            capture = nextCapture
            // Preview uses FIT_CENTER, so the whole encoded composition is visible in either aspect ratio.
            cameraProvider.bindToLifecycle(owner, selector, UseCaseGroup.Builder()
                .addUseCase(nextPreview).addUseCase(nextCapture).addEffect(nextEffect).build())
            mutableState.value = state.value.copy(ready = true, qualities = qualities, quality = quality,
                canSwitch = cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) && cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
        } catch (error: Exception) { failed(error) }
    }

    fun start(audio: Boolean) {
        if (!state.value.ready || !canEditVideoOverlay(state.value.phase) || recording != null) return
        val recorder = capture?.output ?: return
        if (audio && ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            failed(SecurityException("Microphone permission required")); return
        }
        mutableState.value = state.value.copy(phase = VideoRecordingPhase.STARTING, saved = null, interrupted = false, error = null, durationSeconds = 0)
        finalization.recordingStarted()
        try {
            val name = "Volty_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}.mp4"
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
                val file = File(directory, name).also { pendingFile = it }
                recorder.prepareRecording(context, FileOutputOptions.Builder(file).build())
            }
            recording = (if (audio) pending.withAudioEnabled() else pending).start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> mutableState.value = state.value.copy(
                        phase = if (state.value.phase == VideoRecordingPhase.STOPPING) VideoRecordingPhase.STOPPING else VideoRecordingPhase.RECORDING)
                    is VideoRecordEvent.Status -> mutableState.value = state.value.copy(
                        durationSeconds = event.recordingStats.recordedDurationNanos / 1_000_000_000)
                    is VideoRecordEvent.Finalize -> finalize(event)
                }
            }
        } catch (error: Exception) {
            pendingFile?.delete(); pendingFile = null
            finalization.finalized()
            failed(error)
        }
    }

    fun stop() {
        if (state.value.phase == VideoRecordingPhase.IDLE || state.value.phase == VideoRecordingPhase.STOPPING) return
        mutableState.value = state.value.copy(phase = VideoRecordingPhase.STOPPING)
        recording?.stop()
    }

    fun closeWhenFinalized(onClosed: () -> Unit) {
        if (recording == null) onClosed() else { afterFinalize = onClosed; stop() }
    }

    private fun finalize(event: VideoRecordEvent.Finalize) {
        recording?.close(); recording = null
        val file = pendingFile.also { pendingFile = null }
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
            mutableState.value = state.value.copy(phase = VideoRecordingPhase.IDLE, saved = null,
                error = "${event.error}: ${event.cause?.message.orEmpty()}")
        } else {
            mutableState.value = state.value.copy(phase = VideoRecordingPhase.IDLE, saved = uri,
                interrupted = outcome == VideoOutputStatus.INTERRUPTED, error = null)
            if (file != null) MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        }
        if (disposed) {
            afterFinalize = null
        }
        finalization.finalized()
        if (!disposed) afterFinalize?.also { afterFinalize = null; it() }
    }

    private fun failed(error: Throwable) {
        mutableState.value = state.value.copy(ready = false, error = error.message ?: error.javaClass.simpleName)
        if (recording != null) stop() else mutableState.value = state.value.copy(phase = VideoRecordingPhase.IDLE)
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
        provider?.unbind(*listOfNotNull(preview, capture).toTypedArray())
        preview = null
        capture = null
        // close queues GL resource disposal onto the supplied handler before quitSafely.
        effect?.close(); effect = null
        overlayThread.quitSafely()
    }
}

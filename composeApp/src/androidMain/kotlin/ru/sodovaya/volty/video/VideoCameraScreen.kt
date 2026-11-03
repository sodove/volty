package ru.sodovaya.volty.video

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import java.util.UUID
import ru.sodovaya.volty.domain.model.Vehicle
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.presentation.video.VideoOverlayComponent
import volty.composeapp.generated.resources.*

@Composable
internal fun VideoCameraScreen(
    camera: VideoCameraController,
    component: VideoOverlayComponent,
    linkMonitor: VideoLinkMonitor,
    vehicle: StateFlow<Vehicle?>,
    onClose: () -> Unit,
    onEditing: (Boolean) -> Unit,
    onSegmentMinutes: (Int) -> Unit,
) {
    val context = LocalContext.current
    // Burned-in words come from the resources (localized), resolved here because the renderer runs on a camera
    // thread. The window flag in the activity keeps the screen on; this keeps it on for the view tree as well,
    // so the camera screen never sleeps whichever of the two a future change removes.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        val previous = rootView.keepScreenOn
        rootView.keepScreenOn = true
        onDispose { rootView.keepScreenOn = previous }
    }
    val overlayStrings = VideoOverlayStrings(
        labels = mapOf(
            VideoMetric.SPEED to stringResource(Res.string.video_label_speed),
            VideoMetric.DUTY to stringResource(Res.string.video_label_duty),
            VideoMetric.ESC_TEMPERATURE to stringResource(Res.string.video_label_esc_temperature),
            VideoMetric.MOTOR_TEMPERATURE to stringResource(Res.string.video_label_motor_temperature),
            VideoMetric.MOTOR_CURRENT to stringResource(Res.string.video_label_motor_current),
            VideoMetric.CONTROLLER_CURRENT to stringResource(Res.string.video_label_controller_current),
            VideoMetric.CONTROLLER_POWER to stringResource(Res.string.video_label_controller_power),
            VideoMetric.SOC to stringResource(Res.string.video_label_soc),
            VideoMetric.VOLTAGE to stringResource(Res.string.video_label_voltage),
            VideoMetric.BATTERY_CURRENT to stringResource(Res.string.video_label_battery_current),
            VideoMetric.BATTERY_POWER to stringResource(Res.string.video_label_battery_power),
            VideoMetric.BATTERY_TEMPERATURE to stringResource(Res.string.video_label_battery_temperature),
            VideoMetric.ELAPSED to stringResource(Res.string.video_label_elapsed),
            VideoMetric.DISTANCE to stringResource(Res.string.video_label_distance),
        ),
        percent = stringResource(Res.string.unit_percent),
        celsius = stringResource(Res.string.unit_celsius),
        volt = stringResource(Res.string.history_unit_volt),
        amp = stringResource(Res.string.history_unit_amp),
        watt = stringResource(Res.string.history_unit_watt),
        kmh = stringResource(Res.string.unit_kmh),
        demo = stringResource(Res.string.video_demo),
        km = stringResource(Res.string.video_unit_km),
        noLink = stringResource(Res.string.video_chip_no_link),
    )
    SideEffect { camera.strings = overlayStrings }
    val owner = LocalLifecycleOwner.current
    val permissions = remember {
        buildList { add(Manifest.permission.CAMERA); if (Build.VERSION.SDK_INT <= 28) add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.toTypedArray()
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    var allowed by remember { mutableStateOf(permissions.all(::granted)) }
    var requested by remember { mutableStateOf(false) }
    var audio by remember { mutableStateOf(false) }
    var audioDenied by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val document by component.document.collectAsState()
    val state by camera.state.collectAsState()
    val idle = canEditVideoOverlay(state.phase)
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        requested = true; allowed = permissions.all(::granted)
    }
    val audioRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audio = it; audioDenied = !it
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { allowed = permissions.all(::granted); if (!granted(Manifest.permission.RECORD_AUDIO)) audio = false }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    var confirmStop by remember { mutableStateOf(false) }
    // The orientation the picture was in when the recording started; see videoFrozenLandscape.
    // Plain memory, not state: it is derived during composition from the phase and the window, both already state.
    val frozenMemory = remember { arrayOfNulls<Boolean>(1) }
    var sheet by remember { mutableStateOf(VideoSheetKind.NONE) }
    var still by remember { mutableStateOf<ImageBitmap?>(null) }
    var pendingPreset by remember { mutableStateOf<OverlayPreset?>(null) }
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The editor's stage uses a real frame when one can be had. The overlay comes off first, or the old one would be
    // baked into the picture under the one being arranged; the camera itself goes away with the PreviewView.
    fun openEditor() {
        // From the first line: the overlay is about to go off, so no press may start a recording until the editor
        // is up (I3). The controller refuses a start while it is off as well.
        opening = true
        sheet = VideoSheetKind.NONE
        camera.overlayHidden = true
        scope.launch {
            delay(STILL_SETTLE_MILLIS)
            still = previewView?.bitmap?.asImageBitmap()
            editing = true
            opening = false
        }
    }
    val vehicleName = vehicle.collectAsState().value?.name.orEmpty()
    SideEffect { camera.audioWanted = audio; onEditing(editing || opening) }
    // One policy for the system Back and the on-screen one: a live recording is never ended by a single tap.
    fun requestBack() {
        if (pendingPreset != null) { pendingPreset = null; return }
        if (sheet != VideoSheetKind.NONE) { sheet = VideoSheetKind.NONE; return }
        when (videoBackAction(editing, state.phase, confirmStop && state.phase != VideoRecordingPhase.IDLE)) {
            VideoBackAction.DISMISS_CONFIRM -> confirmStop = false
            VideoBackAction.CLOSE_EDITOR -> editing = false
            VideoBackAction.CONFIRM_STOP -> confirmStop = true
            VideoBackAction.CLOSE_SCREEN -> onClose()
            VideoBackAction.IGNORE -> Unit
        }
    }
    BackHandler { requestBack() }
    VideoHaptics(context, state)
    if (editing && idle) {
        VideoOverlayEditor(document, overlayStrings, camera.unitSystem, still, component::change, onClose = {
            editing = false
            camera.overlayHidden = false
            still = null
        })
        return
    }
    // A recording takes every sheet down with it: only the stop is reachable while it runs.
    LaunchedEffect(idle) { if (!idle) sheet = VideoSheetKind.NONE }

    var freeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(camera) {
        while (true) {
            freeBytes = withContext(Dispatchers.IO) { camera.freeBytes() }
            delay(if (idle) STORAGE_REFRESH_MILLIS else STORAGE_REFRESH_RECORDING_MILLIS)
        }
    }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(STATUS_REFRESH_MILLIS)
        }
    }
    val height = videoQualityHeight(state.quality)
    val storage = videoStorageStatus(freeBytes, videoBitsPerSecond(height, audio, state.recordedBytes, state.recordedNanos))
    val chromeState = VideoChromeState(
        phase = state.phase,
        canShoot = allowed && !opening && (state.ready || !idle),
        canSwitch = idle && state.canSwitch,
        elapsedSeconds = state.durationSeconds,
        chips = videoChips(linkMonitor.status(now), vehicleName, storage),
        presetName = videoPresetButtonLabel(overlayPresetName(document.selected, document.saved)),
        resolution = videoQualityText(state.quality).removeSuffix("p"),
        frameRate = state.frameRate,
    )
    val actions = VideoChromeActions(
        onBack = ::requestBack,
        onShutter = camera::shutter,
        onSwitch = camera::switchCamera,
        onQuality = { sheet = VideoSheetKind.QUALITY },
        onOverlay = { sheet = VideoSheetKind.OVERLAY },
    )
    // I4: a storage alarm while recording, and the app's own stop with a minute in hand.
    val bitsPerSecond = videoBitsPerSecond(height, audio, state.recordedBytes, state.recordedNanos)
    var wasLow by remember { mutableStateOf(false) }
    LaunchedEffect(freeBytes, state.phase) {
        val recording = state.phase == VideoRecordingPhase.RECORDING
        val low = storage?.low == true
        if (videoStorageAlert(wasLow, low, recording)) vibrate(context, VideoHaptic.LOW_STORAGE)
        wasLow = low
        if (recording && videoStorageMustStop(freeBytes, bitsPerSecond)) camera.stopForStorage()
    }
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        savedVisible = state.saved != null
        if (savedVisible) { delay(SAVED_NOTICE_MILLIS); savedVisible = false }
    }
    val notices = videoCaptureNotices(VideoCaptureFacts(
        error = state.cameraFailed || state.stopOutcome == VideoStopOutcome.FAILED,
        overlayFailed = state.overlayFailed,
        audioDenied = audioDenied,
        clockMismatch = state.clockMismatch,
        // An interrupted file stays on screen until the next recording: it is news the rider did not ask for.
        saved = savedVisible || state.interrupted,
        savedParts = state.savedParts,
        interrupted = state.interrupted,
        stoppedForStorage = state.stoppedForStorage,
    )).map { noticeText(it, state.savedParts) }

    // The camera area is the scheme's lowest surface, so the letterbox round a 9:16 frame is the app's own ground
    // in light and dark alike, and the frame itself stays full-bleed inside it.
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        // In portrait the panel's colour runs on under the gesture bar, as in mock B1: a black strip below the panel read
        // as a seam (visual check 2026-11-03). Nothing is placed there; it is paint only.
        if (!videoCaptureIsLandscape(maxWidth.value, maxHeight.value)) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(MaterialTheme.colorScheme.surfaceContainer))
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val landscape = videoCaptureIsLandscape(maxWidth.value, maxHeight.value)
            val frozenLandscape = videoFrozenLandscape(state.phase, frozenMemory[0], landscape).also { frozenMemory[0] = it }
            // One tree for both orientations: moving the PreviewView to another parent would rebind the camera.
            Box(
                Modifier.fillMaxSize().padding(end = if (landscape) VideoChromeSize else 0.dp, bottom = if (landscape) 0.dp else VideoChromeSize),
                contentAlignment = Alignment.Center,
            ) {
                if (allowed) {
                    // PreviewView paints its own black round a fitted frame; it takes the scheme's ground instead, so the
                    // letterbox is the app's in a light theme too (emulator check 2026-11-03).
                    val letterbox = MaterialTheme.colorScheme.surfaceContainerLowest.toArgb()
                    AndroidView(factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            previewView = this
                            camera.attach(this)
                        }
                    }, update = { it.setBackgroundColor(letterbox) }, modifier = Modifier.fillMaxSize())
                    // Over a real picture only, and while recording in the orientation the picture is frozen in (I2).
                    if (videoPreviewOverlayShown(state.ready, state.cameraFailed, state.previewAspect)) {
                        PreviewOverlay(camera, videoPreviewDisplayedAspect(state.previewAspect, landscape, frozenLandscape), Modifier.align(Alignment.Center))
                    }
                } else {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.video_permissions), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Button(onClick = { permissionRequest.launch(permissions) }) { Text(stringResource(Res.string.video_allow)) }
                        if (requested) TextButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }) { Text(stringResource(Res.string.video_app_settings)) }
                    }
                }
                VideoNotices(
                    notices,
                    showRetry = state.cameraFailed && idle && allowed,
                    onRetry = { previewView?.let(camera::attach) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            if (landscape) {
                VideoCaptureRail(chromeState, actions, Modifier.align(Alignment.CenterEnd))
            } else {
                VideoCapturePanel(chromeState, actions, Modifier.align(Alignment.BottomCenter))
            }
            when (sheet) {
                VideoSheetKind.QUALITY -> VideoQualitySheet(
                    state, audio,
                    onQuality = camera::setQuality,
                    onAudio = { enabled ->
                        if (!enabled) audio = false else if (granted(Manifest.permission.RECORD_AUDIO)) audio = true
                        else audioRequest.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSegments = onSegmentMinutes,
                    onDismiss = { sheet = VideoSheetKind.NONE },
                )
                VideoSheetKind.OVERLAY -> VideoOverlaySheet(
                    document,
                    onPreset = { preset ->
                        if (overlayPresetSwitchLosesEdits(document) && preset != document.selected) pendingPreset = preset
                        else component.change(document.copy(selected = preset))
                    },
                    onTripScope = { component.change(document.copy(tripScope = it)) },
                    onCustomize = ::openEditor,
                    onDismiss = { sheet = VideoSheetKind.NONE },
                )
                VideoSheetKind.NONE -> Unit
            }
            pendingPreset?.let { preset ->
                val copyName = stringResource(Res.string.video_editor_copy_name, overlayPresetName(document.selected, document.saved))
                val canKeep = document.saved.size < OVERLAY_MAX_SAVED
                VideoDecisionSheet(
                    title = stringResource(Res.string.video_editor_lose_title),
                    text = stringResource(Res.string.video_editor_lose_text),
                    primary = stringResource(Res.string.video_editor_lose_save).takeIf { canKeep },
                    onPrimary = {
                        val kept = overlayKeepAsCopy(document, UUID.randomUUID().toString(), copyName)
                        if (kept != null && component.change(kept.copy(selected = preset))) pendingPreset = null
                    },
                    destructive = stringResource(Res.string.video_editor_lose_discard),
                    onDestructive = { component.change(document.copy(selected = preset)); pendingPreset = null },
                    neutral = stringResource(Res.string.video_editor_lose_keep),
                    onNeutral = { pendingPreset = null },
                )
            }
            if (confirmStop && state.phase != VideoRecordingPhase.IDLE) {
                VideoBackConfirm(
                    onContinue = { confirmStop = false },
                    onStop = { confirmStop = false; camera.stop() },
                )
            }
        }
    }
}

private enum class VideoSheetKind { NONE, QUALITY, OVERLAY }

/**
 * The preview's overlay, in the view tree over the PreviewView and fitted to the displayed frame as FIT_CENTER fits
 * the picture. It is the same scene builder and drawer the file's GL effect uses, so what is on the frame is what is
 * burned in; only the chrome around the frame stays out of the file. See `VideoCameraController.previewInput`.
 */
@Composable
private fun PreviewOverlay(camera: VideoCameraController, aspect: Float, modifier: Modifier) {
    val context = LocalContext.current
    val drawer = remember { OverlayCanvasDrawer(OverlayFonts(context)) }
    val now by produceState(SystemClock.elapsedRealtime()) {
        while (true) {
            value = SystemClock.elapsedRealtime()
            delay(VIDEO_PREVIEW_OVERLAY_MILLIS)
        }
    }
    // Clipped: the scrim is drawn wider than its frame (OverlayCanvasDrawer), harmless on a camera surface but it
    // spilled across the letterbox in the view tree (emulator check 2026-11-03).
    Canvas(modifier.aspectRatio(aspect).fillMaxSize().clipToBounds()) {
        val input = camera.previewInput(size.width.toInt(), size.height.toInt(), now) ?: return@Canvas
        val scene = buildOverlayScene(input, drawer)
        drawIntoCanvas { drawer.draw(it.nativeCanvas, scene) }
    }
}

private const val STATUS_REFRESH_MILLIS = 500L
private const val STORAGE_REFRESH_MILLIS = 5_000L
private const val STORAGE_REFRESH_RECORDING_MILLIS = 2_000L
private const val SAVED_NOTICE_MILLIS = 6_000L
private const val STILL_SETTLE_MILLIS = 300L

/** Pulses follow the recorder's own state, so the rider feels what happened, not what was pressed. */
@Composable
private fun VideoHaptics(context: android.content.Context, state: VideoCameraState) {
    val previous = remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        val haptic = videoHapticFor(previous.value, state.phase, state.stopOutcome)
        previous.value = state.phase
        haptic?.let { vibrate(context, it) }
    }
}

private fun vibrate(context: android.content.Context, haptic: VideoHaptic) {
    val vibrator = context.getSystemService(Vibrator::class.java) ?: return
    when (haptic) {
        VideoHaptic.STARTED -> vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        VideoHaptic.STOPPED -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1))
        VideoHaptic.FAILED -> vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
        VideoHaptic.LOW_STORAGE -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60, 80, 60), -1))
    }
}

@Composable
private fun videoChips(link: VideoLinkStatus, vehicleName: String, storage: VideoStorageStatus?): VideoChips {
    val (linkText, linkTone) = when (link) {
        VideoLinkStatus.NoVehicle -> stringResource(Res.string.video_link_none) to ChipTone.WARN
        VideoLinkStatus.Waiting -> stringResource(Res.string.video_link_waiting) to ChipTone.WARN
        is VideoLinkStatus.Live -> stringResource(Res.string.video_link_live, vehicleName, videoAgeSeconds(link.ageMillis)) to ChipTone.GOOD
        is VideoLinkStatus.Lost -> stringResource(Res.string.video_link_lost, videoElapsedText(link.sinceMillis / 1000)) to ChipTone.BAD
    }
    val linkShort = when (link) {
        VideoLinkStatus.NoVehicle -> stringResource(Res.string.video_link_none_short)
        VideoLinkStatus.Waiting -> stringResource(Res.string.video_link_waiting_short)
        is VideoLinkStatus.Live -> stringResource(Res.string.video_link_live_short, videoAgeSeconds(link.ageMillis))
        is VideoLinkStatus.Lost -> stringResource(Res.string.video_link_lost_short, videoElapsedText(link.sinceMillis / 1000))
    }
    val time = storage?.let {
        val hours = it.minutesLeft / 60
        if (hours > 0) stringResource(Res.string.video_storage_hours, hours.toInt(), (it.minutesLeft % 60).toInt())
        else stringResource(Res.string.video_storage_minutes, it.minutesLeft.toInt())
    }
    val size = storage?.let {
        val value = videoStorageSize(it.freeBytes)
        stringResource(if (value.gigabytes) Res.string.video_storage_gb else Res.string.video_storage_mb, value.text)
    }
    return VideoChips(linkText, videoRailLinkText(linkText, linkShort), linkTone, if (size != null && time != null) "$size · $time" else null, time, storage?.low == true)
}

@Composable
private fun noticeText(notice: VideoCaptureNotice, savedParts: Int): String = when (notice) {
    VideoCaptureNotice.ERROR -> stringResource(Res.string.video_error)
    VideoCaptureNotice.STORAGE_STOPPED -> stringResource(Res.string.video_stopped_storage)
    VideoCaptureNotice.OVERLAY_FAILED -> stringResource(Res.string.video_overlay_failed)
    VideoCaptureNotice.AUDIO_DENIED -> stringResource(Res.string.video_audio_denied)
    VideoCaptureNotice.CLOCK_MISMATCH -> stringResource(Res.string.video_clock_mismatch)
    VideoCaptureNotice.INTERRUPTED -> stringResource(Res.string.video_saved_interrupted)
    VideoCaptureNotice.SAVED_PARTS -> stringResource(Res.string.video_saved_parts, savedParts)
    VideoCaptureNotice.SAVED -> stringResource(Res.string.video_saved)
}

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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
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
import kotlinx.coroutines.withContext
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
    var sheet by remember { mutableStateOf(VideoSheetKind.NONE) }
    val vehicleName = vehicle.collectAsState().value?.name.orEmpty()
    SideEffect { camera.audioWanted = audio; onEditing(editing) }
    // One policy for the system Back and the on-screen one: a live recording is never ended by a single tap.
    fun requestBack() {
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
        VideoOverlayEditor(document, component::change, onClose = { editing = false })
        return
    }
    // A recording takes every sheet down with it: only the stop is reachable while it runs.
    LaunchedEffect(idle) { if (!idle) sheet = VideoSheetKind.NONE }

    var freeBytes by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(camera) {
        while (true) {
            freeBytes = withContext(Dispatchers.IO) { camera.freeBytes() }
            delay(STORAGE_REFRESH_MILLIS)
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
        canShoot = allowed && (state.ready || !idle),
        canSwitch = idle && state.canSwitch,
        elapsedSeconds = state.durationSeconds,
        chips = videoChips(linkMonitor.status(now), vehicleName, storage),
        presetName = overlayPresetName(document.selected, document.saved),
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
    var savedVisible by remember { mutableStateOf(false) }
    LaunchedEffect(state.saved) {
        savedVisible = state.saved != null
        if (savedVisible) { delay(SAVED_NOTICE_MILLIS); savedVisible = false }
    }
    val notices = videoCaptureNotices(VideoCaptureFacts(
        error = state.error != null,
        overlayFailed = state.overlayFailed,
        audioDenied = audioDenied,
        clockMismatch = state.clockMismatch,
        saved = savedVisible,
        savedParts = state.savedParts,
        interrupted = state.interrupted,
    )).map { noticeText(it, state.savedParts) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        val landscape = videoCaptureIsLandscape(maxWidth.value, maxHeight.value)
        // One tree for both orientations: moving the PreviewView to another parent would rebind the camera.
        Box(
            Modifier.fillMaxSize().padding(end = if (landscape) VideoChromeSize else 0.dp, bottom = if (landscape) 0.dp else VideoChromeSize),
            contentAlignment = Alignment.Center,
        ) {
            if (allowed) {
                AndroidView(factory = { viewContext ->
                    PreviewView(viewContext).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                        previewView = this
                        camera.attach(this)
                    }
                }, modifier = Modifier.fillMaxSize())
            } else {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(Res.string.video_permissions), color = Color.White)
                    Button(onClick = { permissionRequest.launch(permissions) }) { Text(stringResource(Res.string.video_allow)) }
                    if (requested) TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(Res.string.video_app_settings)) }
                }
            }
            VideoNotices(
                notices,
                showRetry = state.error != null && idle && allowed,
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
                onPreset = { component.change(document.copy(selected = it)) },
                onTripScope = { component.change(document.copy(tripScope = it)) },
                onCustomize = { sheet = VideoSheetKind.NONE; editing = true },
                onDismiss = { sheet = VideoSheetKind.NONE },
            )
            VideoSheetKind.NONE -> Unit
        }
        if (confirmStop && state.phase != VideoRecordingPhase.IDLE) {
            VideoBackConfirm(
                onContinue = { confirmStop = false },
                onStop = { confirmStop = false; camera.stop() },
            )
        }
    }
}

private enum class VideoSheetKind { NONE, QUALITY, OVERLAY }

private const val STATUS_REFRESH_MILLIS = 500L
private const val STORAGE_REFRESH_MILLIS = 5_000L
private const val SAVED_NOTICE_MILLIS = 6_000L

/** Pulses follow the recorder's own state, so the rider feels what happened, not what was pressed. */
@Composable
private fun VideoHaptics(context: android.content.Context, state: VideoCameraState) {
    val previous = remember { mutableStateOf(state.phase) }
    LaunchedEffect(state.phase) {
        val haptic = videoHapticFor(previous.value, state.phase, state.error != null)
        previous.value = state.phase
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return@LaunchedEffect
        when (haptic) {
            VideoHaptic.STARTED -> vibrator.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
            VideoHaptic.STOPPED -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1))
            VideoHaptic.FAILED -> vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
            null -> Unit
        }
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
    val time = storage?.let {
        val hours = it.minutesLeft / 60
        if (hours > 0) stringResource(Res.string.video_storage_hours, hours.toInt(), (it.minutesLeft % 60).toInt())
        else stringResource(Res.string.video_storage_minutes, it.minutesLeft.toInt())
    }
    val size = storage?.let {
        val value = videoStorageSize(it.freeBytes)
        stringResource(if (value.gigabytes) Res.string.video_storage_gb else Res.string.video_storage_mb, value.text)
    }
    return VideoChips(linkText, linkTone, if (size != null && time != null) "$size · $time" else null, time, storage?.low == true)
}

@Composable
private fun noticeText(notice: VideoCaptureNotice, savedParts: Int): String = when (notice) {
    VideoCaptureNotice.ERROR -> stringResource(Res.string.video_error)
    VideoCaptureNotice.OVERLAY_FAILED -> stringResource(Res.string.video_overlay_failed)
    VideoCaptureNotice.AUDIO_DENIED -> stringResource(Res.string.video_audio_denied)
    VideoCaptureNotice.CLOCK_MISMATCH -> stringResource(Res.string.video_clock_mismatch)
    VideoCaptureNotice.INTERRUPTED -> stringResource(Res.string.video_saved_interrupted)
    VideoCaptureNotice.SAVED_PARTS -> stringResource(Res.string.video_saved_parts, savedParts)
    VideoCaptureNotice.SAVED -> stringResource(Res.string.video_saved)
}

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
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
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
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.presentation.video.VideoOverlayComponent
import volty.composeapp.generated.resources.*

@Composable
internal fun VideoCameraScreen(
    camera: VideoCameraController,
    component: VideoOverlayComponent,
    onClose: () -> Unit,
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
    // One policy for the system Back and the on-screen one: a live recording is never ended by a single tap.
    fun requestBack() {
        when (videoBackAction(editing, state.phase)) {
            VideoBackAction.CLOSE_EDITOR -> editing = false
            VideoBackAction.CONFIRM_STOP -> confirmStop = true
            VideoBackAction.CLOSE_SCREEN -> onClose()
            VideoBackAction.IGNORE -> Unit
        }
    }
    BackHandler { requestBack() }
    if (confirmStop && state.phase != VideoRecordingPhase.IDLE) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(Res.string.video_stop_confirm_title)) },
            text = { Text(stringResource(Res.string.video_stop_confirm_text)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; onClose() }) { Text(stringResource(Res.string.video_stop_confirm_stop)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(Res.string.video_stop_confirm_continue)) }
            },
        )
    }
    if (editing && idle) {
        VideoOverlayEditor(document, component::change, onClose = { editing = false })
        return
    }
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::requestBack) { Text(stringResource(Res.string.video_back)) }
            Text(stringResource(Res.string.video_title), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            TextButton(enabled = idle, onClick = { editing = true }) { Text(stringResource(Res.string.video_overlay)) }
            TextButton(enabled = idle && state.canSwitch, onClick = camera::switchCamera) { Text(stringResource(Res.string.video_switch)) }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
        }
        Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.error != null) {
                Text(stringResource(Res.string.video_error) + ": " + state.error, color = MaterialTheme.colorScheme.error, maxLines = 2)
                if (idle && allowed) TextButton(onClick = { previewView?.let(camera::attach) }) { Text(stringResource(Res.string.video_retry)) }
            }
            if (state.overlayFailed) Text(stringResource(Res.string.video_overlay_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (state.clockMismatch) Text(stringResource(Res.string.video_clock_mismatch), color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
            if (state.saved != null) Text(
                if (state.savedParts > 1) stringResource(Res.string.video_saved_parts, state.savedParts)
                else stringResource(if (state.interrupted) Res.string.video_saved_interrupted else Res.string.video_saved),
                color = Color.White,
            )
            if (audioDenied) Text(stringResource(Res.string.video_audio_denied), color = Color.White)
            if (idle) Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.qualities.forEach { quality ->
                    FilterChip(selected = quality == state.quality, onClick = { camera.setQuality(quality) }, label = {
                        Text(when (quality) { Quality.UHD -> "4K"; Quality.FHD -> "1080p"; Quality.HD -> "720p"; else -> "SD" })
                    })
                }
                Text(stringResource(Res.string.video_segments), color = Color.White)
                VideoSegmentPolicy.CHOICES_MINUTES.forEach { minutes ->
                    FilterChip(selected = minutes == state.segmentMinutes, onClick = { onSegmentMinutes(minutes) }, label = {
                        Text(if (minutes == 0) stringResource(Res.string.video_segments_off) else stringResource(Res.string.video_segments_minutes, minutes))
                    })
                }
                Text(stringResource(Res.string.video_audio), color = Color.White)
                Switch(checked = audio, onCheckedChange = { enabled ->
                    if (!enabled) audio = false else if (granted(Manifest.permission.RECORD_AUDIO)) audio = true
                    else audioRequest.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!idle) Text("%02d:%02d".format(state.durationSeconds / 60, state.durationSeconds % 60), color = Color.White)
                Button(enabled = allowed && (state.ready || !idle) && state.phase != VideoRecordingPhase.STOPPING,
                    onClick = { if (idle) camera.start(audio) else camera.stop() }) {
                    Text(stringResource(when (state.phase) {
                        VideoRecordingPhase.IDLE -> Res.string.video_record
                        VideoRecordingPhase.STARTING -> Res.string.video_starting
                        VideoRecordingPhase.RECORDING -> Res.string.video_stop
                        VideoRecordingPhase.STOPPING -> Res.string.video_stopping
                    }))
                }
            }
            Text(stringResource(if (idle) Res.string.video_foreground_hint else Res.string.video_no_telemetry),
                color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

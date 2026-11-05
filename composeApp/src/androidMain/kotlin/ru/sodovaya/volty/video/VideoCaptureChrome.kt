package ru.sodovaya.volty.video

import androidx.camera.video.Quality
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.*
import volty.composeapp.generated.resources.*

/**
 * The capture chrome, built from the app's own design system: the colour scheme (light, dark, accent, dynamic
 * colour) and typography of `VoltyTheme`, Volty's tonal surfaces and shapes, M3 buttons, chips and switches.
 *
 * **Retraction (owner, 2026-11-03):** this screen used to copy the HTML mock's fixed dark navy palette and its bespoke
 * widgets, «будто вырвано из другого приложения», and it ignored the app's theme. The mock still gives the layout and
 * behaviour (B1 shutter, chips, sheets, landscape rail); the look is Volty's. Only the overlay burned into the video
 * keeps the HUD palette, because a video must read the same on any frame.
 */

/** The panel's height in portrait and the rail's width in landscape: everything else is the frame, shown whole. */
internal val VideoChromeSize = 151.dp

internal enum class ChipTone { PLAIN, GOOD, WARN, BAD }

/** What the status chips say, resolved once for the panel and the rail. */
internal data class VideoChips(
    val linkText: String,
    /** The landscape rail's form of [linkText], from [videoRailLinkText]. */
    val railLinkText: String,
    val linkTone: ChipTone,
    val storageFull: String?,
    val storageShort: String?,
    val storageLow: Boolean,
)

internal data class VideoChromeState(
    val phase: VideoRecordingPhase,
    val canShoot: Boolean,
    val canSwitch: Boolean,
    val elapsedSeconds: Long,
    val chips: VideoChips,
    val presetName: String,
    val resolution: String,
    val frameRate: Int?,
)

internal class VideoChromeActions(
    val onBack: () -> Unit,
    val onShutter: () -> Unit,
    val onSwitch: () -> Unit,
    val onQuality: () -> Unit,
    val onOverlay: () -> Unit,
)

private val VideoChromeState.active get() = phase != VideoRecordingPhase.IDLE

/**
 * Recording is the scheme's error family, the red the app already uses for alarms, so it follows the theme. In a dark
 * scheme the error role is a pastel (tone 80) that read as pink on the shutter (emulator check 2026-11-03), so a dark
 * scheme takes its error container (tone 30), a red that still says "record".
 */
@Composable
private fun recordColor(): Color = MaterialTheme.colorScheme.let { if (it.surface.luminance() < 0.5f) it.errorContainer else it.error }

/** Portrait B1: the tonal panel under the frame. */
@Composable
internal fun VideoCapturePanel(state: VideoChromeState, actions: VideoChromeActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(VideoChromeSize).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (state.active) Box(Modifier.fillMaxWidth().height(4.dp).background(recordColor()).align(Alignment.TopCenter))
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            // Mock B1: the chips sit at the two ends of the row. **Retraction:** they were packed in the middle, and
            // while recording a weighted spacer took half the free width, so «Demo wheel · 0.2 с» was cut to
            // «Demo w…» beside a clock that left room for all of it (visual check 2026-11-03).
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (state.active) {
                    RecClock(videoRecordingClock(state.elapsedSeconds), MaterialTheme.typography.headlineSmall)
                    Box(Modifier.weight(1f).padding(start = 6.dp), contentAlignment = Alignment.CenterEnd) { LinkChip(state.chips) }
                } else {
                    // The storage chip is measured first (unweighted), the link chip takes what is left.
                    Box(Modifier.weight(1f, fill = false).padding(end = 6.dp)) { LinkChip(state.chips) }
                    StorageChip(state.chips, full = state.chips.linkTone == ChipTone.GOOD)
                }
            }
            if (state.active) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Shutter(state, actions.onShutter)
                    Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp)) { StorageChip(state.chips, full = false) }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    BackButton(actions)
                    SwitchButton(state, actions)
                    Shutter(state, actions.onShutter)
                    QualityButton(state, actions)
                    OverlayButton(state, actions)
                }
            }
        }
    }
}

/** Landscape B1: the same controls as a rail on the display's end edge. */
@Composable
internal fun VideoCaptureRail(state: VideoChromeState, actions: VideoChromeActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight().width(VideoChromeSize).background(MaterialTheme.colorScheme.surfaceContainer)) {
        if (state.active) Box(Modifier.fillMaxHeight().width(4.dp).background(recordColor()).align(Alignment.CenterStart))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.active) {
                RecClock(videoElapsedText(state.elapsedSeconds), MaterialTheme.typography.titleLarge)
                Shutter(state, actions.onShutter)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinkChip(state.chips, rail = true)
                    StorageChip(state.chips, full = false)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackButton(actions)
                    OverlayButton(state, actions)
                }
                LinkChip(state.chips, rail = true)
                Shutter(state, actions.onShutter)
                StorageChip(state.chips, full = false)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SwitchButton(state, actions)
                    QualityButton(state, actions)
                }
            }
        }
    }
}

@Composable
private fun RecClock(text: String, style: TextStyle) {
    val rec = recordColor()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(rec.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(rec))
        }
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LinkChip(chips: VideoChips, modifier: Modifier = Modifier, rail: Boolean = false) =
    Chip(Icons.Default.Bluetooth, if (rail) chips.railLinkText else chips.linkText, chips.linkTone, modifier)

@Composable
private fun StorageChip(chips: VideoChips, full: Boolean) {
    val text = (if (full) chips.storageFull else chips.storageShort) ?: return
    Chip(Icons.Default.SdStorage, text, if (chips.storageLow) ChipTone.WARN else ChipTone.PLAIN)
}

/** A status chip in the scheme's tonal roles: plain, "connected" (tertiary, as the dashboard's dot), warning, error. */
@Composable
private fun Chip(icon: ImageVector, text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (fill, ink) = when (tone) {
        ChipTone.PLAIN -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
        ChipTone.GOOD -> scheme.surfaceContainerHighest to scheme.tertiary
        ChipTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        ChipTone.BAD -> scheme.errorContainer to scheme.onErrorContainer
    }
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(fill).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Shutter(state: VideoChromeState, onShutter: () -> Unit) {
    val recording = state.active
    val busy = state.phase == VideoRecordingPhase.STARTING || state.phase == VideoRecordingPhase.STOPPING
    val rec = recordColor()
    val label = stringResource(when (state.phase) {
        VideoRecordingPhase.IDLE -> Res.string.video_record
        VideoRecordingPhase.STARTING -> Res.string.video_starting
        VideoRecordingPhase.RECORDING -> Res.string.video_stop
        VideoRecordingPhase.STOPPING -> Res.string.video_stopping
    })
    Box(
        Modifier
            .size(88.dp)
            .alpha(if (busy || !state.canShoot) 0.5f else 1f)
            .clip(CircleShape)
            .clickable(enabled = state.canShoot && !busy, role = Role.Button, onClickLabel = label, onClick = onShutter)
            .semantics { contentDescription = label }
            .border(5.dp, if (recording) rec else MaterialTheme.colorScheme.onSurface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(rec))
        } else {
            Box(Modifier.size(68.dp).clip(CircleShape).background(rec))
        }
    }
}

/** A 58 dp tonal circle: the M3 filled-tonal role, glove sized. */
@Composable
private fun RoundButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.size(58.dp).alpha(if (enabled) 1f else 0.4f).semantics { contentDescription = description },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center, content = content)
    }
}

@Composable
private fun BackButton(actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_back)
    RoundButton(text, actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun SwitchButton(state: VideoChromeState, actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_switch)
    RoundButton(text, actions.onSwitch, enabled = state.canSwitch) { Icon(Icons.Default.Cameraswitch, null, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun QualityButton(state: VideoChromeState, actions: VideoChromeActions) {
    RoundButton(stringResource(Res.string.video_sheet_quality), actions.onQuality) {
        Text(state.resolution, style = MaterialTheme.typography.labelLarge, lineHeight = 14.sp)
        state.frameRate?.let { Text(it.toString(), style = MaterialTheme.typography.labelSmall, lineHeight = 12.sp) }
    }
}

@Composable
private fun OverlayButton(state: VideoChromeState, actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_overlay)
    RoundButton(text, actions.onOverlay) {
        Icon(Icons.Default.Tune, null, modifier = Modifier.size(22.dp))
        Text(state.presetName, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 11.sp)
    }
}

/** Status lines over the frame, on a tonal surface. They live in the view tree, so they never reach the file. */
@Composable
internal fun VideoNotices(notices: List<String>, showRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            notices.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (showRetry) {
                Text(
                    stringResource(Res.string.video_retry),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button, onClick = onRetry).padding(vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * The app's bottom sheet, drawn in place: the M3 sheet container, shape, drag handle and scrim. Not a
 * `ModalBottomSheet`, because its window would take the volume keys (the shutter) away from the activity.
 * [lightScrim] keeps the frame visible behind the overlay sheet, where the frame is what is being chosen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun VideoSheet(onDismiss: () -> Unit, lightScrim: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val dimClicks = remember { MutableInteractionSource() }
    val scrim = BottomSheetDefaults.ScrimColor.let { if (lightScrim) it.copy(alpha = it.alpha * 0.35f) else it }
    Box(Modifier.fillMaxSize().background(scrim).clickable(dimClicks, indication = null, onClick = onDismiss)) {
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).widthIn(max = 640.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, indication = null) {},
            shape = BottomSheetDefaults.ExpandedShape,
            color = BottomSheetDefaults.ContainerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = BottomSheetDefaults.Elevation,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally)) { BottomSheetDefaults.DragHandle() }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
            }
        }
    }
}

/** A question with up to three big answers: the app's filled, tonal and error buttons, 56 dp for a glove. */
@Composable
internal fun VideoDecisionSheet(
    title: String,
    text: String,
    primary: String?,
    onPrimary: () -> Unit,
    destructive: String,
    onDestructive: () -> Unit,
    neutral: String,
    onNeutral: () -> Unit,
) {
    VideoSheet(onNeutral) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        primary?.let { SheetButton(it, SheetButtonKind.PRIMARY, onPrimary) }
        SheetButton(neutral, SheetButtonKind.NEUTRAL, onNeutral)
        SheetButton(destructive, SheetButtonKind.DESTRUCTIVE, onDestructive)
    }
}

internal enum class SheetButtonKind { PRIMARY, NEUTRAL, DESTRUCTIVE }

@Composable
internal fun VideoSheetButton(text: String, kind: SheetButtonKind, enabled: Boolean = true, onClick: () -> Unit) =
    SheetButton(text, kind, onClick, enabled)

@Composable
internal fun VideoBackConfirm(onContinue: () -> Unit, onStop: () -> Unit) {
    VideoSheet(onContinue) {
        Text(stringResource(Res.string.video_stop_confirm_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(Res.string.video_stop_confirm_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SheetButton(stringResource(Res.string.video_stop_confirm_continue), SheetButtonKind.NEUTRAL, onContinue)
        SheetButton(stringResource(Res.string.video_stop_confirm_stop), SheetButtonKind.DESTRUCTIVE, onStop)
    }
}

@Composable
private fun SheetButton(text: String, kind: SheetButtonKind, onClick: () -> Unit, enabled: Boolean = true) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
    val label: @Composable RowScope.() -> Unit = {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
    when (kind) {
        SheetButtonKind.PRIMARY -> Button(onClick, modifier, enabled = enabled, content = label)
        SheetButtonKind.NEUTRAL -> FilledTonalButton(onClick, modifier, enabled = enabled, content = label)
        SheetButtonKind.DESTRUCTIVE -> Button(
            onClick, modifier, enabled = enabled,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            content = label,
        )
    }
}

@Composable
private fun SheetTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun SheetLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SheetChoices(choices: List<T>, selected: T?, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onPick(choice) },
                label = { Text(label(choice), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 10.dp)) },
            )
        }
    }
}

internal fun videoQualityText(quality: Quality): String = when (quality) {
    Quality.UHD -> "4K"
    Quality.FHD -> "1080p"
    Quality.HD -> "720p"
    else -> "SD"
}

internal fun videoQualityHeight(quality: Quality): Int = when (quality) {
    Quality.UHD -> 2160
    Quality.FHD -> 1080
    Quality.HD -> 720
    else -> 480
}

@Composable
internal fun VideoQualitySheet(
    state: VideoCameraState,
    audio: Boolean,
    onQuality: (Quality) -> Unit,
    onFrameRate: (Int) -> Unit,
    onStabilization: (VideoStabilizationMode) -> Unit,
    onAudio: (Boolean) -> Unit,
    onSegments: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    VideoSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.video_sheet_quality))
        SheetChoices(state.qualities, state.quality, { videoQualityText(it) }, onQuality)
        if (state.frameRateChoices.isNotEmpty()) {
            SheetLabel(stringResource(Res.string.video_frame_rate))
            SheetChoices(state.frameRateChoices, state.targetFrameRate, { "$it FPS" }, onFrameRate)
        }
        if (state.stabilizationModes.size > 1) {
            SheetLabel(stringResource(Res.string.video_stabilization_mode))
            SheetChoices(state.stabilizationModes, state.stabilizationMode, { mode ->
                stringResource(when (mode) {
                    VideoStabilizationMode.OFF -> Res.string.video_stabilization_off
                    VideoStabilizationMode.VIDEO -> Res.string.video_stabilization_video
                    VideoStabilizationMode.PREVIEW -> Res.string.video_stabilization_preview
                })
            }, onStabilization)
        }
        Text(stringResource(when {
            !state.stabilizationAvailable -> Res.string.video_stabilization_unavailable
            state.stabilizationActive == true -> Res.string.video_stabilization_active
            state.stabilizationActive == false -> Res.string.video_stabilization_inactive
            else -> Res.string.video_stabilization_requested
        }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.video_audio), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = audio, onCheckedChange = onAudio)
        }
        SheetLabel(stringResource(Res.string.video_segments))
        SheetChoices(VideoSegmentPolicy.CHOICES_MINUTES, state.segmentMinutes, { minutes ->
            if (minutes == 0) stringResource(Res.string.video_segments_off) else stringResource(Res.string.video_segments_minutes, minutes)
        }, onSegments)
        Text(stringResource(Res.string.video_foreground_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun VideoOverlaySheet(
    document: OverlayDocument,
    onPreset: (OverlayPreset) -> Unit,
    onTripScope: (OverlayTripScope) -> Unit,
    onCustomize: () -> Unit,
    onDismiss: () -> Unit,
) {
    val builtIns = remember { OverlayTemplates.all() }
    VideoSheet(onDismiss, lightScrim = true) {
        SheetTitle(stringResource(Res.string.video_sheet_overlay))
        val choices = builtIns + document.saved
        val selected = choices.firstOrNull { it.id == document.selected.id }
        SheetChoices(choices, selected, { overlayPresetName(it, document.saved) }, onPreset)
        if (overlayUsesTrip(document.selected)) {
            SheetLabel(stringResource(Res.string.video_trip_scope_title))
            SheetChoices(OverlayTripScope.entries, document.tripScope, {
                stringResource(if (it == OverlayTripScope.RECORDING) Res.string.video_trip_scope_recording else Res.string.video_trip_scope_ride)
            }, onTripScope)
        }
        SheetButton(stringResource(Res.string.video_overlay_customize), SheetButtonKind.NEUTRAL, onCustomize)
    }
}

/** A built-in's localized name; a saved preset is called what the rider called it. */
@Composable
internal fun overlayPresetName(preset: OverlayPreset, saved: List<OverlayPreset>): String =
    if (saved.any { it.id == preset.id } || OverlayTemplates.byId(preset.id) == null) preset.name else stringResource(builtinName(preset))

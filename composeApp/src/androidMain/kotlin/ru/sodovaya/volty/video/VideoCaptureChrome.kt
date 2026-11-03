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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.*
import volty.composeapp.generated.resources.*

/** The panel's height in portrait and the rail's width in landscape: everything else is the frame, shown whole. */
internal val VideoChromeSize = 151.dp

private val Panel = Color(OverlayPalette.PANEL)
private val Rec = Color(OverlayPalette.REC)
private val HudText = Color(OverlayPalette.TEXT)
private val HudMuted = Color(OverlayPalette.MUTED)
private val Good = Color(OverlayPalette.BATTERY)
private val Warn = Color(OverlayPalette.ORANGE)
private val Bad = Color(OverlayPalette.PINK)
private val Accent = Color(OverlayPalette.CYAN)
private val ButtonFill = Color(0x523E5C6C)
private val SheetFill = Color(0xF70A1822)

internal enum class ChipTone { PLAIN, GOOD, WARN, BAD }

/** What the status chips say, resolved once for the panel and the rail. */
internal data class VideoChips(
    val linkText: String,
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

/** Portrait B1: the dark panel under the frame. */
@Composable
internal fun VideoCapturePanel(state: VideoChromeState, actions: VideoChromeActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(VideoChromeSize).background(Panel)) {
        if (state.active) Box(Modifier.fillMaxWidth().height(4.dp).background(Rec).align(Alignment.TopCenter))
        Column(Modifier.fillMaxSize().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                if (state.active) {
                    RecClock(videoRecordingClock(state.elapsedSeconds), 26.sp)
                    Spacer(Modifier.weight(1f))
                    LinkChip(state.chips, Modifier.weight(1f, fill = false))
                } else {
                    LinkChip(state.chips, Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(0.01f))
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
    Box(modifier.fillMaxHeight().width(VideoChromeSize).background(Panel)) {
        if (state.active) Box(Modifier.fillMaxHeight().width(4.dp).background(Rec).align(Alignment.CenterStart))
        Column(
            Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (state.active) {
                RecClock(videoElapsedText(state.elapsedSeconds), 22.sp)
                Shutter(state, actions.onShutter)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    LinkChip(state.chips)
                    StorageChip(state.chips, full = false)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BackButton(actions)
                    OverlayButton(state, actions)
                }
                LinkChip(state.chips)
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
private fun RecClock(text: String, size: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Rec.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(Rec))
        }
        Text(text, color = Color.White, fontSize = size, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LinkChip(chips: VideoChips, modifier: Modifier = Modifier) = Chip(Icons.Default.Bluetooth, chips.linkText, chips.linkTone, modifier)

@Composable
private fun StorageChip(chips: VideoChips, full: Boolean) {
    val text = (if (full) chips.storageFull else chips.storageShort) ?: return
    Chip(Icons.Default.SdStorage, text, if (chips.storageLow) ChipTone.WARN else ChipTone.PLAIN)
}

@Composable
private fun Chip(icon: ImageVector, text: String, tone: ChipTone, modifier: Modifier = Modifier) {
    val fill = when (tone) { ChipTone.WARN -> Warn; ChipTone.BAD -> Bad; else -> ButtonFill }
    val ink = when (tone) {
        ChipTone.WARN -> Color(OverlayPalette.ON_ORANGE)
        ChipTone.BAD -> Color(OverlayPalette.ON_PINK)
        ChipTone.GOOD -> Good
        ChipTone.PLAIN -> HudText
    }
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(fill).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
        Text(text, color = ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Shutter(state: VideoChromeState, onShutter: () -> Unit) {
    val recording = state.active
    val busy = state.phase == VideoRecordingPhase.STARTING || state.phase == VideoRecordingPhase.STOPPING
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
            .border(5.dp, if (recording) Rec else Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)).background(Rec))
        } else {
            Box(Modifier.size(68.dp).clip(CircleShape).background(Rec))
        }
    }
}

@Composable
private fun RoundButton(description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .size(58.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(ButtonFill)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content,
    )
}

@Composable
private fun BackButton(actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_back)
    RoundButton(text, actions.onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text, tint = HudText, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun SwitchButton(state: VideoChromeState, actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_switch)
    RoundButton(text, actions.onSwitch, enabled = state.canSwitch) { Icon(Icons.Default.Cameraswitch, text, tint = HudText, modifier = Modifier.size(22.dp)) }
}

@Composable
private fun QualityButton(state: VideoChromeState, actions: VideoChromeActions) {
    RoundButton(stringResource(Res.string.video_sheet_quality), actions.onQuality) {
        Text(state.resolution, color = HudText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp)
        state.frameRate?.let { Text(it.toString(), color = HudMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp) }
    }
}

@Composable
private fun OverlayButton(state: VideoChromeState, actions: VideoChromeActions) {
    val text = stringResource(Res.string.video_overlay)
    RoundButton(text, actions.onOverlay) {
        Icon(Icons.Default.Tune, text, tint = HudText, modifier = Modifier.size(22.dp))
        Text(state.presetName, color = HudText, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Clip, lineHeight = 11.sp)
    }
}

/** Status lines over the frame. They live in the view tree, so like the rest of the chrome they never reach the file. */
@Composable
internal fun VideoNotices(notices: List<String>, showRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (notices.isEmpty()) return
    Column(
        modifier.padding(8.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xCC07131E)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        notices.forEach { Text(it, color = HudText, fontSize = 13.sp) }
        if (showRetry) {
            Text(
                stringResource(Res.string.video_retry),
                color = Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(role = Role.Button, onClick = onRetry).padding(vertical = 8.dp),
            )
        }
    }
}

/** A dimmed sheet. Not a dialog: a dialog's window would take the volume keys away from the activity. */
@Composable
internal fun VideoSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dimClicks = remember { MutableInteractionSource() }
    Box(Modifier.fillMaxSize().background(Color(0x85000000)).clickable(dimClicks, indication = null, onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(SheetFill)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/** A question with up to three big answers. */
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
        Text(title, color = HudText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(text, color = HudMuted, fontSize = 14.sp)
        primary?.let { SheetButton(it, Accent, Color(0xFF04202A), onPrimary) }
        SheetButton(neutral, ButtonFill, HudText, onNeutral)
        SheetButton(destructive, Rec, Color.White, onDestructive)
    }
}

@Composable
internal fun VideoSheetButton(text: String, fill: Color, ink: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.alpha(if (enabled) 1f else 0.4f)) {
        SheetButton(text, fill, ink, if (enabled) onClick else ({}))
    }
}

@Composable
internal fun VideoBackConfirm(onContinue: () -> Unit, onStop: () -> Unit) {
    VideoSheet(onContinue) {
        Text(stringResource(Res.string.video_stop_confirm_title), color = HudText, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(Res.string.video_stop_confirm_text), color = HudMuted, fontSize = 14.sp)
        SheetButton(stringResource(Res.string.video_stop_confirm_continue), ButtonFill, HudText, onContinue)
        SheetButton(stringResource(Res.string.video_stop_confirm_stop), Rec, Color.White, onStop)
    }
}

@Composable
private fun SheetButton(text: String, fill: Color, ink: Color, onClick: () -> Unit, height: Dp = 64.dp) {
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(fill).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SheetTitle(text: String) = Text(text, color = HudText, fontSize = 17.sp, fontWeight = FontWeight.Bold)

@Composable
private fun SheetLabel(text: String) = Text(text, color = HudMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SheetChoices(choices: List<T>, selected: T?, label: @Composable (T) -> String, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onPick(choice) },
                label = { Text(label(choice), fontSize = 15.sp, modifier = Modifier.padding(vertical = 10.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = HudText,
                    selectedLabelColor = Color(0xFF04202A),
                    selectedContainerColor = Accent,
                ),
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
    onAudio: (Boolean) -> Unit,
    onSegments: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    VideoSheet(onDismiss) {
        SheetTitle(stringResource(Res.string.video_sheet_quality))
        SheetChoices(state.qualities, state.quality, { videoQualityText(it) }, onQuality)
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.video_audio), color = HudText, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(checked = audio, onCheckedChange = onAudio)
        }
        SheetLabel(stringResource(Res.string.video_segments))
        SheetChoices(VideoSegmentPolicy.CHOICES_MINUTES, state.segmentMinutes, { minutes ->
            if (minutes == 0) stringResource(Res.string.video_segments_off) else stringResource(Res.string.video_segments_minutes, minutes)
        }, onSegments)
        Text(stringResource(Res.string.video_foreground_hint), color = HudMuted, fontSize = 13.sp)
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
    VideoSheet(onDismiss) {
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
        SheetButton(stringResource(Res.string.video_overlay_customize), ButtonFill, HudText, onCustomize, height = 56.dp)
    }
}

/** A built-in's localized name; a saved preset is called what the rider called it. */
@Composable
internal fun overlayPresetName(preset: OverlayPreset, saved: List<OverlayPreset>): String =
    if (saved.any { it.id == preset.id } || OverlayTemplates.byId(preset.id) == null) preset.name else stringResource(builtinName(preset))

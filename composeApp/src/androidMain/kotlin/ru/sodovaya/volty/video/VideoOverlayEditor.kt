package ru.sodovaya.volty.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Crop169
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.*
import ru.sodovaya.volty.util.UnitSystem
import volty.composeapp.generated.resources.*
import java.util.UUID

// The editor's chrome is the app's: every colour below is a role of the current scheme (light, dark, accent,
// dynamic). **Retraction (owner, 2026-11-03):** these were the mock's fixed navy tokens, so the editor looked like
// another app and never followed the theme. Only the stage, which is the video frame, keeps the overlay's palette.
private val Panel: Color @Composable get() = MaterialTheme.colorScheme.surface
private val Sheet: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
private val Ink: Color @Composable get() = MaterialTheme.colorScheme.onSurface
private val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
private val OnAccent: Color @Composable get() = MaterialTheme.colorScheme.onPrimary
private val Warn: Color @Composable get() = MaterialTheme.colorScheme.tertiary
private val Fill: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
private val StageButtonFill: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer

/** The snap guides are drawn on the frame, so they keep the overlay's pink: they must read on any picture. */
private val Guide = Color(OverlayPalette.PINK)
private val Selection = Color(OverlayPalette.CYAN)

private enum class EditorTab { DATA, LOOK, PLACE }
private enum class StageBackdrop { STILL, DUSK, DAY }
private enum class Naming { NONE, COPY }

private val Swatches: List<Pair<Long, StringResource>> = listOf(
    OverlayPalette.CYAN to Res.string.video_editor_color_cyan,
    OverlayPalette.GREEN to Res.string.video_editor_color_teal,
    OverlayPalette.BATTERY to Res.string.video_editor_color_green,
    OverlayPalette.ORANGE to Res.string.video_editor_color_orange,
    OverlayPalette.PINK to Res.string.video_editor_color_pink,
    OverlayPalette.TEXT to Res.string.video_editor_color_white,
)

private fun OverlayWidget.dot(): Color = Color(overlayColorArgb(style.accent ?: style.foreground))

/** The widget's overlay colour as a dot with a hairline, so a white or a pale accent still shows on a light sheet. */
@Composable
private fun WidgetDot(widget: OverlayWidget) {
    Box(Modifier.size(12.dp).clip(CircleShape).background(widget.dot()).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

/**
 * Mock D: one screen, frame on top and a sheet below. Everything it decides is in [reduceOverlayEditor]; this file
 * turns touches into actions and draws the state. The stage draws with the very [OverlayCanvasDrawer] and scene builder
 * the recording uses, so what the rider arranges is what gets burned in.
 */
@Composable
internal fun VideoOverlayEditor(
    document: OverlayDocument,
    strings: VideoOverlayStrings,
    system: UnitSystem,
    still: ImageBitmap?,
    onChange: (OverlayDocument) -> Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val drawer = remember { OverlayCanvasDrawer(OverlayFonts(context)) }
    val env = remember(strings, system) { OverlayEditorEnv(strings, system, drawer) }
    var state by remember { mutableStateOf(OverlayEditorState.open(document)) }
    var naming by remember { mutableStateOf(Naming.NONE) }
    var adding by remember { mutableStateOf(false) }
    var backdrop by remember { mutableStateOf(if (still != null) StageBackdrop.STILL else StageBackdrop.DAY) }

    var rejected by remember { mutableStateOf(false) }
    fun dispatch(action: OverlayEditorAction) {
        val before = state
        val next = reduceOverlayEditor(before, action, env)
        // A document the store refuses (too large, invalid) must not look saved: keep the editor where it was.
        if (next.document != before.document && !onChange(next.document)) {
            state = before.copy(confirm = null)
            rejected = true
            return
        }
        rejected = false
        state = next
        if (state.closed) onClose()
    }
    BackHandler {
        when {
            naming != Naming.NONE -> naming = Naming.NONE
            adding -> adding = false
            state.confirm != null -> dispatch(OverlayEditorAction.ConfirmCancel)
            state.selectedId != null -> dispatch(OverlayEditorAction.Select(null))
            else -> dispatch(OverlayEditorAction.RequestClose)
        }
    }
    fun save() {
        if (state.canOverwrite) dispatch(OverlayEditorAction.Save) else naming = Naming.COPY
    }

    val nextBackdrop = {
        backdrop = when (backdrop) {
            StageBackdrop.STILL -> StageBackdrop.DUSK
            StageBackdrop.DUSK -> StageBackdrop.DAY
            StageBackdrop.DAY -> if (still != null) StageBackdrop.STILL else StageBackdrop.DUSK
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Panel).safeDrawingPadding()) {
        val wide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(state, onClose = { dispatch(OverlayEditorAction.RequestClose) }, onSave = ::save, dispatch = ::dispatch)
            if (rejected) {
                Text(stringResource(Res.string.video_editor_save_failed), color = Warn, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            if (wide) {
                Row(Modifier.weight(1f)) {
                    EditorStage(state, env, drawer, still, backdrop, nextBackdrop, ::dispatch, Modifier.weight(1f).fillMaxHeight())
                    EditorSheet(state, adding, { adding = it }, { naming = Naming.COPY }, ::dispatch, Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                EditorStage(state, env, drawer, still, backdrop, nextBackdrop, ::dispatch, Modifier.weight(1f).fillMaxWidth())
                EditorSheet(state, adding, { adding = it }, { naming = Naming.COPY }, ::dispatch, Modifier.fillMaxWidth().height(312.dp))
            }
        }
        state.confirm?.let { confirm ->
            EditorConfirm(confirm, state,
                onSave = { save() },
                onDiscard = { dispatch(OverlayEditorAction.ConfirmDiscard) },
                onCancel = { dispatch(OverlayEditorAction.ConfirmCancel) })
        }
        if (naming == Naming.COPY) {
            val base = stringResource(Res.string.video_editor_copy_name, overlayPresetName(state.draft, state.document.saved))
            NameSheet(base,
                onName = { name ->
                    dispatch(OverlayEditorAction.SaveAs(UUID.randomUUID().toString(), name))
                    naming = Naming.NONE
                },
                onDismiss = { naming = Naming.NONE })
        }
    }
}

@Composable
private fun EditorTopBar(state: OverlayEditorState, onClose: () -> Unit, onSave: () -> Unit, dispatch: (OverlayEditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTarget(Icons.Default.Close, stringResource(Res.string.video_editor_close), onClick = onClose)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(overlayPresetName(state.draft, state.document.saved), style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                state.edited -> Text(stringResource(Res.string.video_editor_changed), style = MaterialTheme.typography.labelMedium, color = Warn, maxLines = 1)
                state.isBuiltIn -> Text(stringResource(Res.string.video_editor_builtin_note), style = MaterialTheme.typography.labelMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconTarget(Icons.AutoMirrored.Filled.Undo, stringResource(Res.string.video_editor_undo), enabled = state.canUndo) { dispatch(OverlayEditorAction.Undo) }
        IconTarget(Icons.AutoMirrored.Filled.Redo, stringResource(Res.string.video_editor_redo), enabled = state.canRedo) { dispatch(OverlayEditorAction.Redo) }
        Button(onClick = onSave, enabled = state.edited && (state.canOverwrite || state.canSaveCopy)) {
            Text(stringResource(Res.string.video_editor_save))
        }
    }
}

@Composable
private fun IconTarget(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(48.dp).alpha(if (enabled) 1f else 0.32f).clip(CircleShape)
            .background(if (selected) Accent else if (filled) StageButtonFill else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (selected) OnAccent else Ink, modifier = Modifier.size(24.dp))
    }
}

private fun backdropBrush(backdrop: StageBackdrop): Brush = when (backdrop) {
    StageBackdrop.DAY -> Brush.verticalGradient(
        0f to Color(0xFFB9DCF2), 0.38f to Color(0xFFF7FBFC), 0.47f to Color.White,
        0.48f to Color(0xFFCFD3CF), 1f to Color(0xFFADAEAB),
    )
    else -> Brush.verticalGradient(
        0f to Color(0xFF0D1A2C), 0.36f to Color(0xFF54496A), 0.47f to Color(0xFFF2B06E),
        0.48f to Color(0xFF10131A), 1f to Color(0xFF0C0F14),
    )
}

@Composable
private fun EditorStage(
    state: OverlayEditorState,
    env: OverlayEditorEnv,
    drawer: OverlayCanvasDrawer,
    still: ImageBitmap?,
    backdrop: StageBackdrop,
    onBackdrop: () -> Unit,
    dispatch: (OverlayEditorAction) -> Unit,
    modifier: Modifier,
) {
    val designW = overlayDesignWidth(state.landscape)
    val designH = overlayDesignHeight(state.landscape)
    val scene = remember(state.draft, state.landscape, env) {
        val sample = overlaySampleSnapshot()
        val scales = OverlayScaleTracker().update(state.draft, sample, env.system)
        buildOverlayScene(
            OverlaySceneInput(state.draft, sample, env.strings, env.system, designW.toInt(), designH.toInt(), OVERLAY_SAMPLE_TRIP, scales),
            env.measure,
        )
    }
    val latestScene by rememberUpdatedState(scene)
    val selected = scene.widgets.firstOrNull { it.id == state.selectedId }

    val selectionColor = Selection
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        val density = LocalDensity.current
        check(density.density > 0f)
        val availableW = maxWidth - 62.dp
        val fit = minOf(availableW / designW, maxHeight / designH)
        val frameW = fit * designW
        val frameH = fit * designH
        val toDesign = with(density) { 1f / fit.toPx() }
        // No lift: the mock's 48 dp clearance was stored into the position, so even a tiny drag made the widget
        // jump for good (review Minor 1). The widget follows the finger; the arrows place it exactly.
        val lift = 0f

        Column(Modifier.padding(start = 7.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            IconTarget(Icons.Default.CropPortrait, stringResource(Res.string.video_editor_portrait), selected = !state.landscape, filled = true) {
                dispatch(OverlayEditorAction.SetOrientation(false))
            }
            IconTarget(Icons.Default.Crop169, stringResource(Res.string.video_editor_landscape), selected = state.landscape, filled = true) {
                dispatch(OverlayEditorAction.SetOrientation(true))
            }
            IconTarget(Icons.Default.Wallpaper, stringResource(Res.string.video_editor_background), filled = true, onClick = onBackdrop)
        }
        Box(
            Modifier.align(Alignment.Center).offset(x = 28.dp).size(frameW, frameH).clipToBounds()
                .pointerInput(toDesign) {
                    detectTapGestures { position ->
                        dispatch(OverlayEditorAction.Select(overlayHitTest(latestScene.widgets, position.x * toDesign, position.y * toDesign)))
                    }
                }
                .pointerInput(toDesign, lift) {
                    detectDragGestures(
                        onDragStart = { position ->
                            overlayHitTest(latestScene.widgets, position.x * toDesign, position.y * toDesign)
                                ?.let { dispatch(OverlayEditorAction.DragStart(it, lift)) }
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dispatch(OverlayEditorAction.DragBy(amount.x * toDesign, amount.y * toDesign))
                        },
                        onDragEnd = { dispatch(OverlayEditorAction.DragEnd) },
                        onDragCancel = { dispatch(OverlayEditorAction.DragCancel) },
                    )
                },
        ) {
            if (backdrop == StageBackdrop.STILL && still != null) {
                Image(still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(backdropBrush(backdrop)))
            }
            Canvas(Modifier.fillMaxSize()) {
                val k = size.width / designW
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    native.save()
                    native.scale(k, k)
                    drawer.draw(native, scene)
                    native.restore()
                }
                val drag = state.drag
                if (drag != null) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(20f * k, 20f * k))
                    val faint = Color.White.copy(alpha = 0.16f)
                    for (x in listOf(designW / 3, designW * 2 / 3)) drawLine(faint, Offset(x * k, 0f), Offset(x * k, size.height), 3f * k, pathEffect = dash)
                    for (y in listOf(designH / 3, designH * 2 / 3)) drawLine(faint, Offset(0f, y * k), Offset(size.width, y * k), 3f * k, pathEffect = dash)
                    val margin = 54f * k
                    drawRect(Color.White.copy(alpha = 0.3f), Offset(margin, margin), Size(size.width - 2 * margin, size.height - 2 * margin), style = Stroke(3f * k))
                    for (guide in drag.guides) {
                        val p = guide.position * k
                        if (guide.vertical) drawLine(Guide, Offset(p, 0f), Offset(p, size.height), 7f * k)
                        else drawLine(Guide, Offset(0f, p), Offset(size.width, p), 7f * k)
                    }
                }
                if (selected != null) {
                    val inset = 10f * k
                    drawRoundRect(
                        selectionColor, Offset(selected.left * k - inset, selected.top * k - inset),
                        Size((selected.right - selected.left) * k + 2 * inset, (selected.bottom - selected.top) * k + 2 * inset),
                        CornerRadius(28f * k), style = Stroke(10f * k),
                    )
                }
            }
            Text(
                stringResource(Res.string.video_editor_sample),
                // lineHeight: the theme's 24 sp body line made this a 31 dp slab over the speed dial, not the mock's chip.
                color = MaterialTheme.colorScheme.inverseOnSurface, fontSize = 10.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp,
                // Bottom start: at the top it covered the first chip of a top-row preset (meow_icons, 2026-11-03).
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f)).padding(horizontal = 7.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun EditorSheet(
    state: OverlayEditorState,
    adding: Boolean,
    onAdding: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    dispatch: (OverlayEditorAction) -> Unit,
    modifier: Modifier,
) {
    var tab by remember { mutableStateOf(EditorTab.DATA) }
    Column(modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Sheet).padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(32.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
        val widget = state.selected
        val drag = state.drag
        when {
            drag != null -> DragPanel(state, drag)
            adding -> AddPanel(onPick = { metric ->
                dispatch(OverlayEditorAction.AddWidget(UUID.randomUUID().toString(), metric))
                onAdding(false)
            }, onCancel = { onAdding(false) })
            widget != null -> WidgetPanel(state, widget, tab, { tab = it }, dispatch)
            else -> OverviewPanel(state, onAdd = { onAdding(true) }, onDuplicate, dispatch)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 5.dp))
}

/**
 * A choice. [outlined] draws the selection as mock D's preset row does, a cyan rim on the dark tile, for a choice
 * that is the current state rather than an edit; [source] is a metric tile's small second line.
 */
@Composable
private fun Tile(
    text: String,
    on: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Int = 52,
    outlined: Boolean = false,
    source: String? = null,
    onClick: () -> Unit,
) {
    val filledOn = on && !outlined
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.height(height.dp).alpha(if (enabled) 1f else 0.35f).clip(shape).background(if (filledOn) Accent else Fill)
            .then(if (on && outlined) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text, color = if (filledOn) OnAccent else Ink, fontSize = if (outlined) 14.sp else 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = if (source == null) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp,
            )
            source?.let {
                Text(
                    it, color = if (filledOn) OnAccent.copy(alpha = 0.8f) else Muted, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp,
                )
            }
        }
    }
}

/** The tab strip is the app's segmented button, as in Settings: not tiles, which are the choices below it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(tabs: List<Pair<EditorTab, String>>, selected: EditorTab, onTab: (EditorTab) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(bottom = 9.dp)) {
        tabs.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onTab(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                label = { Text(label, maxLines = 1) },
            )
        }
    }
}

@Composable
private fun OverviewPanel(state: OverlayEditorState, onAdd: () -> Unit, onDuplicate: () -> Unit, dispatch: (OverlayEditorAction) -> Unit) {
    val builtIns = remember { OverlayTemplates.all() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(Res.string.video_editor_preset))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (builtIns + state.document.saved).forEach { preset ->
                Tile(overlayPresetName(preset, state.document.saved), preset.id == state.draft.id, Modifier.widthIn(min = 104.dp), height = 60, outlined = true) {
                    dispatch(OverlayEditorAction.RequestPreset(preset))
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
            TextAction(Icons.Default.ContentCopy, stringResource(Res.string.video_editor_duplicate), enabled = state.canSaveCopy, onClick = onDuplicate)
            if (state.canOverwrite) {
                Spacer(Modifier.width(12.dp))
                TextAction(Icons.Default.Delete, stringResource(Res.string.video_editor_delete)) { dispatch(OverlayEditorAction.RequestDelete(state.draft.id)) }
            }
            Spacer(Modifier.weight(1f))
            TextAction(Icons.Default.Add, stringResource(Res.string.video_editor_add), enabled = state.draft.widgets.size < 24, onClick = onAdd)
        }
        if (!state.canSaveCopy) Text(stringResource(Res.string.video_editor_full), color = Warn, fontSize = 12.sp)
        state.draft.widgets.forEach { widget ->
            val box = widget.placement?.let { if (state.landscape) it.landscape else it.portrait }
            val hidden = widget.placement != null && box == null
            Row(
                Modifier.fillMaxWidth().height(50.dp).clickable(role = Role.Button) { dispatch(OverlayEditorAction.Select(widget.id)) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WidgetDot(widget)
                Text(overlayLabel(widget, widgetLabelStrings()), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
                Text(
                    listOfNotNull(stringResource(kindLabel(widget.kind)), box?.let { stringResource(anchorLabel(it.anchor)) }, if (hidden) stringResource(Res.string.video_editor_hidden) else null).joinToString(" · "),
                    color = Muted, fontSize = 12.sp, maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TextAction(icon: ImageVector, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).alpha(if (enabled) 1f else 0.4f).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        Text(text, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The widget list and the selection header name a widget by the very label it burns in, «Ток батареи», as in mock D.
 * **Retraction:** they used the metric grid's catalogue names, so the list read «Скорость · контроллер», words that
 * appear nowhere on the frame. The grid keeps the catalogue names: there the source is what the rider is choosing.
 */
@Composable
private fun widgetLabelStrings() = VideoOverlayStrings(labels = VideoMetric.entries.associateWith { videoBurnedLabel(it) })

@Composable
private fun videoBurnedLabel(metric: VideoMetric): String = stringResource(when (metric) {
    VideoMetric.SPEED -> Res.string.video_label_speed
    VideoMetric.DUTY -> Res.string.video_label_duty
    VideoMetric.ESC_TEMPERATURE -> Res.string.video_label_esc_temperature
    VideoMetric.MOTOR_TEMPERATURE -> Res.string.video_label_motor_temperature
    VideoMetric.MOTOR_CURRENT -> Res.string.video_label_motor_current
    VideoMetric.CONTROLLER_CURRENT -> Res.string.video_label_controller_current
    VideoMetric.CONTROLLER_POWER -> Res.string.video_label_controller_power
    VideoMetric.SOC -> Res.string.video_label_soc
    VideoMetric.VOLTAGE -> Res.string.video_label_voltage
    VideoMetric.BATTERY_CURRENT -> Res.string.video_label_battery_current
    VideoMetric.BATTERY_POWER -> Res.string.video_label_battery_power
    VideoMetric.BATTERY_TEMPERATURE -> Res.string.video_label_battery_temperature
    VideoMetric.ELAPSED -> Res.string.video_label_elapsed
    VideoMetric.DISTANCE -> Res.string.video_label_distance
})

@Composable
private fun WidgetPanel(state: OverlayEditorState, widget: OverlayWidget, tab: EditorTab, onTab: (EditorTab) -> Unit, dispatch: (OverlayEditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        WidgetDot(widget)
        Spacer(Modifier.width(8.dp))
        Text(overlayLabel(widget, widgetLabelStrings()), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconTarget(Icons.Default.Delete, stringResource(Res.string.video_editor_delete), enabled = state.draft.widgets.size > 1) { dispatch(OverlayEditorAction.DeleteWidget) }
        IconTarget(Icons.Default.Close, stringResource(Res.string.video_editor_close)) { dispatch(OverlayEditorAction.Select(null)) }
    }
    Tabs(
        listOf(
            EditorTab.DATA to stringResource(Res.string.video_editor_tab_data),
            EditorTab.LOOK to stringResource(Res.string.video_editor_tab_look),
            EditorTab.PLACE to stringResource(Res.string.video_editor_tab_place),
        ),
        tab, onTab,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        when (tab) {
            EditorTab.DATA -> DataTab(widget, dispatch)
            EditorTab.LOOK -> LookTab(widget, dispatch)
            EditorTab.PLACE -> PlaceTab(state, widget, dispatch)
        }
    }
}

@Composable
private fun MetricGrid(selected: VideoMetric?, onPick: (VideoMetric) -> Unit) {
    VideoMetric.entries.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { metric ->
                val text = overlayMetricTileText(videoMetricLabel(metric))
                Tile(text.title, metric == selected, Modifier.weight(1f), height = 56, source = text.source) { onPick(metric) }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun DataTab(widget: OverlayWidget, dispatch: (OverlayEditorAction) -> Unit) {
    MetricGrid(widget.metric) { dispatch(OverlayEditorAction.SetMetric(it)) }
    SectionLabel(stringResource(Res.string.video_editor_label))
    val automatic = isAutoOverlayLabel(widget)
    BasicTextField(
        value = if (automatic) "" else widget.label,
        onValueChange = { dispatch(OverlayEditorAction.SetLabel(it)) },
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 15.sp),
        cursorBrush = SolidColor(Accent),
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(13.dp)).background(Fill).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (automatic) Text(stringResource(Res.string.video_editor_label_auto), color = Muted, fontSize = 15.sp)
                inner()
            }
        },
    )
}

@Composable
private fun LookTab(widget: OverlayWidget, dispatch: (OverlayEditorAction) -> Unit) {
    val placedSize = listOf(
        OverlayWidgetSize.S to Res.string.video_editor_size_s, OverlayWidgetSize.M to Res.string.video_editor_size_m,
        OverlayWidgetSize.L to Res.string.video_editor_size_l, OverlayWidgetSize.XL to Res.string.video_editor_size_xl,
    )
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        placedSize.forEach { (size, label) -> Tile(stringResource(label), widget.size == size, Modifier.weight(1f)) { dispatch(OverlayEditorAction.SetSize(size)) } }
    }
    val dial = widget.kind == OverlayWidgetKind.ARC || widget.kind == OverlayWidgetKind.ARC_LEFT || widget.kind == OverlayWidgetKind.ARC_RIGHT
    // The chip is its own kind; the card tile also stands for the older labelled card with an icon (ICON_VALUE).
    // Five kinds do not fit a phone's width whole («Карточ-ка» broke on the emulator), so the row scrolls.
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(OverlayWidgetKind.VALUE_CARD, Res.string.video_editor_kind_card, widget.kind == OverlayWidgetKind.VALUE_CARD || widget.kind == OverlayWidgetKind.ICON_VALUE),
            Triple(OverlayWidgetKind.ICON_CHIP, Res.string.video_editor_kind_chip, widget.kind == OverlayWidgetKind.ICON_CHIP),
            Triple(OverlayWidgetKind.ARC, Res.string.video_editor_kind_dial, dial),
            Triple(OverlayWidgetKind.BAR, Res.string.video_editor_kind_bar, widget.kind == OverlayWidgetKind.BAR),
            Triple(OverlayWidgetKind.TEXT, Res.string.video_editor_kind_text, widget.kind == OverlayWidgetKind.TEXT),
        ).forEach { (kind, label, on) ->
            Tile(stringResource(label), on, Modifier.widthIn(min = 84.dp), enabled = overlayKindAllowed(kind, widget.metric)) {
                if (!on) dispatch(OverlayEditorAction.SetKind(kind))
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Swatches.forEach { (color, name) ->
            val hex = OverlayPalette.hex(color)
            val on = widget.style.accent == hex
            Column(
                Modifier.width(54.dp).clickable(role = Role.Button) { dispatch(OverlayEditorAction.SetAccent(hex)) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Unselected swatches carry a hairline, so the white one still reads on a light sheet.
                Box(
                    Modifier.size(46.dp)
                        .then(if (on) Modifier.border(2.dp, Ink, CircleShape).padding(4.dp) else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        .clip(CircleShape).background(Color(color)),
                )
                Text(stringResource(name), color = if (on) Ink else Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
    if (widget.kind == OverlayWidgetKind.ICON_CHIP || widget.kind == OverlayWidgetKind.ICON_VALUE) IconPicker(widget, dispatch)
}

/**
 * The chip's icon: "by metric" first, then telemetry-studio's icon list in its own groups, drawn from the same
 * Font Awesome glyphs the video burns in.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconPicker(widget: OverlayWidget, dispatch: (OverlayEditorAction) -> Unit) {
    val context = LocalContext.current
    val face = remember { runCatching { android.graphics.Typeface.createFromAsset(context.assets, OverlayFonts.ICON_FONT) }.getOrNull() }
    val family = remember(face) { face?.let { FontFamily(it) } }
    val auto = overlayIconIsAuto(widget)
    val chosen = overlayIconChosen(widget)
    SectionLabel(stringResource(Res.string.video_editor_icon))
    FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        IconChoice(OverlayIcons.byName(autoOverlayIconName(widget.metric)), family, stringResource(Res.string.video_editor_icon_auto), auto, wide = true) {
            dispatch(OverlayEditorAction.SetIcon(null))
        }
    }
    OverlayIconGroup.entries.forEach { group ->
        val icons = OverlayIcons.all.filter { it.group == group }
        SectionLabel(stringResource(iconGroupLabel(group)))
        FlowRow(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            icons.forEach { info ->
                IconChoice(info, family, overlayIconLabel(info.name), !auto && chosen == info.name) {
                    dispatch(OverlayEditorAction.SetIcon(info.name))
                }
            }
        }
    }
}

@Composable
private fun IconChoice(info: OverlayIconInfo?, family: FontFamily?, label: String, on: Boolean, wide: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.height(48.dp).then(if (wide) Modifier else Modifier.width(48.dp)).clip(shape)
            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Fill)
            .then(if (on) Modifier.border(2.dp, Accent, shape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (info != null && family != null) {
            Text(info.codepoint.toString(), fontFamily = family, fontSize = 20.sp, lineHeight = 20.sp, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else Ink)
        }
        if (wide) Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else Ink)
    }
}

@Composable
private fun PlaceTab(state: OverlayEditorState, widget: OverlayWidget, dispatch: (OverlayEditorAction) -> Unit) {
    val box = widget.placement?.let { if (state.landscape) it.landscape else it.portrait }
    val hidden = widget.placement != null && box == null
    box?.let { Text(stringResource(anchorLabel(it.anchor)), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp)) }
    if (!hidden) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val step = OVERLAY_NUDGE_STEP
            listOf(
                Triple(Icons.Default.KeyboardArrowUp, Res.string.video_editor_move_up, Offset(0f, -step)),
                Triple(Icons.Default.KeyboardArrowDown, Res.string.video_editor_move_down, Offset(0f, step)),
                Triple(Icons.Default.KeyboardArrowLeft, Res.string.video_editor_move_left, Offset(-step, 0f)),
                Triple(Icons.Default.KeyboardArrowRight, Res.string.video_editor_move_right, Offset(step, 0f)),
            ).forEach { (icon, label, move) ->
                Box(
                    Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(13.dp)).background(Fill)
                        .clickable(role = Role.Button) { dispatch(OverlayEditorAction.Nudge(move.x, move.y)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, stringResource(label), tint = Ink, modifier = Modifier.size(28.dp)) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Tile(stringResource(Res.string.video_editor_restore), false, Modifier.fillMaxWidth()) { dispatch(OverlayEditorAction.RestorePlace) }
        Tile(
            stringResource(if (hidden) Res.string.video_editor_show_here else Res.string.video_editor_hide_here), false, Modifier.fillMaxWidth(),
        ) { dispatch(OverlayEditorAction.SetHidden(!hidden)) }
    }
}

@Composable
private fun DragPanel(state: OverlayEditorState, drag: OverlayEditorDrag) {
    val widget = state.draft.widgets.firstOrNull { it.id == drag.id }
    val box = widget?.placement?.let { if (state.landscape) it.landscape else it.portrait }
    Row(Modifier.fillMaxWidth().height(38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(box?.let { stringResource(anchorLabel(it.anchor)) }.orEmpty(), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (drag.guides.isNotEmpty()) Text(stringResource(Res.string.video_editor_snapped), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
    }
    Text(stringResource(Res.string.video_editor_drag_hint), color = Muted, fontSize = 13.sp)
}

@Composable
private fun AddPanel(onPick: (VideoMetric) -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.video_editor_add_title), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        IconTarget(Icons.Default.Close, stringResource(Res.string.video_editor_close), onClick = onCancel)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { MetricGrid(null, onPick) }
}

@Composable
internal fun EditorConfirm(confirm: OverlayEditorConfirm, state: OverlayEditorState, onSave: () -> Unit, onDiscard: () -> Unit, onCancel: () -> Unit) {
    if (confirm is OverlayEditorConfirm.Delete) {
        VideoDecisionSheet(
            title = stringResource(Res.string.video_editor_delete_title),
            text = stringResource(Res.string.video_editor_delete_text),
            primary = null, onPrimary = {},
            destructive = stringResource(Res.string.video_editor_delete), onDestructive = onDiscard,
            neutral = stringResource(Res.string.video_editor_lose_keep), onNeutral = onCancel,
        )
    } else {
        VideoDecisionSheet(
            title = stringResource(Res.string.video_editor_lose_title),
            text = stringResource(Res.string.video_editor_lose_text),
            primary = stringResource(Res.string.video_editor_lose_save).takeIf { state.canOverwrite || state.canSaveCopy }, onPrimary = onSave,
            destructive = stringResource(Res.string.video_editor_lose_discard), onDestructive = onDiscard,
            neutral = stringResource(Res.string.video_editor_lose_keep), onNeutral = onCancel,
        )
    }
}

@Composable
private fun NameSheet(initial: String, onName: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    VideoSheet(onDismiss) {
        Text(stringResource(Res.string.video_editor_name_title), color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        BasicTextField(
            value = name,
            onValueChange = { name = it.take(64) },
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 17.sp),
            cursorBrush = SolidColor(Accent),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(13.dp)).background(Fill).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
        VideoSheetButton(stringResource(Res.string.video_editor_save), SheetButtonKind.PRIMARY, enabled = name.isNotBlank(), onClick = { onName(name) })
    }
}

internal fun builtinName(preset: OverlayPreset): StringResource = when (preset.id) {
    "top-row" -> Res.string.video_editor_template_top_row
    "hud" -> Res.string.video_editor_template_hud
    "minimal" -> Res.string.video_editor_template_minimal
    "studio-panel" -> Res.string.video_editor_template_panel
    "studio-large" -> Res.string.video_editor_template_large
    "ts-meow-icons" -> Res.string.video_editor_template_meow_icons
    "ts-meow-icons-trip" -> Res.string.video_editor_template_meow_icons_trip
    else -> Res.string.video_editor_template_racing
}

private fun kindLabel(kind: OverlayWidgetKind): StringResource = when (kind) {
    OverlayWidgetKind.VALUE_CARD, OverlayWidgetKind.ICON_VALUE -> Res.string.video_editor_kind_card
    OverlayWidgetKind.ICON_CHIP -> Res.string.video_editor_kind_chip
    OverlayWidgetKind.ARC, OverlayWidgetKind.ARC_LEFT, OverlayWidgetKind.ARC_RIGHT -> Res.string.video_editor_kind_dial
    OverlayWidgetKind.BAR -> Res.string.video_editor_kind_bar
    OverlayWidgetKind.TEXT -> Res.string.video_editor_kind_text
}

private fun anchorLabel(anchor: OverlayAnchor): StringResource = when (anchor) {
    OverlayAnchor.TOP_START -> Res.string.video_anchor_top_start
    OverlayAnchor.TOP_CENTER -> Res.string.video_anchor_top_center
    OverlayAnchor.TOP_END -> Res.string.video_anchor_top_end
    OverlayAnchor.CENTER_START -> Res.string.video_anchor_center_start
    OverlayAnchor.CENTER -> Res.string.video_anchor_center
    OverlayAnchor.CENTER_END -> Res.string.video_anchor_center_end
    OverlayAnchor.BOTTOM_START -> Res.string.video_anchor_bottom_start
    OverlayAnchor.BOTTOM_CENTER -> Res.string.video_anchor_bottom_center
    OverlayAnchor.BOTTOM_END -> Res.string.video_anchor_bottom_end
}

@Composable
internal fun videoMetricLabel(metric: VideoMetric): String = stringResource(when (metric) {
    VideoMetric.SPEED -> Res.string.video_editor_metric_speed
    VideoMetric.DUTY -> Res.string.video_editor_metric_duty
    VideoMetric.ESC_TEMPERATURE -> Res.string.video_editor_metric_esc_temperature
    VideoMetric.MOTOR_TEMPERATURE -> Res.string.video_editor_metric_motor_temperature
    VideoMetric.MOTOR_CURRENT -> Res.string.video_editor_metric_motor_current
    VideoMetric.CONTROLLER_CURRENT -> Res.string.video_editor_metric_controller_current
    VideoMetric.CONTROLLER_POWER -> Res.string.video_editor_metric_controller_power
    VideoMetric.SOC -> Res.string.video_editor_metric_soc
    VideoMetric.VOLTAGE -> Res.string.video_editor_metric_voltage
    VideoMetric.BATTERY_CURRENT -> Res.string.video_editor_metric_battery_current
    VideoMetric.BATTERY_POWER -> Res.string.video_editor_metric_battery_power
    VideoMetric.BATTERY_TEMPERATURE -> Res.string.video_editor_metric_battery_temperature
    VideoMetric.ELAPSED -> Res.string.video_editor_metric_elapsed
    VideoMetric.DISTANCE -> Res.string.video_editor_metric_distance
})

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
import androidx.compose.material3.Icon
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

private val Panel = Color(OverlayPalette.PANEL)
private val Sheet = Color(0xFF0B1C28)
private val Ink = Color(OverlayPalette.TEXT)
private val Muted = Color(OverlayPalette.MUTED)
private val Accent = Color(OverlayPalette.CYAN)
private val Danger = Color(OverlayPalette.PINK)
private val Warn = Color(OverlayPalette.ORANGE)
private val Fill = Color(0x403E5C6C)
private val OnAccent = Color(0xFF04202A)

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
            Text(overlayPresetName(state.draft, state.document.saved), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                state.edited -> Text(stringResource(Res.string.video_editor_changed), color = Warn, fontSize = 11.sp, maxLines = 1)
                state.isBuiltIn -> Text(stringResource(Res.string.video_editor_builtin_note), color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconTarget(Icons.AutoMirrored.Filled.Undo, stringResource(Res.string.video_editor_undo), enabled = state.canUndo) { dispatch(OverlayEditorAction.Undo) }
        IconTarget(Icons.AutoMirrored.Filled.Redo, stringResource(Res.string.video_editor_redo), enabled = state.canRedo) { dispatch(OverlayEditorAction.Redo) }
        val enabled = state.edited && (state.canOverwrite || state.canSaveCopy)
        Box(
            Modifier.height(44.dp).alpha(if (enabled) 1f else 0.4f).clip(RoundedCornerShape(22.dp)).background(Accent)
                .clickable(enabled = enabled, role = Role.Button, onClick = onSave).padding(horizontal = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(Res.string.video_editor_save), color = OnAccent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IconTarget(icon: ImageVector, description: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).alpha(if (enabled) 1f else 0.32f).clip(CircleShape)
            .background(if (selected) Accent else Color.Transparent)
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

    BoxWithConstraints(modifier.background(Color(0xFF040B11))) {
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
            IconTarget(Icons.Default.CropPortrait, stringResource(Res.string.video_editor_portrait), selected = !state.landscape) {
                dispatch(OverlayEditorAction.SetOrientation(false))
            }
            IconTarget(Icons.Default.Crop169, stringResource(Res.string.video_editor_landscape), selected = state.landscape) {
                dispatch(OverlayEditorAction.SetOrientation(true))
            }
            IconTarget(Icons.Default.Wallpaper, stringResource(Res.string.video_editor_background), onClick = onBackdrop)
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
                        if (guide.vertical) drawLine(Danger, Offset(p, 0f), Offset(p, size.height), 7f * k)
                        else drawLine(Danger, Offset(0f, p), Offset(size.width, p), 7f * k)
                    }
                }
                if (selected != null) {
                    val inset = 10f * k
                    drawRoundRect(
                        Accent, Offset(selected.left * k - inset, selected.top * k - inset),
                        Size((selected.right - selected.left) * k + 2 * inset, (selected.bottom - selected.top) * k + 2 * inset),
                        CornerRadius(28f * k), style = Stroke(10f * k),
                    )
                }
            }
            Text(
                stringResource(Res.string.video_editor_sample),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 7.dp, vertical = 5.dp),
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
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF5A7482)))
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

@Composable
private fun Tile(text: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, height: Int = 52, onClick: () -> Unit) {
    Box(
        modifier.height(height.dp).alpha(if (enabled) 1f else 0.35f).clip(RoundedCornerShape(13.dp)).background(if (on) Accent else Fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (on) OnAccent else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp)
    }
}

@Composable
private fun OverviewPanel(state: OverlayEditorState, onAdd: () -> Unit, onDuplicate: () -> Unit, dispatch: (OverlayEditorAction) -> Unit) {
    val builtIns = remember { OverlayTemplates.all() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(Res.string.video_editor_preset))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            (builtIns + state.document.saved).forEach { preset ->
                Tile(overlayPresetName(preset, state.document.saved), preset.id == state.draft.id, Modifier.widthIn(min = 92.dp), height = 56) {
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
                Box(Modifier.size(10.dp).clip(CircleShape).background(widget.dot()))
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

/** The metric's automatic labels for the widget list; the file's own burned-in words come from the capture screen. */
@Composable
private fun widgetLabelStrings() = VideoOverlayStrings(labels = VideoMetric.entries.associateWith { videoMetricLabel(it) })

@Composable
private fun WidgetPanel(state: OverlayEditorState, widget: OverlayWidget, tab: EditorTab, onTab: (EditorTab) -> Unit, dispatch: (OverlayEditorAction) -> Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(overlayLabel(widget, widgetLabelStrings()), color = widget.dot(), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconTarget(Icons.Default.Delete, stringResource(Res.string.video_editor_delete), enabled = state.draft.widgets.size > 1) { dispatch(OverlayEditorAction.DeleteWidget) }
        IconTarget(Icons.Default.Close, stringResource(Res.string.video_editor_close)) { dispatch(OverlayEditorAction.Select(null)) }
    }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(EditorTab.DATA to Res.string.video_editor_tab_data, EditorTab.LOOK to Res.string.video_editor_tab_look, EditorTab.PLACE to Res.string.video_editor_tab_place)
            .forEach { (value, label) -> Tile(stringResource(label), tab == value, Modifier.weight(1f), height = 42) { onTab(value) } }
    }
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
            row.forEach { metric -> Tile(videoMetricLabel(metric), metric == selected, Modifier.weight(1f), height = 56) { onPick(metric) } }
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
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Triple(OverlayWidgetKind.VALUE_CARD, Res.string.video_editor_kind_card, widget.kind == OverlayWidgetKind.VALUE_CARD || widget.kind == OverlayWidgetKind.ICON_VALUE),
            Triple(OverlayWidgetKind.ARC, Res.string.video_editor_kind_dial, dial),
            Triple(OverlayWidgetKind.BAR, Res.string.video_editor_kind_bar, widget.kind == OverlayWidgetKind.BAR),
            Triple(OverlayWidgetKind.TEXT, Res.string.video_editor_kind_text, widget.kind == OverlayWidgetKind.TEXT),
        ).forEach { (kind, label, on) ->
            Tile(stringResource(label), on, Modifier.weight(1f), enabled = overlayKindAllowed(kind, widget.metric)) {
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
                Box(
                    Modifier.size(46.dp).then(if (on) Modifier.border(2.dp, Color.White, CircleShape).padding(4.dp) else Modifier)
                        .clip(CircleShape).background(Color(color)),
                )
                Text(stringResource(name), color = if (on) Color.White else Muted, fontSize = 10.sp, maxLines = 1)
            }
        }
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
        if (drag.guides.isNotEmpty()) Text(stringResource(Res.string.video_editor_snapped), color = Danger, fontSize = 13.sp)
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
        VideoSheetButton(stringResource(Res.string.video_editor_save), Accent, OnAccent, enabled = name.isNotBlank(), onClick = { onName(name) })
    }
}

internal fun builtinName(preset: OverlayPreset): StringResource = when (preset.id) {
    "top-row" -> Res.string.video_editor_template_top_row
    "hud" -> Res.string.video_editor_template_hud
    "minimal" -> Res.string.video_editor_template_minimal
    "studio-panel" -> Res.string.video_editor_template_panel
    "studio-large" -> Res.string.video_editor_template_large
    else -> Res.string.video_editor_template_racing
}

private fun kindLabel(kind: OverlayWidgetKind): StringResource = when (kind) {
    OverlayWidgetKind.VALUE_CARD, OverlayWidgetKind.ICON_VALUE -> Res.string.video_editor_kind_card
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

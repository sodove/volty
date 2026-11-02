package ru.sodovaya.volty.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.video.OverlayComparison
import ru.sodovaya.volty.domain.video.OverlayTextDraft
import ru.sodovaya.volty.domain.video.overlayColorAlpha
import ru.sodovaya.volty.domain.video.overlayColorWithAlpha
import ru.sodovaya.volty.domain.video.OverlayDocument
import ru.sodovaya.volty.domain.video.OverlayIcon
import ru.sodovaya.volty.domain.video.OverlayCodec
import ru.sodovaya.volty.domain.video.OverlayPreset
import ru.sodovaya.volty.domain.video.OverlayRule
import ru.sodovaya.volty.domain.video.OverlayStyle
import ru.sodovaya.volty.domain.video.OverlayTemplates
import ru.sodovaya.volty.domain.video.OverlayWidget
import ru.sodovaya.volty.domain.video.OverlayWidgetKind
import ru.sodovaya.volty.domain.video.VideoMetric
import ru.sodovaya.volty.domain.video.validOverlayPreset
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.video_editor_add_rule
import volty.composeapp.generated.resources.video_editor_add_widget
import volty.composeapp.generated.resources.video_editor_appearance
import volty.composeapp.generated.resources.video_editor_arc
import volty.composeapp.generated.resources.video_editor_arc_max
import volty.composeapp.generated.resources.video_editor_arc_min
import volty.composeapp.generated.resources.video_editor_arc_radius
import volty.composeapp.generated.resources.video_editor_arc_thickness
import volty.composeapp.generated.resources.video_editor_arc_x
import volty.composeapp.generated.resources.video_editor_arc_y
import volty.composeapp.generated.resources.video_editor_background
import volty.composeapp.generated.resources.video_editor_center_cards
import volty.composeapp.generated.resources.video_editor_clear_color
import volty.composeapp.generated.resources.video_editor_close
import volty.composeapp.generated.resources.video_editor_comparison
import volty.composeapp.generated.resources.video_editor_comparison_eq
import volty.composeapp.generated.resources.video_editor_comparison_gt
import volty.composeapp.generated.resources.video_editor_comparison_gte
import volty.composeapp.generated.resources.video_editor_comparison_lt
import volty.composeapp.generated.resources.video_editor_comparison_lte
import volty.composeapp.generated.resources.video_editor_comparison_ne
import volty.composeapp.generated.resources.video_editor_corner_radius
import volty.composeapp.generated.resources.video_editor_delete
import volty.composeapp.generated.resources.video_editor_font_size
import volty.composeapp.generated.resources.video_editor_foreground
import volty.composeapp.generated.resources.video_editor_kind
import volty.composeapp.generated.resources.video_editor_kind_arc
import volty.composeapp.generated.resources.video_editor_kind_icon_value
import volty.composeapp.generated.resources.video_editor_kind_value_card
import volty.composeapp.generated.resources.video_editor_icon
import volty.composeapp.generated.resources.video_editor_icon_auto
import volty.composeapp.generated.resources.video_editor_icon_battery
import volty.composeapp.generated.resources.video_editor_icon_bolt
import volty.composeapp.generated.resources.video_editor_icon_current
import volty.composeapp.generated.resources.video_editor_icon_gauge
import volty.composeapp.generated.resources.video_editor_icon_temperature
import volty.composeapp.generated.resources.video_editor_icon_warning
import volty.composeapp.generated.resources.video_editor_label
import volty.composeapp.generated.resources.video_editor_metric
import volty.composeapp.generated.resources.video_editor_move_down
import volty.composeapp.generated.resources.video_editor_move_up
import volty.composeapp.generated.resources.video_editor_no_saved_templates
import volty.composeapp.generated.resources.video_editor_opacity
import volty.composeapp.generated.resources.video_editor_padding
import volty.composeapp.generated.resources.video_editor_remove_widget
import volty.composeapp.generated.resources.video_editor_rule_background
import volty.composeapp.generated.resources.video_editor_rule_foreground
import volty.composeapp.generated.resources.video_editor_rule_number
import volty.composeapp.generated.resources.video_editor_rules
import volty.composeapp.generated.resources.video_editor_save_template
import volty.composeapp.generated.resources.video_editor_saved_templates
import volty.composeapp.generated.resources.video_editor_shadow
import volty.composeapp.generated.resources.video_editor_template_hud
import volty.composeapp.generated.resources.video_editor_template_minimal
import volty.composeapp.generated.resources.video_editor_template_name
import volty.composeapp.generated.resources.video_editor_template_racing
import volty.composeapp.generated.resources.video_editor_template_top_row
import volty.composeapp.generated.resources.video_editor_templates
import volty.composeapp.generated.resources.video_editor_threshold
import volty.composeapp.generated.resources.video_editor_title
import volty.composeapp.generated.resources.video_editor_unit
import volty.composeapp.generated.resources.video_editor_widget_number
import volty.composeapp.generated.resources.video_editor_widgets
import volty.composeapp.generated.resources.video_editor_metric_battery_current
import volty.composeapp.generated.resources.video_editor_metric_battery_power
import volty.composeapp.generated.resources.video_editor_metric_battery_temperature
import volty.composeapp.generated.resources.video_editor_metric_controller_current
import volty.composeapp.generated.resources.video_editor_metric_controller_power
import volty.composeapp.generated.resources.video_editor_metric_duty
import volty.composeapp.generated.resources.video_editor_metric_esc_temperature
import volty.composeapp.generated.resources.video_editor_metric_motor_current
import volty.composeapp.generated.resources.video_editor_metric_motor_temperature
import volty.composeapp.generated.resources.video_editor_metric_soc
import volty.composeapp.generated.resources.video_editor_metric_speed
import volty.composeapp.generated.resources.video_editor_metric_voltage

private const val MAX_SAVED_TEMPLATES = 32

private val backgroundSwatches = listOf("#B320252A", "#D9000000", "#CC1A2B39", "#C92E3945", "#B31B6A55")
private val foregroundSwatches = listOf("#FFFFFFFF", "#FF42D392", "#FFFFC857", "#FF69C5FF", "#FFFF7070")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoOverlayEditor(
    document: OverlayDocument,
    onChange: (OverlayDocument) -> Unit,
    onClose: () -> Unit,
) {
    val builtIns = remember { OverlayTemplates.all() }
    var newTemplateName by remember(document.selected.id) { mutableStateOf("") }
    val templateDraftId = remember(document.selected.id) { UUID.randomUUID().toString() }
    val additionPreviewId = remember(document.selected.id, document.selected.widgets.map { it.id }) {
        UUID.randomUUID().toString()
    }
    val defaultMetric = VideoMetric.SPEED
    val defaultMetricLabel = videoMetricLabel(defaultMetric)
    val templateDraft = document.selected.copy(id = templateDraftId, name = newTemplateName.trim())
    val canSaveTemplate = newTemplateName.isNotBlank() &&
        document.saved.size < MAX_SAVED_TEMPLATES &&
        isValidDocument(document.copy(saved = document.saved + templateDraft))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.video_editor_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text(stringResource(Res.string.video_editor_close)) }
        }

        Card {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorSectionTitle(stringResource(Res.string.video_editor_templates))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    builtIns.forEach { preset ->
                        FilterChip(
                            selected = document.selected.id == preset.id,
                            onClick = { publish(document.copy(selected = preset), onChange) },
                            label = { Text(stringResource(builtinName(preset))) },
                        )
                    }
                }

                HorizontalDivider()
                EditorSectionTitle(stringResource(Res.string.video_editor_saved_templates))
                if (document.saved.isEmpty()) {
                    EditorCaption(stringResource(Res.string.video_editor_no_saved_templates))
                } else {
                    document.saved.forEach { preset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = document.selected.id == preset.id,
                                onClick = { publish(document.copy(selected = preset), onChange) },
                                label = { Text(preset.name, maxLines = 1) },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    val remaining = document.saved.filterNot { it.id == preset.id }
                                    val selected = if (document.selected.id == preset.id) OverlayTemplates.hud() else document.selected
                                    publish(document.copy(selected = selected, saved = remaining), onChange)
                                },
                            ) { Text(stringResource(Res.string.video_editor_delete)) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = newTemplateName,
                        onValueChange = { newTemplateName = it },
                        label = { Text(stringResource(Res.string.video_editor_template_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val name = newTemplateName.trim()
                            if (name.isNotEmpty() && document.saved.size < MAX_SAVED_TEMPLATES) {
                                val savedPreset = document.selected.copy(id = UUID.randomUUID().toString(), name = name)
                                val candidate = document.copy(saved = document.saved + savedPreset)
                                if (isValidDocument(candidate)) {
                                    onChange(candidate)
                                    newTemplateName = ""
                                }
                            }
                        },
                        enabled = canSaveTemplate,
                    ) { Text(stringResource(Res.string.video_editor_save_template)) }
                }
            }
        }

        SwitchSetting(
            label = stringResource(Res.string.video_editor_center_cards),
            checked = document.selected.centerCards,
            onCheckedChange = { centered ->
                publish(document.copy(selected = document.selected.copy(centerCards = centered)), onChange)
            },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditorSectionTitle(stringResource(Res.string.video_editor_widgets), Modifier.weight(1f))
            val canAddWidget = validOverlayPreset(
                document.selected.copy(
                    widgets = document.selected.widgets + OverlayWidget(
                        id = additionPreviewId,
                        metric = defaultMetric,
                    ),
                ),
            )
            OutlinedButton(
                onClick = {
                    val widget = OverlayWidget(
                        id = UUID.randomUUID().toString(),
                        metric = defaultMetric,
                        label = defaultMetricLabel,
                    )
                    updatePreset(document, onChange) { it.copy(widgets = it.widgets + widget) }
                },
                enabled = canAddWidget,
            ) { Text(stringResource(Res.string.video_editor_add_widget)) }
        }

        document.selected.widgets.forEachIndexed { index, widget ->
            key(document.selected.id, widget.id) {
            WidgetEditor(
                index = index,
                widget = widget,
                widgetCount = document.selected.widgets.size,
                onUpdate = { changed ->
                    updatePreset(document, onChange) { preset ->
                        preset.copy(widgets = preset.widgets.map { if (it.id == widget.id) changed else it })
                    }
                },
                onMove = { delta ->
                    val destination = index + delta
                    val reordered = document.selected.widgets.toMutableList().apply {
                        add(destination, removeAt(index))
                    }
                    updatePreset(document, onChange) { it.copy(widgets = reordered) }
                },
                onRemove = {
                    updatePreset(document, onChange) { preset ->
                        preset.copy(widgets = preset.widgets.filterNot { it.id == widget.id })
                    }
                },
            )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetEditor(
    index: Int,
    widget: OverlayWidget,
    widgetCount: Int,
    onUpdate: (OverlayWidget) -> Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val canAddRule = remember(widget.rules.size) {
        validOverlayPreset(
            OverlayPreset(
                id = "editor-validation",
                name = "Editor",
                widgets = listOf(widget.copy(rules = widget.rules + OverlayRule(OverlayComparison.GT, 0.0))),
            ),
        )
    }

    Card {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.video_editor_widget_number, index + 1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    TextButton(onClick = { onMove(-1) }, enabled = index > 0) {
                        Text("↑ ${stringResource(Res.string.video_editor_move_up)}")
                    }
                    TextButton(onClick = { onMove(1) }, enabled = index < widgetCount - 1) {
                        Text("↓ ${stringResource(Res.string.video_editor_move_down)}")
                    }
                    TextButton(onClick = onRemove, enabled = widgetCount > 1) {
                        Text(stringResource(Res.string.video_editor_remove_widget))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactSelector(
                    label = stringResource(Res.string.video_editor_metric),
                    selected = widget.metric,
                    choices = VideoMetric.entries,
                    display = { videoMetricLabel(it) },
                    onSelected = { metric ->
                        onUpdate(widget.copy(metric = metric, unit = metric.unit))
                    },
                    modifier = Modifier.weight(1f),
                )
                CompactSelector(
                    label = stringResource(Res.string.video_editor_kind),
                    selected = widget.kind,
                    choices = OverlayWidgetKind.entries,
                    display = { stringResource(kindLabel(it)) },
                    onSelected = { kind -> onUpdate(widget.copy(kind = kind)) },
                    modifier = Modifier.weight(1f),
                )
            }

            if (widget.kind == OverlayWidgetKind.ICON_VALUE) {
                CompactSelector(
                    label = stringResource(Res.string.video_editor_icon),
                    selected = widget.icon,
                    choices = OverlayIcon.entries,
                    display = { stringResource(iconLabel(it)) },
                    onSelected = { icon -> onUpdate(widget.copy(icon = icon)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DraftTextField(
                    value = widget.label,
                    label = stringResource(Res.string.video_editor_label),
                    modifier = Modifier.weight(1f),
                ) { value -> onUpdate(widget.copy(label = value)) }
                DraftTextField(
                    value = widget.unit,
                    label = stringResource(Res.string.video_editor_unit),
                    modifier = Modifier.weight(1f),
                ) { value -> onUpdate(widget.copy(unit = value)) }
            }

            if (widget.kind == OverlayWidgetKind.ARC) {
                HorizontalDivider()
                EditorSectionTitle(stringResource(Res.string.video_editor_arc))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftNumberField(
                        widget.arc.x,
                        stringResource(Res.string.video_editor_arc_x),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(x = value))) }
                    DraftNumberField(
                        widget.arc.y,
                        stringResource(Res.string.video_editor_arc_y),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(y = value))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftNumberField(
                        widget.arc.radius,
                        stringResource(Res.string.video_editor_arc_radius),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(radius = value))) }
                    DraftNumberField(
                        widget.arc.thickness,
                        stringResource(Res.string.video_editor_arc_thickness),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(thickness = value))) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DraftNumberField(
                        widget.arc.min,
                        stringResource(Res.string.video_editor_arc_min),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(min = value))) }
                    DraftNumberField(
                        widget.arc.max,
                        stringResource(Res.string.video_editor_arc_max),
                        Modifier.weight(1f),
                    ) { value -> onUpdate(widget.copy(arc = widget.arc.copy(max = value))) }
                }
            }

            HorizontalDivider()
            EditorSectionTitle(stringResource(Res.string.video_editor_appearance))
            ColorEditor(
                value = widget.style.background,
                label = stringResource(Res.string.video_editor_background),
                swatches = backgroundSwatches,
            ) { color -> onUpdate(widget.copy(style = widget.style.copy(background = color))) }
            ColorEditor(
                value = widget.style.foreground,
                label = stringResource(Res.string.video_editor_foreground),
                swatches = foregroundSwatches,
            ) { color -> onUpdate(widget.copy(style = widget.style.copy(foreground = color))) }
            SliderSetting(
                value = widget.style.background.alphaHex().toFloat(),
                valueRange = 0f..255f,
                steps = 254,
                format = { stringResource(Res.string.video_editor_opacity, (it / 255f * 100f).toInt()) },
            ) { alpha ->
                val background = widget.style.background.withAlpha(alpha.toInt())
                onUpdate(widget.copy(style = widget.style.copy(background = background)))
            }
            SliderSetting(
                value = widget.style.fontSize,
                valueRange = 8f..96f,
                format = { stringResource(Res.string.video_editor_font_size, it.toInt()) },
            ) { value -> onUpdate(widget.copy(style = widget.style.copy(fontSize = value))) }
            SliderSetting(
                value = widget.style.cornerRadius,
                valueRange = 0f..64f,
                format = { stringResource(Res.string.video_editor_corner_radius, it.toInt()) },
            ) { value -> onUpdate(widget.copy(style = widget.style.copy(cornerRadius = value))) }
            SliderSetting(
                value = widget.style.padding,
                valueRange = 0f..100f,
                format = { stringResource(Res.string.video_editor_padding, it.toInt()) },
            ) { value -> onUpdate(widget.copy(style = widget.style.copy(padding = value))) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.video_editor_shadow), modifier = Modifier.weight(1f))
                Switch(
                    checked = widget.style.shadow,
                    onCheckedChange = { enabled -> onUpdate(widget.copy(style = widget.style.copy(shadow = enabled))) },
                )
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorSectionTitle(stringResource(Res.string.video_editor_rules), Modifier.weight(1f))
                OutlinedButton(
                    onClick = {
                        onUpdate(widget.copy(rules = widget.rules + OverlayRule(OverlayComparison.GT, 50.0)))
                    },
                    enabled = canAddRule,
                ) { Text(stringResource(Res.string.video_editor_add_rule)) }
            }
            widget.rules.forEachIndexed { ruleIndex, rule ->
                key(ruleIndex, widget.rules.size) {
                RuleEditor(
                    index = ruleIndex,
                    rule = rule,
                    onUpdate = { changed ->
                        onUpdate(widget.copy(rules = widget.rules.mapIndexed { i, old -> if (i == ruleIndex) changed else old }))
                    },
                    onRemove = { onUpdate(widget.copy(rules = widget.rules.filterIndexed { i, _ -> i != ruleIndex })) },
                )
                }
            }
        }
    }
}

@Composable
private fun RuleEditor(
    index: Int,
    rule: OverlayRule,
    onUpdate: (OverlayRule) -> Boolean,
    onRemove: () -> Unit,
) {
    Card(shape = RoundedCornerShape(12.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.video_editor_rule_number, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRemove) { Text(stringResource(Res.string.video_editor_remove_widget)) }
            }
            CompactSelector(
                label = stringResource(Res.string.video_editor_comparison),
                selected = rule.comparison,
                choices = OverlayComparison.entries,
                display = { stringResource(comparisonLabel(it)) },
                onSelected = { comparison -> onUpdate(rule.copy(comparison = comparison)) },
                modifier = Modifier.fillMaxWidth(),
            )
            DraftNumberField(
                value = rule.threshold,
                label = stringResource(Res.string.video_editor_threshold),
                modifier = Modifier.fillMaxWidth(),
            ) { value -> onUpdate(rule.copy(threshold = value.toDouble())) }
            NullableColorEditor(
                value = rule.foreground,
                label = stringResource(Res.string.video_editor_rule_foreground),
                swatches = foregroundSwatches,
            ) { color -> onUpdate(rule.copy(foreground = color)) }
            NullableColorEditor(
                value = rule.background,
                label = stringResource(Res.string.video_editor_rule_background),
                swatches = backgroundSwatches,
            ) { color -> onUpdate(rule.copy(background = color)) }
        }
    }
}

@Composable
private fun ColorEditor(
    value: String,
    label: String,
    swatches: List<String>,
    onValue: (String) -> Boolean,
) {
    DraftTextField(
        value = value,
        label = label,
        keyboardType = KeyboardType.Ascii,
        modifier = Modifier.fillMaxWidth(),
    ) { color -> onValue(color.uppercase()) }
    ColorSwatches(swatches = swatches, selected = value, onSelected = { onValue(it.uppercase()) })
}

@Composable
private fun NullableColorEditor(
    value: String?,
    label: String,
    swatches: List<String>,
    onValue: (String?) -> Boolean,
) {
    DraftTextField(
        value = value.orEmpty(),
        label = label,
        keyboardType = KeyboardType.Ascii,
        modifier = Modifier.fillMaxWidth(),
    ) { color -> onValue(color.takeIf(String::isNotBlank)?.uppercase()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ColorSwatches(swatches = swatches, selected = value, onSelected = { onValue(it) })
        TextButton(onClick = { onValue(null) }) { Text(stringResource(Res.string.video_editor_clear_color)) }
    }
}

@Composable
private fun ColorSwatches(swatches: List<String>, selected: String?, onSelected: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        swatches.forEach { colorValue ->
            val color = remember(colorValue) { colorValue.toComposeColor() }
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(color, RoundedCornerShape(7.dp))
                    .clickable { onSelected(colorValue) },
            ) {
                if (selected.equals(colorValue, ignoreCase = true)) {
                    Text("✓", color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }
}

@Composable
private fun DraftTextField(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    onCandidate: (String) -> Boolean,
) {
    var draft by remember { mutableStateOf(OverlayTextDraft(value)) }
    LaunchedEffect(value) { draft = draft.synced(value) }
    OutlinedTextField(
        value = draft.text,
        onValueChange = { candidate ->
            draft = draft.edited(candidate, runCatching { onCandidate(candidate) }.getOrDefault(false))
        },
        label = { Text(label) },
        singleLine = true,
        isError = draft.invalid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.onFocusChanged { draft = draft.copy(focused = it.isFocused) },
    )
}

@Composable
private fun DraftNumberField(
    value: Float,
    label: String,
    modifier: Modifier = Modifier,
    onCandidate: (Float) -> Boolean,
) {
    DraftTextField(
        value = value.toString(),
        label = label,
        keyboardType = KeyboardType.Decimal,
        modifier = modifier,
    ) { candidate ->
        candidate.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)?.let { onCandidate(it.toFloat()) }
            ?: false
    }
}

@Composable
private fun DraftNumberField(
    value: Double,
    label: String,
    modifier: Modifier = Modifier,
    onCandidate: (Double) -> Boolean,
) {
    DraftTextField(
        value = value.toString(),
        label = label,
        keyboardType = KeyboardType.Decimal,
        modifier = modifier,
    ) { candidate ->
        candidate.replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)?.let(onCandidate) ?: false
    }
}

@Composable
private fun CompactSelector(
    label: String,
    selected: VideoMetric,
    choices: List<VideoMetric>,
    display: @Composable (VideoMetric) -> String,
    onSelected: (VideoMetric) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorMenu(label, display(selected), choices, display, onSelected, modifier)
}

@Composable
private fun CompactSelector(
    label: String,
    selected: OverlayWidgetKind,
    choices: List<OverlayWidgetKind>,
    display: @Composable (OverlayWidgetKind) -> String,
    onSelected: (OverlayWidgetKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorMenu(label, display(selected), choices, display, onSelected, modifier)
}

@Composable
private fun CompactSelector(
    label: String,
    selected: OverlayIcon,
    choices: List<OverlayIcon>,
    display: @Composable (OverlayIcon) -> String,
    onSelected: (OverlayIcon) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorMenu(label, display(selected), choices, display, onSelected, modifier)
}

@Composable
private fun CompactSelector(
    label: String,
    selected: OverlayComparison,
    choices: List<OverlayComparison>,
    display: @Composable (OverlayComparison) -> String,
    onSelected: (OverlayComparison) -> Unit,
    modifier: Modifier = Modifier,
) {
    SelectorMenu(label, display(selected), choices, display, onSelected, modifier)
}

@Composable
private fun <T> SelectorMenu(
    label: String,
    current: String,
    choices: List<T>,
    display: @Composable (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(current, maxLines = 3, modifier = Modifier.weight(1f))
                Text("⌄")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(display(choice)) },
                        onClick = {
                            expanded = false
                            onSelected(choice)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderSetting(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    format: @Composable (Float) -> String,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(format(value), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.coerceIn(valueRange),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun SwitchSetting(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun EditorSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
private fun EditorCaption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun updatePreset(
    document: OverlayDocument,
    onChange: (OverlayDocument) -> Unit,
    transform: (OverlayPreset) -> OverlayPreset,
): Boolean {
    return publish(document.copy(selected = transform(document.selected)), onChange)
}

private fun publish(document: OverlayDocument, onChange: (OverlayDocument) -> Unit): Boolean =
    if (isValidDocument(document)) {
        onChange(document)
        true
    } else false

private fun isValidDocument(document: OverlayDocument): Boolean =
    runCatching { OverlayCodec.encode(document) }.isSuccess

private fun builtinName(preset: OverlayPreset): StringResource = when (preset.name) {
    "Top Row" -> Res.string.video_editor_template_top_row
    "HUD" -> Res.string.video_editor_template_hud
    "Minimal" -> Res.string.video_editor_template_minimal
    else -> Res.string.video_editor_template_racing
}

private fun kindLabel(kind: OverlayWidgetKind): StringResource = when (kind) {
    OverlayWidgetKind.VALUE_CARD -> Res.string.video_editor_kind_value_card
    OverlayWidgetKind.ICON_VALUE -> Res.string.video_editor_kind_icon_value
    OverlayWidgetKind.ARC -> Res.string.video_editor_kind_arc
}

private fun comparisonLabel(comparison: OverlayComparison): StringResource = when (comparison) {
    OverlayComparison.GT -> Res.string.video_editor_comparison_gt
    OverlayComparison.GTE -> Res.string.video_editor_comparison_gte
    OverlayComparison.LT -> Res.string.video_editor_comparison_lt
    OverlayComparison.LTE -> Res.string.video_editor_comparison_lte
    OverlayComparison.EQ -> Res.string.video_editor_comparison_eq
    OverlayComparison.NE -> Res.string.video_editor_comparison_ne
}

private fun iconLabel(icon: OverlayIcon): StringResource = when (icon) {
    OverlayIcon.AUTO -> Res.string.video_editor_icon_auto
    OverlayIcon.BATTERY -> Res.string.video_editor_icon_battery
    OverlayIcon.BOLT -> Res.string.video_editor_icon_bolt
    OverlayIcon.TEMPERATURE -> Res.string.video_editor_icon_temperature
    OverlayIcon.GAUGE -> Res.string.video_editor_icon_gauge
    OverlayIcon.WARNING -> Res.string.video_editor_icon_warning
    OverlayIcon.CURRENT -> Res.string.video_editor_icon_current
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
})

private fun String.alphaHex(): Int = overlayColorAlpha(this)

private fun String.withAlpha(alpha: Int): String =
    overlayColorWithAlpha(this, alpha)

private fun String.toComposeColor(): Color =
    runCatching { Color(android.graphics.Color.parseColor(this)) }.getOrDefault(Color.Transparent)


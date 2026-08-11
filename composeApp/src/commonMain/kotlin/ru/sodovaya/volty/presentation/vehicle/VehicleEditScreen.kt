package ru.sodovaya.volty.presentation.vehicle

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.dashboardStyleLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.common.secondaryGaugeLabel
import org.jetbrains.compose.resources.stringResource
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.action_delete
import volty.composeapp.generated.resources.alerts_open
import volty.composeapp.generated.resources.alerts_open_subtitle
import volty.composeapp.generated.resources.dashboard_style_default
import volty.composeapp.generated.resources.topology_parallel
import volty.composeapp.generated.resources.topology_series
import volty.composeapp.generated.resources.units_open
import volty.composeapp.generated.resources.units_open_subtitle
import volty.composeapp.generated.resources.vehicle_delete
import volty.composeapp.generated.resources.vehicle_delete_confirm_text
import volty.composeapp.generated.resources.vehicle_delete_confirm_title
import volty.composeapp.generated.resources.vehicle_discard_confirm
import volty.composeapp.generated.resources.vehicle_discard_keep
import volty.composeapp.generated.resources.vehicle_discard_text
import volty.composeapp.generated.resources.vehicle_discard_title
import volty.composeapp.generated.resources.vehicle_diagram_title
import volty.composeapp.generated.resources.vehicle_edit_edit
import volty.composeapp.generated.resources.vehicle_edit_new
import volty.composeapp.generated.resources.vehicle_field_averaging_window
import volty.composeapp.generated.resources.vehicle_field_bms_address
import volty.composeapp.generated.resources.vehicle_field_bms_type
import volty.composeapp.generated.resources.vehicle_field_cell_high
import volty.composeapp.generated.resources.vehicle_field_cell_low
import volty.composeapp.generated.resources.vehicle_field_chemistry
import volty.composeapp.generated.resources.vehicle_field_dashboard_style
import volty.composeapp.generated.resources.vehicle_field_icon
import volty.composeapp.generated.resources.vehicle_field_name
import volty.composeapp.generated.resources.vehicle_field_name_required
import volty.composeapp.generated.resources.vehicle_field_secondary_gauge
import volty.composeapp.generated.resources.vehicle_field_secondary_gauge_caption
import volty.composeapp.generated.resources.vehicle_field_soc_low
import volty.composeapp.generated.resources.vehicle_field_temp_high
import volty.composeapp.generated.resources.vehicle_field_temp_warn
import volty.composeapp.generated.resources.vehicle_field_topology
import volty.composeapp.generated.resources.vehicle_field_yield_bms
import volty.composeapp.generated.resources.vehicle_field_yield_bms_caption
import volty.composeapp.generated.resources.vehicle_auto_volume_caption
import volty.composeapp.generated.resources.vehicle_auto_volume_deadband
import volty.composeapp.generated.resources.vehicle_auto_volume_enabled
import volty.composeapp.generated.resources.vehicle_auto_volume_max_speed
import volty.composeapp.generated.resources.vehicle_auto_volume_max_volume
import volty.composeapp.generated.resources.vehicle_auto_volume_min_speed
import volty.composeapp.generated.resources.vehicle_auto_volume_min_volume
import volty.composeapp.generated.resources.vehicle_section_auto_volume
import volty.composeapp.generated.resources.vehicle_save
import volty.composeapp.generated.resources.vehicle_save_blocked
import volty.composeapp.generated.resources.vehicle_section_alerts
import volty.composeapp.generated.resources.vehicle_section_sources
import volty.composeapp.generated.resources.vehicle_source_add_controller
import volty.composeapp.generated.resources.vehicle_source_add_pack
import volty.composeapp.generated.resources.vehicle_source_scan

private val ICON_KEYS = listOf("generic", "skateboard", "ebike", "scooter", "unicycle", "moto", "solar", "ev", "boat", "rv")

/**
 * The vehicle composer (`G §3`–`§4`), and a renderer and nothing more — see
 * `VehicleSourceCards.kt` for what that means and why it has to be true here.
 *
 * The screen is three bands:
 *
 *  1. **the vehicle** — name, icon;
 *  2. **its sources** — one card per controller and per pack (G2 Task 3), which
 *     is the whole of what was, until this task, a single read-only "BMS type /
 *     BMS address" pair plus a flat Motor card editing `controllers[0]`;
 *  3. **its settings** — chemistry, battery wiring, averaging, the five cell and
 *     temperature thresholds, and links out to the two screens that own the
 *     rest (motion alerts, per vehicle; units, app-wide).
 *
 * The read-only source header only survives on the **create** path, where it
 * renders the picker's prefilled `State.bmsType` / `State.sourceAddress` —
 * there is no draft to make cards from (`State.canComposeSources`), so it is
 * the only description of the device the rider picked.
 *
 * While editing it is gone. It described the vehicle **as loaded**
 * (`State.sourceVehicle`), so changing a controller's type in its card would
 * have left the header contradicting the card six rows below it; and the defect
 * it was built for in G1 Task 5 — a controller-only vehicle described as a
 * phantom JK BMS — cannot happen once the cards render the actual sources.
 * `State.sourceVehicle` is still written at load and still asserted by two
 * component tests, but nothing on this screen reads it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleEditScreen(component: VehicleEditComponent) {
    val state by component.state.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isEditing) stringResource(Res.string.vehicle_edit_edit) else stringResource(Res.string.vehicle_edit_new)) },
                navigationIcon = {
                    TextButton(onClick = component::onCancel) { Text(stringResource(Res.string.action_cancel)) }
                },
                actions = {
                    // Deliberately NOT disabled on `!canSave`. A Save that is
                    // simply dead tells a rider nothing; tapping it sets
                    // `saveBlocked`, which prints the banner below and points at
                    // the source cards that contradict each other.
                    TextButton(onClick = component::onSave, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(Res.string.vehicle_save))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.name,
                onValueChange = component::onNameChanged,
                label = { Text(stringResource(Res.string.vehicle_field_name)) },
                isError = state.nameError,
                supportingText = { if (state.nameError) Text(stringResource(Res.string.vehicle_field_name_required)) },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel(stringResource(Res.string.vehicle_field_icon))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ICON_KEYS.forEach { key ->
                    val selected = state.iconKey == key
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { component.onIconChanged(key) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = iconKeyToEmoji(key),
                            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            if (state.canComposeSources) {
                HorizontalDivider()
                SourcesSection(state, component)
            } else {
                // CREATE only: the picker's prefilled BMS, which is the entire
                // source set a new vehicle can have from this screen
                // (`newVehicle` builds the single-pack shape). No draft exists
                // here, so there are no cards to say it instead.
                ReadOnlyRow(stringResource(Res.string.vehicle_field_bms_type), bmsTypeLabel(state.bmsType))
                ReadOnlyRow(
                    stringResource(Res.string.vehicle_field_bms_address),
                    state.sourceAddress.ifEmpty { "—" }
                )
            }

            HorizontalDivider()

            SectionLabel(stringResource(Res.string.vehicle_field_chemistry))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val chemistries = Chemistry.entries
                chemistries.forEachIndexed { idx, c ->
                    SegmentedButton(
                        selected = state.chemistry == c,
                        onClick = { component.onChemistryChanged(c) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = chemistries.size)
                    ) { Text(chemistryLabel(c), fontSize = 12.sp) }
                }
            }

            if (state.showTopologyChoice) {
                // How the packs are wired (`G §4`). Drives both the aggregation
                // formulas and what a missing pack means, so it belongs to the
                // vehicle rather than to any one pack card — and has no meaning
                // until the draft contains a second pack.
                SectionLabel(stringResource(Res.string.vehicle_field_topology))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val topologies = PackTopology.entries
                    topologies.forEachIndexed { idx, t ->
                        SegmentedButton(
                            selected = state.topology == t,
                            onClick = { component.onTopologyChanged(t) },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = topologies.size)
                        ) { Text(topologyLabel(t), fontSize = 12.sp) }
                    }
                }
            }

            SectionLabel(stringResource(Res.string.vehicle_field_averaging_window))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 30).forEach { min ->
                    FilterChip(
                        selected = state.averagingWindowMin == min,
                        onClick = { component.onAveragingWindowChanged(min) },
                        label = { Text("${min}m") }
                    )
                }
            }

            HorizontalDivider()
            SectionLabel(stringResource(Res.string.vehicle_section_auto_volume))
            SwitchRow(
                label = stringResource(Res.string.vehicle_auto_volume_enabled),
                subtitle = stringResource(Res.string.vehicle_auto_volume_caption),
                checked = state.autoVolume.enabled,
                onChange = component::onAutoVolumeEnabledChanged
            )
            SliderSetting(
                label = stringResource(Res.string.vehicle_auto_volume_min_volume),
                value = state.autoVolume.minVolumePercent,
                range = 0..state.autoVolume.maxVolumePercent,
                onChange = { component.onAutoVolumeVolumeRangeChanged(it, state.autoVolume.maxVolumePercent) },
                suffix = "%"
            )
            SliderSetting(
                label = stringResource(Res.string.vehicle_auto_volume_max_volume),
                value = state.autoVolume.maxVolumePercent,
                range = state.autoVolume.minVolumePercent..100,
                onChange = { component.onAutoVolumeVolumeRangeChanged(state.autoVolume.minVolumePercent, it) },
                suffix = "%"
            )
            SliderSetting(
                label = stringResource(Res.string.vehicle_auto_volume_min_speed),
                value = state.autoVolume.minSpeedKmh,
                range = 0..(state.autoVolume.maxSpeedKmh - 1).coerceAtLeast(0),
                onChange = { component.onAutoVolumeSpeedRangeChanged(it, state.autoVolume.maxSpeedKmh) },
                suffix = " km/h"
            )
            SliderSetting(
                label = stringResource(Res.string.vehicle_auto_volume_max_speed),
                value = state.autoVolume.maxSpeedKmh,
                range = (state.autoVolume.minSpeedKmh + 1)..60,
                onChange = { component.onAutoVolumeSpeedRangeChanged(state.autoVolume.minSpeedKmh, it) },
                suffix = " km/h"
            )
            SliderSetting(
                label = stringResource(Res.string.vehicle_auto_volume_deadband),
                value = state.autoVolume.deadbandKmh,
                range = 0..10,
                onChange = component::onAutoVolumeDeadbandChanged,
                suffix = " km/h"
            )

            HorizontalDivider()
            SectionLabel(stringResource(Res.string.vehicle_section_alerts))
            FloatField(stringResource(Res.string.vehicle_field_cell_high), state.cellHighV, component::onCellHighVChanged)
            FloatField(stringResource(Res.string.vehicle_field_cell_low), state.cellLowV, component::onCellLowVChanged)
            FloatField(stringResource(Res.string.vehicle_field_temp_warn), state.temperatureWarnC, component::onTemperatureWarnChanged)
            FloatField(stringResource(Res.string.vehicle_field_temp_high), state.temperatureHighC, component::onTemperatureHighChanged)
            IntField(stringResource(Res.string.vehicle_field_soc_low), state.socLowPercent, component::onSocLowChanged)

            // Motion alerts live on their own screen (F Task 9): they are
            // list-shaped — up to three rider-defined levels per kind — and every
            // kind is shown even when the hardware cannot supply it, which does
            // not fit between two numeric fields. Only offered for a SAVED
            // vehicle: the alert screen persists onto a row that must exist.
            if (state.isEditing) {
                LinkRow(
                    title = stringResource(Res.string.alerts_open),
                    subtitle = stringResource(Res.string.alerts_open_subtitle),
                    onClick = component::onOpenAlerts
                )
            }

            // km / mi is app-wide (`B §9`), so this is a link to the one screen
            // that owns it and never a per-vehicle copy of the control — a
            // second switch writing the same preference would read as
            // per-vehicle and silently change every other vehicle.
            LinkRow(
                title = stringResource(Res.string.units_open),
                subtitle = stringResource(Res.string.units_open_subtitle),
                onClick = component::onOpenUnits
            )

            HorizontalDivider()

            // DASHBOARD STYLE — Default (null, follows the app-level setting), Clean, Classic.
            SectionLabel(stringResource(Res.string.vehicle_field_dashboard_style))
            val dashboardOptions: List<DashboardStyle?> = listOf(null) + DashboardStyle.entries
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                dashboardOptions.forEachIndexed { idx, style ->
                    SegmentedButton(
                        selected = state.dashboardStyle == style,
                        onClick = { component.onDashboardStyleChanged(style) },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = dashboardOptions.size)
                    ) { Text(style?.let { dashboardStyleLabel(it) } ?: stringResource(Res.string.dashboard_style_default), fontSize = 12.sp) }
                }
            }

            // INNER GAUGE — what Clean's hero ring shows inside the speed arc. It applies to that
            // style ONLY: Classic renders all eight VESC dials at once, so it has nothing to pick.
            // The control stays enabled and visible for a Classic vehicle rather than being hidden
            // or greyed out, because the style is a per-vehicle setting two fields up and a rider
            // who switches to Clean would otherwise find their choice had silently gone missing.
            SectionLabel(stringResource(Res.string.vehicle_field_secondary_gauge))
            Text(
                text = stringResource(Res.string.vehicle_field_secondary_gauge_caption),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryGauge.entries.forEach { gauge ->
                    FilterChip(
                        selected = state.secondaryGauge == gauge,
                        onClick = { component.onSecondaryGaugeChanged(gauge) },
                        label = { Text(secondaryGaugeLabel(gauge)) }
                    )
                }
            }

            if (state.isEditing) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = { showDeleteConfirm = true }
                ) {
                    Text(stringResource(Res.string.vehicle_delete), color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                confirmButton = {
                    TextButton(onClick = { showDeleteConfirm = false; component.onDelete() }) {
                        Text(stringResource(Res.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(Res.string.action_cancel)) } },
                title = { Text(stringResource(Res.string.vehicle_delete_confirm_title)) },
                text = { Text(stringResource(Res.string.vehicle_delete_confirm_text)) }
            )
        }

        if (state.discardPrompt) {
            AlertDialog(
                onDismissRequest = component::onDiscardDismissed,
                confirmButton = {
                    TextButton(onClick = component::onDiscardConfirmed) {
                        Text(stringResource(Res.string.vehicle_discard_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = component::onDiscardDismissed) {
                        Text(stringResource(Res.string.vehicle_discard_keep))
                    }
                },
                title = { Text(stringResource(Res.string.vehicle_discard_title)) },
                text = { Text(stringResource(Res.string.vehicle_discard_text)) }
            )
        }
    }
}

@Composable
private fun SliderSetting(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
    suffix: String
) {
    val safeStart = range.first.coerceAtMost(range.last)
    val safeEnd = range.last.coerceAtLeast(safeStart)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text("${value.coerceIn(safeStart, safeEnd)}$suffix", fontSize = 13.sp)
        }
        Slider(
            value = value.coerceIn(safeStart, safeEnd).toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = safeStart.toFloat()..safeEnd.toFloat(),
            steps = (safeEnd - safeStart - 1).coerceAtLeast(0)
        )
    }
}

/**
 * The source list: the refusal banner, one card per source, and the two adds.
 *
 * Only rendered while [VehicleEditComponent.State.canComposeSources] — the
 * create path builds the single-BMS shape through `singlePackVehicle` and never
 * reads the draft, so an "+ Controller" offered there would take the rider's
 * work and throw it away at save time. The component refuses those calls too;
 * this is the half that stops them being offered.
 */
@Composable
private fun SourcesSection(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    SectionLabel(stringResource(Res.string.vehicle_diagram_title))
    DraftDiagramView(draftDiagram(state.draft, state.issues))

    SectionLabel(stringResource(Res.string.vehicle_section_sources))

    // `onSave` refused. Without this the button looked dead: `onSave` returned
    // with no state change at all, unlike the `nameError` branch beside it.
    if (state.saveBlocked) {
        Text(
            text = stringResource(Res.string.vehicle_save_blocked),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(10.dp)
        )
    }

    val issues = state.issuesBySource
    val links = state.draft.linkAddresses

    // `key(draft.key)` and not the loop index: a card owns one piece of local
    // state the draft does not carry — whether its Advanced disclosure is open
    // — and without a key that `remember` binds to the SLOT. Moving controller
    // 1 down would then leave its open disclosure on whatever moved up into
    // position 1. The text fields self-correct (they re-sync from the value
    // they are handed); the boolean would not.
    state.draft.controllers.forEachIndexed { index, controller ->
        key(controller.key) {
            ControllerSourceCard(
                draft = controller,
                index = index,
                count = state.draft.controllers.size,
                issues = issues[controller.key].orEmpty(),
                derivedBattery = state.draft.resolvedDerivedBattery(controller),
                canRemove = state.canRemoveSource,
                linkAddresses = links,
                component = component
            )
        }
    }
    state.draft.packs.forEachIndexed { index, pack ->
        key(pack.key) {
            PackSourceCard(
                draft = pack,
                index = index,
                count = state.draft.packs.size,
                issues = issues[pack.key].orEmpty(),
                canRemove = state.canRemoveSource,
                linkAddresses = links,
                // Numbered as the cards are (1-based, draft order), so a chip
                // reading "Battery 2" names the card headed "Battery 2".
                otherPacks = state.draft.packs
                    .mapIndexed { i, p -> (i + 1) to p }
                    .filter { (_, p) -> p.key != pack.key },
                component = component
            )
        }
    }

    // `C §5`, and it belongs HERE rather than beside the vehicle's own settings:
    // it is a property of an alias group — which of two paths to one battery the
    // app gives up while riding — so it appears under the sources, once such a
    // group exists, and nowhere else. See [State.showYieldToggle].
    if (state.showYieldToggle) {
        SwitchRow(
            label = stringResource(Res.string.vehicle_field_yield_bms),
            subtitle = stringResource(Res.string.vehicle_field_yield_bms_caption),
            checked = state.yieldsBmsToHeadUnit,
            onChange = component::onYieldBmsToHeadUnitChanged
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // FIRST, because it is the one that does not ask a rider to know a BLE
        // MAC by heart (G2 Task 5). The two blank adds below stay: a device that
        // is out of range, asleep, or simply not advertising has to be
        // describable, and before this task they were the only way in at all.
        OutlinedButton(onClick = component::onStartDeviceScan) {
            Text(stringResource(Res.string.vehicle_source_scan), fontSize = 12.sp)
        }
        // The type and the link a new source starts with are only its INITIAL
        // values — both are editable on the card that appears, so neither is a
        // decision with a consequence. The link starts blank on purpose rather
        // than defaulting to the vehicle's existing one: guessing wrong puts a
        // BMS on the controller's link, which is a BLOCKING conflict the rider
        // did not ask for, while a blank link is an advisory that says exactly
        // what is missing — and the card offers every existing link as a chip,
        // so the two-uBox case is still one tap.
        OutlinedButton(onClick = { component.onAddController(ControllerType.VESC, "") }) {
            Text(stringResource(Res.string.vehicle_source_add_controller), fontSize = 12.sp)
        }
        OutlinedButton(onClick = { component.onAddPack(BmsType.JK_BMS, "") }) {
            Text(stringResource(Res.string.vehicle_source_add_pack), fontSize = 12.sp)
        }
    }

    // Below the adds rather than above them: it only applies once there IS a
    // VESC source to scan behind, and on the one-controller vehicle `G §3`'s
    // first flow builds it is a single line of explanation.
    CanDiscoverySection(state, component)

    if (state.scanning) DeviceScanSheet(state.scannedDevices, component)
}

@Composable
private fun topologyLabel(topology: PackTopology): String = stringResource(
    when (topology) {
        PackTopology.PARALLEL -> Res.string.topology_parallel
        PackTopology.SERIES -> Res.string.topology_series
    }
)

/** A row that goes somewhere else — the two settings this form links to rather than duplicates. */
@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

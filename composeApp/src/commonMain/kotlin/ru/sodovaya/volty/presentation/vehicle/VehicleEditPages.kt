package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.CapacityUnit
import ru.sodovaya.volty.domain.model.Chemistry
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.model.DashboardStyle
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.SecondaryGauge
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.dashboardStyleLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.common.secondaryGaugeLabel
import ru.sodovaya.volty.presentation.common.settings.SettingsChoiceRow
import ru.sodovaya.volty.presentation.common.settings.SettingsDangerRow
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import ru.sodovaya.volty.presentation.common.settings.SettingsRowDivider
import ru.sodovaya.volty.presentation.common.settings.SettingsSliderRow
import ru.sodovaya.volty.presentation.common.settings.SettingsSwitchRow
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.dashboard_style_default
import volty.composeapp.generated.resources.topology_parallel
import volty.composeapp.generated.resources.topology_series
import volty.composeapp.generated.resources.vehicle_auto_volume_caption
import volty.composeapp.generated.resources.vehicle_auto_volume_deadband
import volty.composeapp.generated.resources.vehicle_auto_volume_enabled
import volty.composeapp.generated.resources.vehicle_auto_volume_max_speed
import volty.composeapp.generated.resources.vehicle_auto_volume_max_volume
import volty.composeapp.generated.resources.vehicle_auto_volume_min_speed
import volty.composeapp.generated.resources.vehicle_auto_volume_min_volume
import volty.composeapp.generated.resources.vehicle_battery_none_hint
import volty.composeapp.generated.resources.vehicle_battery_source_both
import volty.composeapp.generated.resources.vehicle_battery_source_controller
import volty.composeapp.generated.resources.vehicle_battery_source_label
import volty.composeapp.generated.resources.vehicle_battery_source_none
import volty.composeapp.generated.resources.vehicle_battery_source_own
import volty.composeapp.generated.resources.vehicle_controller_none
import volty.composeapp.generated.resources.vehicle_diagram_title
import volty.composeapp.generated.resources.vehicle_field_averaging_window
import volty.composeapp.generated.resources.vehicle_field_chemistry
import volty.composeapp.generated.resources.vehicle_field_dashboard_style
import volty.composeapp.generated.resources.vehicle_field_icon
import volty.composeapp.generated.resources.vehicle_field_name
import volty.composeapp.generated.resources.vehicle_field_name_required
import volty.composeapp.generated.resources.vehicle_field_secondary_gauge
import volty.composeapp.generated.resources.vehicle_field_secondary_gauge_caption
import volty.composeapp.generated.resources.vehicle_field_topology
import volty.composeapp.generated.resources.vehicle_field_yield_bms
import volty.composeapp.generated.resources.vehicle_field_yield_bms_caption
import volty.composeapp.generated.resources.vehicle_hub_back
import volty.composeapp.generated.resources.vehicle_hub_battery
import volty.composeapp.generated.resources.vehicle_hub_controller
import volty.composeapp.generated.resources.vehicle_hub_dashboard
import volty.composeapp.generated.resources.vehicle_hub_identity
import volty.composeapp.generated.resources.vehicle_hub_layout
import volty.composeapp.generated.resources.vehicle_hub_motor
import volty.composeapp.generated.resources.vehicle_hub_speed_volume
import volty.composeapp.generated.resources.vehicle_section_sources
import volty.composeapp.generated.resources.vehicle_source_add_controller
import volty.composeapp.generated.resources.vehicle_source_add_pack
import volty.composeapp.generated.resources.vehicle_source_scan
import volty.composeapp.generated.resources.vehicle_energy_title
import volty.composeapp.generated.resources.vehicle_energy_unit
import volty.composeapp.generated.resources.vehicle_energy_capacity
import volty.composeapp.generated.resources.vehicle_energy_capacity_invalid
import volty.composeapp.generated.resources.vehicle_energy_unit_wh
import volty.composeapp.generated.resources.vehicle_energy_unit_ah
import volty.composeapp.generated.resources.vehicle_energy_learned
import volty.composeapp.generated.resources.vehicle_energy_learning
import volty.composeapp.generated.resources.vehicle_energy_resistance
import volty.composeapp.generated.resources.vehicle_energy_resistance_value
import volty.composeapp.generated.resources.vehicle_energy_consumption
import volty.composeapp.generated.resources.vehicle_energy_consumption_value
import volty.composeapp.generated.resources.vehicle_energy_reset
import volty.composeapp.generated.resources.vehicle_energy_reset_title
import volty.composeapp.generated.resources.vehicle_energy_reset_text
import volty.composeapp.generated.resources.vehicle_energy_reset_confirm

internal val VEHICLE_ICON_KEYS = listOf("generic", "skateboard", "ebike", "scooter", "unicycle", "moto", "solar", "ev", "boat", "rv")

/** One page of the editor: a back row, then that section's controls. All of it edits the shared draft. */
@Composable
internal fun VehicleEditPage(
    section: VehicleEditSection,
    state: VehicleEditComponent.State,
    component: VehicleEditComponent
) {
    PageHeader(section, onBack = component::onCloseSection)
    when (section) {
        VehicleEditSection.IDENTITY -> IdentityPage(state, component)
        VehicleEditSection.CONTROLLER -> ControllerPage(state, component)
        VehicleEditSection.BATTERY -> BatteryPage(state, component)
        VehicleEditSection.MOTOR -> MotorPage(state, component)
        VehicleEditSection.LAYOUT -> LayoutPage(state, component)
        VehicleEditSection.DASHBOARD -> DashboardPage(state, component)
        VehicleEditSection.SPEED_VOLUME -> SpeedVolumePage(state, component)
    }
}

@Composable
private fun PageHeader(section: VehicleEditSection, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.vehicle_hub_back))
        }
        Text(
            text = stringResource(
                when (section) {
                    VehicleEditSection.IDENTITY -> Res.string.vehicle_hub_identity
                    VehicleEditSection.CONTROLLER -> Res.string.vehicle_hub_controller
                    VehicleEditSection.BATTERY -> Res.string.vehicle_hub_battery
                    VehicleEditSection.MOTOR -> Res.string.vehicle_hub_motor
                    VehicleEditSection.LAYOUT -> Res.string.vehicle_hub_layout
                    VehicleEditSection.DASHBOARD -> Res.string.vehicle_hub_dashboard
                    VehicleEditSection.SPEED_VOLUME -> Res.string.vehicle_hub_speed_volume
                }
            ),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ---------------------------------------------------------------------------------------------
// IDENTITY: name and icon
// ---------------------------------------------------------------------------------------------

@Composable
private fun IdentityPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
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
        VEHICLE_ICON_KEYS.forEach { key ->
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
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 16.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// CONTROLLER: type, label, "battery from this controller", and each controller's link
// ---------------------------------------------------------------------------------------------

@Composable
private fun ControllerPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    val draft = state.draft
    if (draft.controllers.isEmpty()) Caption(stringResource(Res.string.vehicle_controller_none))
    val issues = state.issuesBySource
    // `key(controller.key)` and not the loop index: a card owns one piece of local state the
    // draft does not carry (whether its Advanced disclosure is open), and without a key that
    // `remember` binds to the SLOT, so moving a controller would leave its open disclosure on
    // whatever moved into its position.
    draft.controllers.forEachIndexed { index, controller ->
        key(controller.key) {
            ControllerSourceCard(
                draft = controller,
                index = index,
                count = draft.controllers.size,
                issues = issues[controller.key].orEmpty(),
                derivedBattery = draft.resolvedDerivedBattery(controller),
                canRemove = state.canRemoveSource,
                linkAddresses = draft.linkAddresses,
                component = component
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// BATTERY: source, packs, S, chemistry, topology, averaging window
// ---------------------------------------------------------------------------------------------

@Composable
private fun BatteryPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    val draft = state.draft
    val summary = batterySummary(draft, state.chemistry, state.learnedCellCount)
    SettingsGroup(null) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(Res.string.vehicle_battery_source_label), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(
                    when (summary.source) {
                        BatterySource.OWN_BMS -> Res.string.vehicle_battery_source_own
                        BatterySource.FROM_CONTROLLER -> Res.string.vehicle_battery_source_controller
                        BatterySource.DEVICE_IS_BOTH -> Res.string.vehicle_battery_source_both
                        BatterySource.NONE -> Res.string.vehicle_battery_source_none
                    }
                ),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (summary.source == BatterySource.NONE) Caption(stringResource(Res.string.vehicle_battery_none_hint))

    val issues = state.issuesBySource
    draft.packs.forEachIndexed { index, pack ->
        key(pack.key) {
            PackSourceCard(
                draft = pack,
                index = index,
                count = draft.packs.size,
                issues = issues[pack.key].orEmpty(),
                canRemove = state.canRemoveSource,
                linkAddresses = draft.linkAddresses,
                // Numbered as the cards are (1-based, draft order), so a chip
                // reading "Battery 2" names the card headed "Battery 2".
                otherPacks = draft.packs
                    .mapIndexed { i, p -> (i + 1) to p }
                    .filter { (_, p) -> p.key != pack.key },
                learnedCellCount = state.learnedCellCount,
                component = component
            )
        }
    }
    // A controller that stands in for the battery has no Pack row to hold S, so its S lives here.
    draft.controllers.forEachIndexed { index, controller ->
        if (draft.resolvedDerivedBattery(controller)) {
            key("s-" + controller.key) {
                DerivedSeriesRow(controller, index, state.learnedCellCount, component)
            }
        }
    }

    SettingsGroup(null) {
        SettingsChoiceRow(
            title = stringResource(Res.string.vehicle_field_chemistry),
            options = Chemistry.entries.map { it to chemistryLabel(it) },
            selected = state.chemistry,
            onSelect = component::onChemistryChanged
        )
        if (state.showTopologyChoice) {
            // How the packs are wired (`G §4`). Drives both the aggregation formulas and what a
            // missing pack means, so it belongs to the vehicle rather than to any one pack card -
            // and has no meaning until the draft contains a second pack.
            SettingsRowDivider()
            SettingsChoiceRow(
                title = stringResource(Res.string.vehicle_field_topology),
                options = PackTopology.entries.map { it to topologyLabel(it) },
                selected = state.topology,
                onSelect = component::onTopologyChanged
            )
        }
        SettingsRowDivider()
        SettingsChoiceRow(
            title = stringResource(Res.string.vehicle_field_averaging_window),
            options = listOf(1, 5, 10, 30).map { it to "${it}m" },
            selected = state.averagingWindowMin,
            onSelect = component::onAveragingWindowChanged
        )
    }

    // A block of its own, below the pack cards and never inside one: capacity and what was
    // learned belong to the vehicle's pack as a whole, not to any one source. Nothing for a
    // vehicle that does not exist yet (state.energy is null while creating).
    EnergyBlock(state, component)
}

// ---------------------------------------------------------------------------------------------
// ENERGY: nameplate capacity, what the app learned, reset
// ---------------------------------------------------------------------------------------------

/**
 * Renders [VehicleEditComponent.State.capacityText] / [energy][VehicleEditComponent.State.energy] as
 * handed over. Every decision (parsing, which caption, the Wh conversion) is in the component and
 * tested there; the layout below is NOT verified on a device.
 */
@Composable
private fun EnergyBlock(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    var confirmReset by remember { mutableStateOf(false) }
    val energy = state.energy
    SectionLabel(stringResource(Res.string.vehicle_energy_title))
    SettingsGroup(null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = state.capacityText,
                onValueChange = component::onCapacityChanged,
                label = { Text(stringResource(Res.string.vehicle_energy_capacity)) },
                isError = state.capacityError,
                supportingText = {
                    if (state.capacityError) Text(stringResource(Res.string.vehicle_energy_capacity_invalid))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            // Switching the unit keeps the typed number: 20 Wh and 20 Ah are different packs.
            SettingsChoiceRow(
                title = stringResource(Res.string.vehicle_energy_unit),
                options = listOf(
                    CapacityUnit.WH to stringResource(Res.string.vehicle_energy_unit_wh),
                    CapacityUnit.AH to stringResource(Res.string.vehicle_energy_unit_ah)
                ),
                selected = state.capacityUnit,
                onSelect = component::onCapacityUnitChanged
            )
            when (state.learnedCaption) {
                LearnedCaption.LEARNED -> Caption(
                    stringResource(
                        Res.string.vehicle_energy_learned,
                        (energy?.learnedNameplateWh ?: 0f).roundToInt(),
                        energy?.learnRides ?: 0
                    )
                )
                LearnedCaption.LEARNING -> Caption(stringResource(Res.string.vehicle_energy_learning))
                LearnedCaption.HIDDEN -> Unit
            }
        }
        if (energy != null) {
            energy.resistanceMilliOhm?.let { mohm ->
                SettingsRowDivider()
                EnergyValueRow(
                    stringResource(Res.string.vehicle_energy_resistance),
                    stringResource(Res.string.vehicle_energy_resistance_value, mohm)
                )
            }
            energy.avgWhPerKm?.let { wh ->
                SettingsRowDivider()
                EnergyValueRow(
                    stringResource(Res.string.vehicle_energy_consumption),
                    stringResource(Res.string.vehicle_energy_consumption_value, wh.roundToInt())
                )
            }
            SettingsRowDivider()
            SettingsDangerRow(stringResource(Res.string.vehicle_energy_reset), onClick = { confirmReset = true })
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; component.onResetLearnedEnergy() }) {
                    Text(stringResource(Res.string.vehicle_energy_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
            title = { Text(stringResource(Res.string.vehicle_energy_reset_title)) },
            text = { Text(stringResource(Res.string.vehicle_energy_reset_text)) }
        )
    }
}

@Composable
private fun EnergyValueRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------------------------------------
// MOTOR: pole pairs, wheel diameter, gear ratio - per controller
// ---------------------------------------------------------------------------------------------

@Composable
private fun MotorPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    if (state.draft.controllers.isEmpty()) Caption(stringResource(Res.string.vehicle_controller_none))
    state.draft.controllers.forEachIndexed { index, controller ->
        key(controller.key) { ControllerMotorCard(controller, index, component) }
    }
}

// ---------------------------------------------------------------------------------------------
// LAYOUT: the tree, device search, the adds, the head-unit handoff, CAN
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LayoutPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    SectionLabel(stringResource(Res.string.vehicle_diagram_title))
    DraftDiagramView(draftDiagram(state.draft, state.issues))

    SectionLabel(stringResource(Res.string.vehicle_section_sources))

    // `C §5`, and it belongs HERE: it is a property of an alias group - which of two paths to one
    // battery the app gives up while riding - so it appears once such a group exists, and nowhere
    // else. See [VehicleEditComponent.State.showYieldToggle].
    if (state.showYieldToggle) {
        SwitchRow(
            label = stringResource(Res.string.vehicle_field_yield_bms),
            subtitle = stringResource(Res.string.vehicle_field_yield_bms_caption),
            checked = state.yieldsBmsToHeadUnit,
            onChange = component::onYieldBmsToHeadUnitChanged
        )
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // FIRST, because it is the one that does not ask a rider to know a BLE MAC by heart
        // (G2 Task 5). The two blank adds stay: a device that is out of range, asleep, or simply
        // not advertising has to be describable.
        OutlinedButton(onClick = component::onStartDeviceScan) {
            Text(stringResource(Res.string.vehicle_source_scan), fontSize = 12.sp)
        }
        // The type and the link a new source starts with are only its INITIAL values - both are
        // editable on the card that appears. The link starts blank on purpose: a blank link is an
        // advisory that says exactly what is missing, while guessing wrong is a BLOCKING conflict.
        OutlinedButton(onClick = { component.onAddController(ControllerType.VESC, "") }) {
            Text(stringResource(Res.string.vehicle_source_add_controller), fontSize = 12.sp)
        }
        OutlinedButton(onClick = { component.onAddPack(BmsType.JK_BMS, "") }) {
            Text(stringResource(Res.string.vehicle_source_add_pack), fontSize = 12.sp)
        }
    }

    // Below the adds: it only applies once there IS a VESC source to scan behind.
    CanDiscoverySection(state, component)

    if (state.scanning) DeviceScanSheet(state.scannedDevices, component)
}

// ---------------------------------------------------------------------------------------------
// DASHBOARD: style and inner gauge
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DashboardPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    // Default (null, follows the app-level setting), Clean, Classic, and Light. The enum/resource
    // list is the single source for the labels here and in Settings, so an explicit old vehicle
    // override remains untouched.
    val defaultLabel = stringResource(Res.string.dashboard_style_default)
    val options: List<Pair<DashboardStyle?, String>> =
        listOf<Pair<DashboardStyle?, String>>(null to defaultLabel) +
            DashboardStyle.entries.map { it to dashboardStyleLabel(it) }
    SettingsGroup(null) {
        SettingsChoiceRow(
            title = stringResource(Res.string.vehicle_field_dashboard_style),
            options = options,
            selected = state.dashboardStyle,
            onSelect = component::onDashboardStyleChanged
        )
    }

    // What Clean's hero ring shows inside the speed arc. It applies to that style ONLY, but the
    // control stays visible for a Classic vehicle: the style is a per-vehicle setting one row up,
    // and a rider who switches to Clean would otherwise find their choice had silently gone missing.
    SectionLabel(stringResource(Res.string.vehicle_field_secondary_gauge))
    Caption(stringResource(Res.string.vehicle_field_secondary_gauge_caption))
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
}

// ---------------------------------------------------------------------------------------------
// SPEED_VOLUME: automatic volume
// ---------------------------------------------------------------------------------------------

@Composable
private fun SpeedVolumePage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    val auto = state.autoVolume
    SettingsGroup(null) {
        SettingsSwitchRow(
            title = stringResource(Res.string.vehicle_auto_volume_enabled),
            hint = stringResource(Res.string.vehicle_auto_volume_caption),
            checked = auto.enabled,
            onChange = component::onAutoVolumeEnabledChanged
        )
        SettingsRowDivider()
        IntSliderRow(
            title = stringResource(Res.string.vehicle_auto_volume_min_volume),
            value = auto.minVolumePercent,
            range = 0..auto.maxVolumePercent,
            suffix = "%",
            onChange = { component.onAutoVolumeVolumeRangeChanged(it, auto.maxVolumePercent) }
        )
        IntSliderRow(
            title = stringResource(Res.string.vehicle_auto_volume_max_volume),
            value = auto.maxVolumePercent,
            range = auto.minVolumePercent..100,
            suffix = "%",
            onChange = { component.onAutoVolumeVolumeRangeChanged(auto.minVolumePercent, it) }
        )
        IntSliderRow(
            title = stringResource(Res.string.vehicle_auto_volume_min_speed),
            value = auto.minSpeedKmh,
            range = 0..(auto.maxSpeedKmh - 1).coerceAtLeast(0),
            suffix = " km/h",
            onChange = { component.onAutoVolumeSpeedRangeChanged(it, auto.maxSpeedKmh) }
        )
        IntSliderRow(
            title = stringResource(Res.string.vehicle_auto_volume_max_speed),
            value = auto.maxSpeedKmh,
            range = (auto.minSpeedKmh + 1)..60,
            suffix = " km/h",
            onChange = { component.onAutoVolumeSpeedRangeChanged(auto.minSpeedKmh, it) }
        )
        IntSliderRow(
            title = stringResource(Res.string.vehicle_auto_volume_deadband),
            value = auto.deadbandKmh,
            range = 0..10,
            suffix = " km/h",
            onChange = component::onAutoVolumeDeadbandChanged
        )
    }
}

/** The shared slider row over an integer range, with the same clamping the old inline slider had. */
@Composable
private fun IntSliderRow(title: String, value: Int, range: IntRange, suffix: String, onChange: (Int) -> Unit) {
    val safeStart = range.first.coerceAtMost(range.last)
    val safeEnd = range.last.coerceAtLeast(safeStart)
    val shown = value.coerceIn(safeStart, safeEnd)
    SettingsSliderRow(
        title = title,
        valueText = "$shown$suffix",
        value = shown.toFloat(),
        range = safeStart.toFloat()..safeEnd.toFloat(),
        steps = (safeEnd - safeStart - 1).coerceAtLeast(0),
        onChange = { onChange(it.toInt()) }
    )
}

@Composable
private fun topologyLabel(topology: PackTopology): String = stringResource(
    when (topology) {
        PackTopology.PARALLEL -> Res.string.topology_parallel
        PackTopology.SERIES -> Res.string.topology_series
    }
)

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

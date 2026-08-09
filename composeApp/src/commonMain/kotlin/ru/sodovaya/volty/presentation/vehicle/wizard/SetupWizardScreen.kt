package ru.sodovaya.volty.presentation.vehicle.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.PackTopology
import ru.sodovaya.volty.domain.model.BmsType
import ru.sodovaya.volty.domain.model.ControllerType
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.picker.SignalProximity
import ru.sodovaya.volty.presentation.picker.SourceRole
import ru.sodovaya.volty.presentation.picker.scanDeviceLabel
import ru.sodovaya.volty.presentation.picker.signalProximity
import ru.sodovaya.volty.presentation.picker.sourceRole
import ru.sodovaya.volty.presentation.vehicle.DraftDiagramView
import ru.sodovaya.volty.presentation.vehicle.CanDiscoveryContent
import ru.sodovaya.volty.presentation.vehicle.draftDiagram
import volty.composeapp.generated.resources.*

/** Renderer only: stage access and every draft mutation live in the component. */
@Composable
fun SetupWizardScreen(component: SetupWizardComponent) {
    val state by component.state.collectAsStateCompat()
    Children(
        stack = component.stack,
        animation = stackAnimation(fade()),
        modifier = Modifier.fillMaxSize()
    ) { child ->
        when (val instance = child.instance) {
            is SetupWizardComponent.Child.WhatAreWeBuilding -> ArchetypeScreen(instance.component)
            is SetupWizardComponent.Child.Controllers -> ControllerScreen(instance.component)
            is SetupWizardComponent.Child.Battery -> BatteryScreen(instance.component)
            is SetupWizardComponent.Child.Review -> ReviewScreen(instance.component)
            is SetupWizardComponent.Child.Done -> DoneScreen(instance.component)
        }
    }
    if (state.discardPrompt) {
        AlertDialog(
            onDismissRequest = component::onDiscardDismissed,
            title = { Text(stringResource(Res.string.wizard_leave_title)) },
            text = { Text(stringResource(Res.string.wizard_leave_text)) },
            confirmButton = {
                TextButton(onClick = component::onDiscardConfirmed) {
                    Text(stringResource(Res.string.wizard_leave_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDiscardDismissed) {
                    Text(stringResource(Res.string.wizard_leave_keep))
                }
            }
        )
    }
}

@Composable
private fun ArchetypeScreen(component: SetupWizardComponent.ArchetypeStage) {
    val state by component.state.collectAsStateCompat()
    WizardPage(
        title = stringResource(Res.string.wizard_new_vehicle),
        progress = 1,
        backGlyph = "✕",
        onBack = component::onCancel,
        footer = {
            TextButton(onClick = component::onSkip) { Text(stringResource(Res.string.wizard_skip)) }
            Button(onClick = component::onNext) { Text(stringResource(Res.string.wizard_next)) }
        }
    ) {
        Text(
            stringResource(Res.string.wizard_what_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(Res.string.wizard_what_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArchetypeChoice(
                selected = state.archetype == VehicleArchetype.WHEEL,
                title = Res.string.wizard_archetype_wheel,
                caption = Res.string.wizard_archetype_wheel_caption,
                onClick = { component.onArchetypeSelected(VehicleArchetype.WHEEL) },
                modifier = Modifier.weight(1f)
            )
            ArchetypeChoice(
                selected = state.archetype == VehicleArchetype.SCOOTER,
                title = Res.string.wizard_archetype_scooter,
                caption = Res.string.wizard_archetype_scooter_caption,
                onClick = { component.onArchetypeSelected(VehicleArchetype.SCOOTER) },
                modifier = Modifier.weight(1f)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArchetypeChoice(
                selected = state.archetype == VehicleArchetype.BICYCLE,
                title = Res.string.wizard_archetype_bicycle,
                caption = Res.string.wizard_archetype_bicycle_caption,
                onClick = { component.onArchetypeSelected(VehicleArchetype.BICYCLE) },
                modifier = Modifier.weight(1f)
            )
            ArchetypeChoice(
                selected = state.archetype == VehicleArchetype.CUSTOM,
                title = Res.string.wizard_archetype_custom,
                caption = Res.string.wizard_archetype_custom_caption,
                onClick = { component.onArchetypeSelected(VehicleArchetype.CUSTOM) },
                modifier = Modifier.weight(1f)
            )
        }
        Hint(stringResource(Res.string.wizard_custom_hint))
    }
}

@Composable
private fun ControllerScreen(component: SetupWizardComponent.ControllerStage) {
    val state by component.state.collectAsStateCompat()
    WizardPage(
        title = stringResource(Res.string.wizard_controller_title),
        progress = 2,
        onBack = component::onBack,
        footer = {
            TextButton(onClick = component::onNoController) {
                Text(stringResource(Res.string.wizard_no_controller))
            }
            Button(onClick = component::onNext) { Text(stringResource(Res.string.wizard_next)) }
        }
    ) {
        ScanHeading(state.scannedDevices.size, state.scanning)
        ScannedDeviceList(
            rows = component.scanRows,
            onAdd = component::onAddScannedDevice
        )
        Hint(stringResource(Res.string.wizard_scan_hint))
        WizardSectionCard {
            Text(
                stringResource(Res.string.wizard_vehicle_name),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.name,
                onValueChange = component::onNameChanged,
                placeholder = { Text(stringResource(Res.string.wizard_new_vehicle)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        EditableDraftSources(state, component)
        CanDiscoveryContent(
            canScanTarget = state.canScanTarget,
            scan = state.canScan,
            candidates = state.canCandidates,
            onDiscover = component::onDiscoverCanDevices,
            onAddCandidate = component::onAddCanCandidate,
            onDismiss = component::onDismissCanScan
        )
    }
}

@Composable
private fun BatteryScreen(component: SetupWizardComponent.BatteryStage) {
    val state by component.state.collectAsStateCompat()
    val hasBothDevice = state.draft.controllers.any { controller ->
        controller.controllerType == ControllerType.BEGODE &&
            state.draft.packs.any { pack ->
                pack.address == controller.address && pack.bmsType == BmsType.BEGODE
            }
    }
    val hasSeparateBms = state.draft.packs.any { pack ->
        state.draft.controllers.none { controller ->
            controller.controllerType == ControllerType.BEGODE &&
                controller.address == pack.address &&
                pack.bmsType == BmsType.BEGODE
        }
    }
    val hasDerivedBattery = state.draft.controllers.any {
        state.draft.resolvedDerivedBattery(it)
    }
    WizardPage(
        title = stringResource(Res.string.wizard_battery_title),
        progress = 3,
        onBack = component::onBack,
        footer = {
            TextButton(onClick = component::onNoBattery) {
                Text(stringResource(Res.string.wizard_no_battery))
            }
            Button(onClick = component::onNext) { Text(stringResource(Res.string.wizard_next)) }
        }
    ) {
        Text(
            stringResource(Res.string.wizard_battery_question),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        BatteryChoiceCard(
            selected = hasSeparateBms,
            title = Res.string.wizard_battery_own_bms,
            caption = Res.string.wizard_battery_own_bms_caption,
            badge = "BMS",
            onClick = null
        )
        BatteryChoiceCard(
            selected = hasDerivedBattery,
            title = Res.string.wizard_battery_from_controller,
            caption = Res.string.wizard_battery_from_controller_caption,
            badge = "≈",
            onClick = component::onUseControllerBattery,
            enabled = component.canUseControllerBattery
        )
        BatteryChoiceCard(
            selected = hasBothDevice,
            title = Res.string.wizard_battery_device_is_both,
            caption = Res.string.wizard_battery_device_is_both_caption,
            badge = "2×",
            onClick = null
        )
        ScanHeading(state.scannedDevices.size, state.scanning)
        ScannedDeviceList(
            rows = component.scanRows,
            onAdd = { device, add ->
                when (add) {
                    ScannedAdd.CONTROLLER -> component.onAddScannedDevice(device, add)
                    ScannedAdd.BATTERY -> component.onUseSeparateBms(device)
                    ScannedAdd.WHEEL -> component.onUseDeviceAsBoth(device)
                }
            }
        )
        Hint(stringResource(Res.string.wizard_scan_hint))
        if (state.advanceBlocked) {
            ErrorHint(stringResource(Res.string.wizard_empty_draft))
        }
        EditableDraftSources(state, component)
        if (state.showTopologyChoice) {
            Text(
                stringResource(Res.string.wizard_topology_question),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            TopologyChoice(
                selected = state.topology == PackTopology.PARALLEL,
                title = Res.string.topology_parallel,
                caption = Res.string.wizard_topology_parallel_caption,
                onClick = { component.onTopologyChanged(PackTopology.PARALLEL) }
            )
            TopologyChoice(
                selected = state.topology == PackTopology.SERIES,
                title = Res.string.topology_series,
                caption = Res.string.wizard_topology_series_caption,
                onClick = { component.onTopologyChanged(PackTopology.SERIES) }
            )
        }
    }
}

@Composable
private fun ReviewScreen(component: SetupWizardComponent.ReviewStage) {
    val state by component.state.collectAsStateCompat()
    val diagram = draftDiagram(state.draft, state.issues)
    WizardPage(
        title = stringResource(Res.string.wizard_review_title),
        progress = 4,
        onBack = component::onBack,
        backEnabled = state.navigationEnabled,
        footer = {
            TextButton(onClick = component::onBack, enabled = state.navigationEnabled) {
                Text(stringResource(Res.string.wizard_back))
            }
            Button(onClick = component::onSave, enabled = state.canSave && !state.saving) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(Res.string.wizard_save))
                }
            }
        }
    ) {
        Text(
            state.name.ifBlank { stringResource(Res.string.wizard_new_vehicle) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            stringResource(
                Res.string.wizard_summary,
                diagram.children.size,
                state.draft.sourceCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        WizardSectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(Res.string.vehicle_diagram_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    stringResource(Res.string.diagram_type_phone),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            DraftDiagramView(diagram)
        }
        if (!state.canSave || state.saveBlocked) {
            ErrorHint(stringResource(Res.string.wizard_save_blocked))
        }
    }
}

@Composable
private fun DoneScreen(component: SetupWizardComponent.DoneStage) {
    val state by component.state.collectAsStateCompat()
    val vehicle = state.savedVehicle ?: return
    WizardPage(
        title = stringResource(Res.string.wizard_done_title, vehicle.name),
        progress = 5,
        onBack = component::onShowVehicleList,
        footer = {
            OutlinedButton(onClick = component::onShowVehicleList) {
                Text(stringResource(Res.string.wizard_to_list))
            }
            Button(onClick = component::onConnect, enabled = !state.connecting) {
                Text(
                    stringResource(
                        if (state.connecting) Res.string.wizard_connecting else Res.string.wizard_connect
                    )
                )
            }
        }
    ) {
        Text(stringResource(Res.string.wizard_done_subtitle))
        DraftSources(state)
        Hint(stringResource(Res.string.wizard_done_note))
        if (state.connectFailed) ErrorHint(stringResource(Res.string.wizard_connect_failed))
    }
}

@Composable
private fun WizardPage(
    title: String,
    progress: Int,
    onBack: () -> Unit,
    footer: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    backGlyph: String = "‹",
    backEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val panelShape = RoundedCornerShape(18.dp)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, enabled = backEnabled && backGlyph.isNotEmpty()) {
                    Text(backGlyph, fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleSmall
                )
                ProgressDots(progress)
            }
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = 620.dp)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = panelShape,
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            shadowElevation = 3.dp
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                content = content
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    content = footer
                )
            }
        }
    }
}

@Composable
private fun ProgressDots(active: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 3.dp)
                    .background(
                        if (index < active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(50)
                    )
            )
        }
    }
}

@Composable
private fun ArchetypeChoice(
    selected: Boolean,
    title: StringResource,
    caption: StringResource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .heightIn(min = 86.dp)
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold, color = contentColor)
            Text(
                stringResource(caption),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopologyChoice(
    selected: Boolean,
    title: StringResource,
    caption: StringResource,
    onClick: () -> Unit
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape
            )
            .clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(11.dp), horizontalAlignment = Alignment.Start) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(caption),
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) contentColor else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanHeading(count: Int, scanning: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        } else {
            Box(
                modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
            )
        }
        Text(
            stringResource(Res.string.wizard_scanning, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScannedDeviceList(
    rows: List<SetupWizardComponent.ScanRow>,
    onAdd: (DiscoveredDevice, ScannedAdd) -> Unit
) {
    rows.forEach { row ->
        val identity = row.device.scanDeviceLabel()
        val shape = RoundedCornerShape(10.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            shape = shape,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(identity.title, fontWeight = FontWeight.SemiBold)
                        Text(
                            identity.address.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    WizardBadge(sourceRoleText(row.device.sourceRole()), live = row.device.controllerType != null)
                    Text(
                        "${row.device.rssi} dBm · ${scanSignalProximityText(row.device.signalProximity())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    row.additions.forEach { add ->
                        TextButton(
                            onClick = { onAdd(row.device, add) },
                            modifier = Modifier.weight(1f),
                            contentPadding = ButtonDefaults.TextButtonContentPadding
                        ) {
                            Text(scanAdditionText(add), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun scanSignalProximityText(proximity: SignalProximity): String = stringResource(
    when (proximity) {
        SignalProximity.WARMER -> Res.string.picker_signal_warmer
        SignalProximity.COLDER -> Res.string.picker_signal_colder
    }
)

@Composable
private fun scanAdditionText(add: ScannedAdd): String = stringResource(
    when (add) {
        ScannedAdd.CONTROLLER -> Res.string.wizard_add_controller
        ScannedAdd.BATTERY -> Res.string.wizard_add_battery
        ScannedAdd.WHEEL -> Res.string.wizard_add_wheel
    }
)

@Composable
private fun sourceRoleText(role: SourceRole): String = stringResource(
    when (role) {
        SourceRole.CONTROLLER -> Res.string.scan_role_controller
        SourceRole.BATTERY -> Res.string.scan_role_battery
        SourceRole.BOTH -> Res.string.scan_role_both
        SourceRole.UNKNOWN -> Res.string.scan_role_unknown
    }
)

@Composable
private fun WizardSectionCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)
    ) {
        Column(
            modifier = Modifier.padding(11.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            content = content
        )
    }
}

@Composable
private fun BatteryChoiceCard(
    selected: Boolean,
    title: StringResource,
    caption: StringResource,
    badge: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(10.dp)
    val borderColor = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() },
        shape = shape,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(title),
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            WizardBadge(badge, live = selected)
        }
    }
}

@Composable
private fun WizardBadge(text: String, live: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (live) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (live) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Setup's source cards. All mutation stays in [SetupWizardComponent.SourceStage];
 * these controls only expose the corrections the approved flow requires.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditableDraftSources(
    state: SetupWizardComponent.State,
    component: SetupWizardComponent.SourceStage
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.draft.controllers.forEachIndexed { index, controller ->
            SourceEditCard(
                title = "${stringResource(Res.string.vehicle_source_controller)} ${index + 1}",
                subtitle = controller.address,
                onRemove = { component.onRemoveController(controller.key) }
            ) {
                WizardTypeChips(
                    options = ControllerType.entries,
                    selected = controller.controllerType,
                    text = { it.label },
                    onSelect = { component.onControllerTypeChanged(controller.key, it) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardIntField(
                        label = stringResource(Res.string.vehicle_field_wheel_diameter),
                        value = controller.motor.wheelDiameterMm,
                        onChange = { component.onControllerWheelDiameterChanged(controller.key, it) },
                        modifier = Modifier.weight(1f)
                    )
                    WizardIntField(
                        label = stringResource(Res.string.vehicle_field_pole_pairs),
                        value = controller.motor.polePairs,
                        onChange = { component.onControllerPolePairsChanged(controller.key, it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardFloatField(
                        label = stringResource(Res.string.vehicle_field_gear_ratio),
                        value = controller.motor.gearRatio,
                        onChange = { component.onControllerGearRatioChanged(controller.key, it) },
                        modifier = Modifier.weight(1f)
                    )
                    WizardIntField(
                        label = stringResource(Res.string.vehicle_field_can_id),
                        value = controller.canId,
                        onChange = { component.onControllerCanIdChanged(controller.key, it) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        state.draft.packs.forEachIndexed { index, pack ->
            SourceEditCard(
                title = "${stringResource(Res.string.vehicle_source_pack)} ${index + 1}",
                subtitle = pack.address,
                onRemove = { component.onRemovePack(pack.key) }
            ) {
                WizardTypeChips(
                    options = BmsType.entries,
                    selected = pack.bmsType,
                    text = { bmsTypeLabel(it) },
                    onSelect = { component.onPackTypeChanged(pack.key, it) }
                )
            }
        }
    }
}

@Composable
private fun SourceEditCard(
    title: String,
    subtitle: String,
    onRemove: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove) {
                Text(
                    stringResource(Res.string.vehicle_source_remove),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> WizardTypeChips(
    options: List<T>,
    selected: T,
    text: @Composable (T) -> String,
    onSelect: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(text(option), fontSize = 11.sp) }
            )
        }
    }
}

@Composable
private fun WizardIntField(
    label: String,
    value: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { onChange(it.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun WizardFloatField(
    label: String,
    value: Float?,
    onChange: (Float?) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { onChange(it.replace(',', '.').toFloatOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun DraftSources(state: SetupWizardComponent.State) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.draft.controllers.forEach { controller ->
            SourceSummary(
                title = controller.label,
                subtitle = "${controller.controllerType.label}  ·  ${controller.address}"
            )
        }
        state.draft.packs.forEach { pack ->
            SourceSummary(
                title = pack.label,
                subtitle = "${bmsTypeLabel(pack.bmsType)}  ·  ${pack.address}"
            )
        }
    }
}

@Composable
private fun SourceSummary(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp)
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ErrorHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat() =
    collectAsState()

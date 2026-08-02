package ru.sodovaya.volty.presentation.vehicle.wizard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.repository.DiscoveredDevice
import ru.sodovaya.volty.presentation.common.bmsTypeLabel
import ru.sodovaya.volty.presentation.picker.ScannedAdd
import ru.sodovaya.volty.presentation.picker.SignalProximity
import ru.sodovaya.volty.presentation.picker.SourceRole
import ru.sodovaya.volty.presentation.picker.scanDeviceLabel
import ru.sodovaya.volty.presentation.picker.signalProximity
import ru.sodovaya.volty.presentation.picker.sourceRole
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
            title = { Text(stringResource(Res.string.vehicle_discard_title)) },
            text = { Text(stringResource(Res.string.vehicle_discard_text)) },
            confirmButton = {
                TextButton(onClick = component::onDiscardConfirmed) {
                    Text(stringResource(Res.string.vehicle_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = component::onDiscardDismissed) {
                    Text(stringResource(Res.string.vehicle_discard_keep))
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        OutlinedTextField(
            value = state.name,
            onValueChange = component::onNameChanged,
            label = { Text(stringResource(Res.string.wizard_vehicle_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        ScanHeading(state.scannedDevices.size)
        ScannedDeviceList(
            rows = component.scanRows,
            onAdd = component::onAddScannedDevice
        )
        Hint(stringResource(Res.string.wizard_scan_hint))
        DraftSources(state)
    }
}

@Composable
private fun BatteryScreen(component: SetupWizardComponent.BatteryStage) {
    val state by component.state.collectAsStateCompat()
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
        Text(stringResource(Res.string.wizard_battery_own_bms), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.wizard_battery_own_bms_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = component::onUseControllerBattery,
            enabled = component.canUseControllerBattery,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(Res.string.wizard_battery_from_controller))
        }
        Text(
            stringResource(Res.string.wizard_battery_from_controller_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(stringResource(Res.string.wizard_battery_device_is_both), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(Res.string.wizard_battery_device_is_both_caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ScanHeading(state.scannedDevices.size)
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
        if (state.advanceBlocked) {
            ErrorHint(stringResource(Res.string.wizard_empty_draft))
        }
        DraftSources(state)
    }
}

@Composable
private fun ReviewScreen(component: SetupWizardComponent.ReviewStage) {
    val state by component.state.collectAsStateCompat()
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
                state.draft.linkAddresses.size,
                state.draft.sourceCount
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DraftSources(state)
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
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, enabled = backEnabled && backGlyph.isNotEmpty()) {
                    Text(backGlyph, fontSize = 24.sp)
                }
                Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                ProgressDots(progress)
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                content = footer
            )
        }
    }
}

@Composable
private fun ProgressDots(active: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .size(7.dp)
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
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = contentColor
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(stringResource(title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(caption), style = MaterialTheme.typography.bodySmall, color = contentColor)
        }
    }
}

@Composable
private fun ScanHeading(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(stringResource(Res.string.wizard_scanning, count), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ScannedDeviceList(
    rows: List<SetupWizardComponent.ScanRow>,
    onAdd: (DiscoveredDevice, ScannedAdd) -> Unit
) {
    rows.forEach { row ->
        val identity = row.device.scanDeviceLabel()
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    identity.title,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    listOfNotNull(
                        identity.address,
                        sourceRoleText(row.device.sourceRole()),
                        scanSignalProximityText(row.device.signalProximity())
                    ).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    row.additions.forEach { add ->
                        TextButton(
                            onClick = { onAdd(row.device, add) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(scanAdditionText(add), fontSize = 12.sp)
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

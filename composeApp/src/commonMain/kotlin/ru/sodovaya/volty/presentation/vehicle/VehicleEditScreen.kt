package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.presentation.common.chemistryLabel
import ru.sodovaya.volty.presentation.common.dashboardStyleLabel
import ru.sodovaya.volty.presentation.common.iconKeyToEmoji
import ru.sodovaya.volty.presentation.common.secondaryGaugeLabel
import ru.sodovaya.volty.presentation.common.settings.PageDirection
import ru.sodovaya.volty.presentation.common.settings.SettingsDangerRow
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import ru.sodovaya.volty.presentation.common.settings.SettingsNavRow
import ru.sodovaya.volty.presentation.common.settings.SettingsRowDivider
import ru.sodovaya.volty.presentation.common.settings.SettingsTint
import ru.sodovaya.volty.presentation.common.settings.pageDirection
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.action_delete
import volty.composeapp.generated.resources.alerts_open_subtitle
import volty.composeapp.generated.resources.dashboard_style_default
import volty.composeapp.generated.resources.vehicle_battery_source_both
import volty.composeapp.generated.resources.vehicle_battery_source_controller
import volty.composeapp.generated.resources.vehicle_battery_source_none
import volty.composeapp.generated.resources.vehicle_battery_source_own
import volty.composeapp.generated.resources.vehicle_delete_confirm_text
import volty.composeapp.generated.resources.vehicle_delete_confirm_title
import volty.composeapp.generated.resources.vehicle_discard_confirm
import volty.composeapp.generated.resources.vehicle_discard_keep
import volty.composeapp.generated.resources.vehicle_discard_text
import volty.composeapp.generated.resources.vehicle_discard_title
import volty.composeapp.generated.resources.vehicle_edit_edit
import volty.composeapp.generated.resources.vehicle_edit_new
import volty.composeapp.generated.resources.vehicle_field_name_required
import volty.composeapp.generated.resources.vehicle_hub_alerts
import volty.composeapp.generated.resources.vehicle_hub_battery
import volty.composeapp.generated.resources.vehicle_hub_controller
import volty.composeapp.generated.resources.vehicle_hub_controller_more
import volty.composeapp.generated.resources.vehicle_hub_dashboard
import volty.composeapp.generated.resources.vehicle_hub_delete
import volty.composeapp.generated.resources.vehicle_hub_group_device
import volty.composeapp.generated.resources.vehicle_hub_group_ride
import volty.composeapp.generated.resources.vehicle_hub_identity
import volty.composeapp.generated.resources.vehicle_hub_issue_prefix
import volty.composeapp.generated.resources.vehicle_hub_layout
import volty.composeapp.generated.resources.vehicle_hub_layout_summary
import volty.composeapp.generated.resources.vehicle_hub_motor
import volty.composeapp.generated.resources.vehicle_hub_motor_summary
import volty.composeapp.generated.resources.vehicle_hub_motor_summary_no_diameter
import volty.composeapp.generated.resources.vehicle_hub_motor_unset
import volty.composeapp.generated.resources.vehicle_hub_none_added
import volty.composeapp.generated.resources.vehicle_hub_off
import volty.composeapp.generated.resources.vehicle_hub_on
import volty.composeapp.generated.resources.vehicle_hub_speed_volume
import volty.composeapp.generated.resources.vehicle_save
import volty.composeapp.generated.resources.vehicle_save_blocked

private const val VEHICLE_PAGE_MS = 250

/**
 * The vehicle editor: a hub of sections over ONE draft, and a renderer and nothing more - see
 * `VehicleSourceCards.kt` for what that means and why it has to be true here.
 *
 * Cancel and Save are in the app bar on the hub AND on every page: a page edits the same
 * [VehicleEditComponent.State] the hub does, so nothing is written until Save and leaving a page
 * loses nothing. The rows below the header are summaries computed by the pure functions in
 * `VehicleEditSections.kt`; the pages are in `VehicleEditPages.kt`.
 *
 * Every pre-existing control lives on exactly one page, and "Units" is no longer among them: it
 * is app-wide and has its own page in Settings (a per-vehicle link to it was a link to a thing
 * that is not the vehicle's).
 *
 * The old create-only read-only BMS header is gone: `State.canComposeSources` is constant true,
 * so that branch had been unreachable since the composer owned creation too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VehicleEditScreen(component: VehicleEditComponent) {
    val state by component.state.collectAsState()
    val section by component.section.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // One scroll state per page, so a long page does not hand its offset to the next one.
    val hubScroll = remember { ScrollState(0) }
    val pageScrolls = remember { VehicleEditSection.entries.associateWith { ScrollState(0) } }

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
                    // `saveBlocked`, which prints the banner and points at the
                    // sources that contradict each other.
                    TextButton(onClick = component::onSave, enabled = !state.saving) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(Res.string.vehicle_save))
                    }
                }
            )
        }
    ) { padding ->
        AnimatedContent(
            targetState = section,
            modifier = Modifier.padding(padding).fillMaxSize(),
            transitionSpec = {
                val back = pageDirection(targetIsHub = targetState == null) == PageDirection.BACK
                val spec = tween<IntOffset>(VEHICLE_PAGE_MS)
                val fade = tween<Float>(VEHICLE_PAGE_MS)
                if (back) {
                    (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { it } + fadeOut(fade))
                } else {
                    (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade))
                }
            },
            label = "vehicle-edit-page",
        ) { shown ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(shown?.let { pageScrolls.getValue(it) } ?: hubScroll)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
            when (val open = shown) {
                null -> VehicleEditHub(state, component, onDelete = { showDeleteConfirm = true })
                else -> VehicleEditPage(open, state, component)
            }
            Spacer(Modifier.height(24.dp))
        }
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
private fun VehicleEditHub(
    state: VehicleEditComponent.State,
    component: VehicleEditComponent,
    onDelete: () -> Unit
) {
    VehicleHeader(state, onClick = { component.onOpenSection(VehicleEditSection.IDENTITY) })

    val issues = state.issuesBySource
    val controllerIssue = state.draft.controllers.any { issues[it.key].orEmpty().isNotEmpty() }
    val packIssue = state.draft.packs.any { issues[it.key].orEmpty().isNotEmpty() }
    val attention = stringResource(Res.string.vehicle_hub_issue_prefix)
    val notAdded = stringResource(Res.string.vehicle_hub_none_added)

    SettingsGroup(stringResource(Res.string.vehicle_hub_group_device)) {
        val controller = controllerSummary(state.draft)
        SettingsNavRow(
            icon = Icons.Filled.Memory,
            tint = SettingsTint.VEHICLE,
            title = stringResource(Res.string.vehicle_hub_controller),
            summary = if (controllerIssue) attention else when {
                controller == null -> notAdded
                else -> {
                    val head = "${controller.type.label} · ${controller.address.ifBlank { "-" }}"
                    if (controller.others > 0) stringResource(Res.string.vehicle_hub_controller_more, head, controller.others) else head
                }
            },
            onClick = { component.onOpenSection(VehicleEditSection.CONTROLLER) }
        )
        SettingsRowDivider()
        SettingsNavRow(
            icon = Icons.Filled.BatteryChargingFull,
            tint = SettingsTint.VEHICLE,
            title = stringResource(Res.string.vehicle_hub_battery),
            summary = if (packIssue) attention else batteryLine(batterySummary(state.draft, state.chemistry, state.learnedCellCount)),
            onClick = { component.onOpenSection(VehicleEditSection.BATTERY) }
        )
        SettingsRowDivider()
        val motor = motorSummary(state.draft)
        SettingsNavRow(
            icon = Icons.Filled.Settings,
            tint = SettingsTint.VEHICLE,
            title = stringResource(Res.string.vehicle_hub_motor),
            summary = when {
                motor.polePairs == null -> stringResource(Res.string.vehicle_hub_motor_unset)
                motor.wheelDiameterMm == null -> stringResource(Res.string.vehicle_hub_motor_summary_no_diameter, motor.polePairs)
                else -> stringResource(Res.string.vehicle_hub_motor_summary, motor.polePairs, motor.wheelDiameterMm)
            },
            onClick = { component.onOpenSection(VehicleEditSection.MOTOR) }
        )
        SettingsRowDivider()
        SettingsNavRow(
            icon = Icons.Filled.AccountTree,
            tint = SettingsTint.VEHICLE,
            title = stringResource(Res.string.vehicle_hub_layout),
            summary = stringResource(Res.string.vehicle_hub_layout_summary, state.draft.controllers.size, state.draft.packs.size),
            onClick = { component.onOpenSection(VehicleEditSection.LAYOUT) }
        )
    }

    SettingsGroup(stringResource(Res.string.vehicle_hub_group_ride)) {
        SettingsNavRow(
            icon = Icons.Filled.Speed,
            tint = SettingsTint.DRIVE,
            title = stringResource(Res.string.vehicle_hub_dashboard),
            summary = (state.dashboardStyle?.let { dashboardStyleLabel(it) } ?: stringResource(Res.string.dashboard_style_default)) +
                " · " + secondaryGaugeLabel(state.secondaryGauge),
            onClick = { component.onOpenSection(VehicleEditSection.DASHBOARD) }
        )
        // Motion alerts live on their own screen (F Task 9) and persist onto a row that must
        // exist, so only a SAVED vehicle offers them.
        if (state.isEditing) {
            SettingsRowDivider()
            SettingsNavRow(
                icon = Icons.Filled.NotificationsActive,
                tint = SettingsTint.SOUND,
                title = stringResource(Res.string.vehicle_hub_alerts),
                summary = stringResource(Res.string.alerts_open_subtitle),
                onClick = component::onOpenAlerts
            )
        }
        SettingsRowDivider()
        val auto = state.autoVolume
        SettingsNavRow(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            tint = SettingsTint.SOUND,
            title = stringResource(Res.string.vehicle_hub_speed_volume),
            summary = if (auto.enabled) {
                stringResource(Res.string.vehicle_hub_on) + " · ${auto.minVolumePercent}–${auto.maxVolumePercent}%"
            } else stringResource(Res.string.vehicle_hub_off),
            onClick = { component.onOpenSection(VehicleEditSection.SPEED_VOLUME) }
        )
    }

    if (state.isEditing) {
        SettingsGroup(null) { SettingsDangerRow(stringResource(Res.string.vehicle_hub_delete), onClick = onDelete) }
    }
}

/** The vehicle's icon, large, and its name. Tapping it opens the name-and-icon page. */
@Composable
private fun VehicleHeader(state: VehicleEditComponent.State, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(onClick = onClick)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(iconKeyToEmoji(state.iconKey), fontSize = 30.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.name.ifBlank { stringResource(Res.string.vehicle_edit_new) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(Res.string.vehicle_hub_identity),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.nameError) {
            Text(
                stringResource(Res.string.vehicle_field_name_required),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
    }
}

@Composable
private fun batteryLine(summary: BatterySummary): String {
    val source = stringResource(
        when (summary.source) {
            BatterySource.OWN_BMS -> Res.string.vehicle_battery_source_own
            BatterySource.FROM_CONTROLLER -> Res.string.vehicle_battery_source_controller
            BatterySource.DEVICE_IS_BOTH -> Res.string.vehicle_battery_source_both
            BatterySource.NONE -> Res.string.vehicle_battery_source_none
        }
    )
    if (summary.source == BatterySource.NONE) return source
    return buildString {
        append(source)
        summary.cellCount?.let { append(" · ${it}S") }
        append(" · ").append(chemistryLabel(summary.chemistry))
    }
}

package ru.sodovaya.volty.presentation.alerts

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.presentation.common.alertAvailabilityNote
import ru.sodovaya.volty.presentation.common.motionAlertKindLabel
import ru.sodovaya.volty.presentation.common.motionAlertUnitLabel
import ru.sodovaya.volty.domain.alert.AlarmMusicMode
import ru.sodovaya.volty.presentation.alerts.BatteryAlertDraft
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.alerts_add_level
import volty.composeapp.generated.resources.alerts_battery_cell_delta
import volty.composeapp.generated.resources.alerts_battery_cell_high
import volty.composeapp.generated.resources.alerts_battery_cell_low
import volty.composeapp.generated.resources.alerts_battery_charge_complete
import volty.composeapp.generated.resources.alerts_battery_disconnect
import volty.composeapp.generated.resources.alerts_battery_section
import volty.composeapp.generated.resources.alerts_battery_soc_cutoff
import volty.composeapp.generated.resources.alerts_battery_soc_low
import volty.composeapp.generated.resources.alerts_battery_temperature_high
import volty.composeapp.generated.resources.alerts_battery_temperature_warn
import volty.composeapp.generated.resources.alerts_default_threshold
import volty.composeapp.generated.resources.alerts_discard_confirm
import volty.composeapp.generated.resources.alerts_discard_keep
import volty.composeapp.generated.resources.alerts_discard_text
import volty.composeapp.generated.resources.alerts_discard_title
import volty.composeapp.generated.resources.alerts_kind_off
import volty.composeapp.generated.resources.alerts_level
import volty.composeapp.generated.resources.alerts_level_muted
import volty.composeapp.generated.resources.alerts_master
import volty.composeapp.generated.resources.alerts_master_subtitle
import volty.composeapp.generated.resources.alerts_music_mode
import volty.composeapp.generated.resources.alerts_music_mode_duck
import volty.composeapp.generated.resources.alerts_music_mode_overlay
import volty.composeapp.generated.resources.alerts_music_mode_subtitle
import volty.composeapp.generated.resources.alerts_preview
import volty.composeapp.generated.resources.alerts_preview_level
import volty.composeapp.generated.resources.alerts_preview_subtitle
import volty.composeapp.generated.resources.alerts_remove_level
import volty.composeapp.generated.resources.alerts_save
import volty.composeapp.generated.resources.alerts_section_sound
import volty.composeapp.generated.resources.alerts_section_thresholds
import volty.composeapp.generated.resources.alerts_section_thresholds_subtitle
import volty.composeapp.generated.resources.alerts_threshold_invalid
import volty.composeapp.generated.resources.alerts_title
import volty.composeapp.generated.resources.alerts_tone
import volty.composeapp.generated.resources.alerts_vibration

/**
 * A renderer, and deliberately nothing more.
 *
 * Compose UI is not unit-testable in this repo (no Robolectric, no instrumented
 * tests), so every decision this screen appears to make — whether a row is
 * editable, whether the switch is on, which levels can be added, what a
 * threshold parses to, whether Save is allowed — has already been made in
 * `MotionAlertEditing.kt` or [VehicleAlertsComponent], where tests can reach it.
 * The only judgement left here is which colour to draw with.
 *
 * The threshold field renders the component's **text** rather than a formatted
 * number, so there is no local `remember` to fall out of sync with the state and
 * no re-parse to fight the rider mid-typing.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VehicleAlertsScreen(component: VehicleAlertsComponent) {
    val state by component.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.alerts_title)) },
                navigationIcon = {
                    TextButton(onClick = component::onBack) { Text(stringResource(Res.string.action_cancel)) }
                },
                actions = {
                    TextButton(onClick = component::onSave, enabled = state.canSave) {
                        if (state.saving) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(Res.string.alerts_save))
                    }
                }
            )
        }
    ) { padding ->
        if (!state.loaded) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.vehicleName.isNotBlank()) {
                Text(state.vehicleName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // --- Global modalities (F §4). Applied on the spot, not on Save:
            // a rider reaching for "tone off" mid-alarm means now.
            SectionLabel(stringResource(Res.string.alerts_section_sound))
            SwitchRow(
                label = stringResource(Res.string.alerts_master),
                subtitle = stringResource(Res.string.alerts_master_subtitle),
                checked = state.alarmEnabled,
                enabled = true,
                onChange = component::onAlarmEnabledChanged
            )
            SwitchRow(
                label = stringResource(Res.string.alerts_tone),
                subtitle = null,
                checked = state.toneEnabled,
                // The master switch is the whole point of being a master: with it
                // off neither modality can do anything, so neither pretends to.
                enabled = state.alarmEnabled,
                onChange = component::onToneEnabledChanged
            )
            SwitchRow(
                label = stringResource(Res.string.alerts_vibration),
                subtitle = null,
                checked = state.vibrationEnabled,
                enabled = state.alarmEnabled,
                onChange = component::onVibrationEnabledChanged
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(Res.string.alerts_music_mode), fontSize = 14.sp)
                Caption(stringResource(Res.string.alerts_music_mode_subtitle))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = state.musicMode == AlarmMusicMode.DUCK_MEDIA,
                        onClick = { component.onAlarmMusicModeChanged(AlarmMusicMode.DUCK_MEDIA) },
                        label = { Text(stringResource(Res.string.alerts_music_mode_duck)) }
                    )
                    FilterChip(
                        selected = state.musicMode == AlarmMusicMode.PLAY_OVER_MEDIA,
                        onClick = { component.onAlarmMusicModeChanged(AlarmMusicMode.PLAY_OVER_MEDIA) },
                        label = { Text(stringResource(Res.string.alerts_music_mode_overlay)) }
                    )
                }
            }

            HorizontalDivider()

            SectionLabel(stringResource(Res.string.alerts_battery_section))
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_cell_high),
                value = state.battery.cellHigh,
                suffix = "V",
                enabled = state.battery.cellHighEnabled,
                onValueChange = component::onCellHighChanged,
                onEnabledChange = component::onCellHighEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_cell_low),
                value = state.battery.cellLow,
                suffix = "V",
                enabled = state.battery.cellLowEnabled,
                onValueChange = component::onCellLowChanged,
                onEnabledChange = component::onCellLowEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_cell_delta),
                value = state.battery.cellDelta,
                suffix = "mV",
                enabled = state.battery.cellDeltaEnabled,
                onValueChange = component::onCellDeltaChanged,
                onEnabledChange = component::onCellDeltaEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_temperature_warn),
                value = state.battery.temperatureWarn,
                suffix = "°C",
                enabled = state.battery.temperatureWarnEnabled,
                onValueChange = component::onTemperatureWarnChanged,
                onEnabledChange = component::onTemperatureWarnEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_temperature_high),
                value = state.battery.temperatureHigh,
                suffix = "°C",
                enabled = state.battery.temperatureHighEnabled,
                onValueChange = component::onTemperatureHighChanged,
                onEnabledChange = component::onTemperatureHighEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_soc_low),
                value = state.battery.socLow,
                suffix = "%",
                enabled = state.battery.socLowEnabled,
                onValueChange = component::onSocLowChanged,
                onEnabledChange = component::onSocLowEnabledChanged,
            )
            BatteryAlertRow(
                label = stringResource(Res.string.alerts_battery_soc_cutoff),
                value = state.battery.socCutoff,
                suffix = "%",
                enabled = state.battery.socCutoffEnabled,
                onValueChange = component::onSocCutoffChanged,
                onEnabledChange = component::onSocCutoffEnabledChanged,
            )
            SwitchRow(
                label = stringResource(Res.string.alerts_battery_disconnect),
                subtitle = null,
                checked = state.battery.disconnectEnabled,
                enabled = true,
                onChange = component::onDisconnectNotifyChanged,
            )
            SwitchRow(
                label = stringResource(Res.string.alerts_battery_charge_complete),
                subtitle = null,
                checked = state.battery.chargeCompleteEnabled,
                enabled = true,
                onChange = component::onChargeCompleteNotifyChanged,
            )

            HorizontalDivider()

            // --- "Проверить сигнал" (F §11): the tone curve is not settled on
            // paper, so the rider judges it by ear on a real phone.
            SectionLabel(stringResource(Res.string.alerts_preview))
            Caption(stringResource(Res.string.alerts_preview_subtitle))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.previewLevels.forEach { level ->
                    OutlinedButton(
                        onClick = { component.onPreview(level) },
                        // canPreview, not alarmEnabled: with the master on but
                        // tone and vibration both off the preview provably makes
                        // no sound, and a live button that does nothing reads as
                        // a broken alarm. The component owns that decision.
                        enabled = state.canPreview
                    ) { Text(stringResource(Res.string.alerts_preview_level, level)) }
                }
            }

            HorizontalDivider()

            SectionLabel(stringResource(Res.string.alerts_section_thresholds))
            Caption(stringResource(Res.string.alerts_section_thresholds_subtitle))

            // Every kind, always — including the ones this hardware cannot
            // supply, which are greyed WITH their reason (F §10). An absent row
            // leaves the rider wondering; a greyed one teaches them what their
            // hardware measures.
            state.kinds.forEach { draft ->
                AlertKindCard(
                    draft = draft,
                    onEnabledChanged = { on -> component.onKindEnabledChanged(draft.kind, on) },
                    onThresholdChanged = { i, t -> component.onThresholdChanged(draft.kind, i, t) },
                    onLevelEnabledChanged = { i, on -> component.onLevelEnabledChanged(draft.kind, i, on) },
                    onAddLevel = { component.onAddLevel(draft.kind) },
                    onRemoveLevel = { i -> component.onRemoveLevel(draft.kind, i) }
                )
            }

            Spacer(Modifier.height(24.dp))
        }

        // Backing out of unsaved threshold edits asks first. Whether there is
        // anything to lose is the component's answer (State.isDirty), not a
        // `remember` here — the two commit models on this screen make it a
        // decision worth testing.
        if (state.discardPrompt) {
            AlertDialog(
                onDismissRequest = component::onDiscardDismissed,
                confirmButton = {
                    TextButton(onClick = component::onDiscardConfirmed) {
                        Text(
                            stringResource(Res.string.alerts_discard_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = component::onDiscardDismissed) {
                        Text(stringResource(Res.string.alerts_discard_keep))
                    }
                },
                title = { Text(stringResource(Res.string.alerts_discard_title)) },
                text = { Text(stringResource(Res.string.alerts_discard_text)) }
            )
        }
    }
}

@Composable
private fun AlertKindCard(
    draft: AlertKindDraft,
    onEnabledChanged: (Boolean) -> Unit,
    onThresholdChanged: (Int, String) -> Unit,
    onLevelEnabledChanged: (Int, Boolean) -> Unit,
    onAddLevel: () -> Unit,
    onRemoveLevel: (Int) -> Unit
) {
    // isEditable is AlertAvailability.isConfigurable — Unknown edits, only
    // Unavailable greys. See AlertKindDraft.isEditable for why.
    val editable = draft.isEditable
    val titleColor =
        if (editable) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    motionAlertKindLabel(draft.kind),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                // The reason, in words — the whole point of showing the row.
                // Unknown gets its own sentence ("no data yet"), which must never
                // read as "your hardware lacks this sensor".
                alertAvailabilityNote(draft.availability)?.let { Caption(it) }
                if (editable && !draft.isOn) Caption(stringResource(Res.string.alerts_kind_off))
            }
            Switch(
                checked = draft.isOn,
                onCheckedChange = onEnabledChanged,
                enabled = editable
            )
        }

        draft.levels.forEachIndexed { index, level ->
            LevelRow(
                index = index,
                level = level,
                unit = motionAlertUnitLabel(draft.kind),
                enabled = editable,
                onThresholdChanged = { onThresholdChanged(index, it) },
                onEnabledChanged = { onLevelEnabledChanged(index, it) },
                onRemove = { onRemoveLevel(index) }
            )
        }

        if (draft.canAddLevel) {
            TextButton(onClick = onAddLevel) { Text(stringResource(Res.string.alerts_add_level)) }
        }

        HorizontalDivider()
    }
}

@Composable
private fun BatteryAlertRow(
    label: String,
    value: String,
    suffix: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            isError = value.isNotBlank() && value.toFloatOrNull() == null && value.toIntOrNull() == null,
            label = { Text(label) },
            placeholder = { Text(stringResource(Res.string.alerts_default_threshold)) },
            suffix = { Text(suffix) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onEnabledChange)
    }
}

@Composable
private fun LevelRow(
    index: Int,
    level: AlertLevelDraft,
    unit: String,
    enabled: Boolean,
    onThresholdChanged: (String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = level.text,
            onValueChange = onThresholdChanged,
            enabled = enabled,
            singleLine = true,
            isError = !level.isValid,
            label = { Text(stringResource(Res.string.alerts_level, index + 1)) },
            suffix = { Text(unit) },
            supportingText = {
                if (!level.isValid) Text(stringResource(Res.string.alerts_threshold_invalid))
                else if (!level.enabled) Text(stringResource(Res.string.alerts_level_muted))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f)
        )
        // Per-level mute: keeps the number the rider tuned while silencing that
        // one step. NOT a way to turn the alert off — that is an empty list.
        Switch(checked = level.enabled, onCheckedChange = onEnabledChanged, enabled = enabled)
        Spacer(Modifier.width(2.dp))
        TextButton(onClick = onRemove, enabled = enabled) {
            Text(stringResource(Res.string.alerts_remove_level), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            subtitle?.let { Caption(it) }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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

@Composable
private fun Caption(text: String) {
    Text(text = text, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

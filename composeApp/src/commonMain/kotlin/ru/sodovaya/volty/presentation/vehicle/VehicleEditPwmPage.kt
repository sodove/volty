package ru.sodovaya.volty.presentation.vehicle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.pwm.PwmCalibrationSource
import ru.sodovaya.volty.presentation.common.settings.SettingsDangerRow
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import ru.sodovaya.volty.presentation.common.settings.SettingsNavRow
import ru.sodovaya.volty.presentation.common.settings.SettingsRowDivider
import ru.sodovaya.volty.presentation.common.settings.SettingsTint
import ru.sodovaya.volty.presentation.vehicle.pwm.PwmEntry
import ru.sodovaya.volty.presentation.vehicle.pwm.PwmHubSummary
import ru.sodovaya.volty.presentation.vehicle.pwm.pwmHubSummaryOf
import kotlin.math.roundToInt
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.pwm_calibration_manual
import volty.composeapp.generated.resources.pwm_calibration_none
import volty.composeapp.generated.resources.pwm_calibration_row
import volty.composeapp.generated.resources.pwm_calibration_summary
import volty.composeapp.generated.resources.pwm_hub_calibrated
import volty.composeapp.generated.resources.pwm_hub_reports
import volty.composeapp.generated.resources.pwm_hub_uncalibrated
import volty.composeapp.generated.resources.pwm_hub_unknown
import volty.composeapp.generated.resources.pwm_manual_caption
import volty.composeapp.generated.resources.pwm_manual_invalid
import volty.composeapp.generated.resources.pwm_manual_kmh
import volty.composeapp.generated.resources.pwm_manual_title
import volty.composeapp.generated.resources.pwm_manual_volts
import volty.composeapp.generated.resources.pwm_reason_reports
import volty.composeapp.generated.resources.pwm_reason_save_first
import volty.composeapp.generated.resources.pwm_reason_unknown
import volty.composeapp.generated.resources.pwm_reset
import volty.composeapp.generated.resources.pwm_reset_text
import volty.composeapp.generated.resources.pwm_reset_title
import volty.composeapp.generated.resources.pwm_source_riding
import volty.composeapp.generated.resources.pwm_source_wizard
import volty.composeapp.generated.resources.pwm_threshold_caption
import volty.composeapp.generated.resources.pwm_threshold_invalid
import volty.composeapp.generated.resources.pwm_threshold_label
import volty.composeapp.generated.resources.vehicle_energy_reset_confirm

/** The hub row's summary line. Every decision is `pwmHubSummaryOf`'s; this only picks the words. */
@Composable
internal fun pwmHubLine(state: VehicleEditComponent.State): String =
    stringResource(
        when (pwmHubSummaryOf(state.pwmEvidence, state.pwmCalibration, state.pwmManual)) {
            PwmHubSummary.REPORTS -> Res.string.pwm_hub_reports
            PwmHubSummary.NOT_KNOWN_YET -> Res.string.pwm_hub_unknown
            PwmHubSummary.CALIBRATED -> Res.string.pwm_hub_calibrated
            PwmHubSummary.UNCALIBRATED -> Res.string.pwm_hub_uncalibrated
        }
    )

/**
 * The estimated-PWM page: the wizard entry (or the reason it is closed), the wheel's alarm threshold, the rider's
 * own free-spin point, and the reset. A renderer: whether the wizard is offered, which calibration is shown and
 * what is valid are decided by [VehicleEditComponent.State] and tested there. **Layout not verified on a device.**
 */
@Composable
internal fun PwmPage(state: VehicleEditComponent.State, component: VehicleEditComponent) {
    var confirmReset by remember { mutableStateOf(false) }

    SettingsGroup(null) {
        val entry = state.pwmEntry
        val reason = when {
            entry == PwmEntry.WHEEL_REPORTS -> stringResource(Res.string.pwm_reason_reports)
            entry == PwmEntry.NOT_KNOWN_YET -> stringResource(Res.string.pwm_reason_unknown)
            state.pwmWizardNeedsSave -> stringResource(Res.string.pwm_reason_save_first)
            else -> null
        }
        if (reason == null) {
            SettingsNavRow(
                icon = Icons.Filled.Tune,
                tint = SettingsTint.DRIVE,
                title = stringResource(Res.string.pwm_calibration_row),
                summary = calibrationLine(state),
                onClick = component::onOpenPwmWizard
            )
        } else {
            // Closed on purpose: the reason, never a disabled button that explains nothing.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(stringResource(Res.string.pwm_calibration_row), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    SettingsGroup(null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = state.pwmThresholdText,
                onValueChange = component::onPwmThresholdChanged,
                label = { Text(stringResource(Res.string.pwm_threshold_label)) },
                isError = state.pwmThresholdError,
                supportingText = {
                    Text(
                        stringResource(
                            if (state.pwmThresholdError) Res.string.pwm_threshold_invalid else Res.string.pwm_threshold_caption
                        )
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    SettingsGroup(stringResource(Res.string.pwm_manual_title)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = state.pwmManualKmhText,
                    onValueChange = component::onPwmManualKmhChanged,
                    label = { Text(stringResource(Res.string.pwm_manual_kmh)) },
                    isError = state.pwmManualError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = state.pwmManualVoltsText,
                    onValueChange = component::onPwmManualVoltsChanged,
                    label = { Text(stringResource(Res.string.pwm_manual_volts)) },
                    isError = state.pwmManualError,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f)
                )
            }
            Caption(
                stringResource(if (state.pwmManualError) Res.string.pwm_manual_invalid else Res.string.pwm_manual_caption)
            )
        }
    }

    if (state.pwmHasStoredCalibration) {
        SettingsGroup(null) {
            SettingsDangerRow(stringResource(Res.string.pwm_reset), onClick = { confirmReset = true })
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; component.onResetPwmCalibration() }) {
                    Text(stringResource(Res.string.vehicle_energy_reset_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(Res.string.action_cancel)) }
            },
            title = { Text(stringResource(Res.string.pwm_reset_title)) },
            text = { Text(stringResource(Res.string.pwm_reset_text)) }
        )
    }
}

/**
 * What the wizard row says about the calibration. A learned or wizard calibration is described by its own k; a typed
 * manual point by the sentence about it, never by a stored MANUAL row's stale k (see `pwmSummaryOf`).
 */
@Composable
private fun calibrationLine(state: VehicleEditComponent.State): String {
    val cal = state.pwmCalibration
    return when {
        cal != null -> stringResource(
            Res.string.pwm_calibration_summary,
            ((cal.k * 1000f).roundToInt() / 1000f).toString(),
            stringResource(if (cal.source == PwmCalibrationSource.WIZARD) Res.string.pwm_source_wizard else Res.string.pwm_source_riding)
        )
        state.pwmManual != null -> stringResource(Res.string.pwm_calibration_manual)
        else -> stringResource(Res.string.pwm_calibration_none)
    }
}

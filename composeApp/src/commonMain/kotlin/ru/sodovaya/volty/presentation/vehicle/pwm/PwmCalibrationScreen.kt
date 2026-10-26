package ru.sodovaya.volty.presentation.vehicle.pwm

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.presentation.common.settings.SettingsGroup
import kotlin.math.roundToInt
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.action_cancel
import volty.composeapp.generated.resources.pwm_wiz_again
import volty.composeapp.generated.resources.pwm_wiz_finish
import volty.composeapp.generated.resources.pwm_wiz_intro_alarm
import volty.composeapp.generated.resources.pwm_wiz_intro_lift
import volty.composeapp.generated.resources.pwm_wiz_intro_plateau
import volty.composeapp.generated.resources.pwm_wiz_live
import volty.composeapp.generated.resources.pwm_wiz_live_none
import volty.composeapp.generated.resources.pwm_wiz_notice_disconnected
import volty.composeapp.generated.resources.pwm_wiz_notice_no_data
import volty.composeapp.generated.resources.pwm_wiz_notice_not_connected
import volty.composeapp.generated.resources.pwm_wiz_notice_nothing
import volty.composeapp.generated.resources.pwm_wiz_plateau_caught
import volty.composeapp.generated.resources.pwm_wiz_plateau_waiting
import volty.composeapp.generated.resources.pwm_wiz_ratio_warn
import volty.composeapp.generated.resources.pwm_wiz_restart
import volty.composeapp.generated.resources.pwm_wiz_result_k
import volty.composeapp.generated.resources.pwm_wiz_result_onset
import volty.composeapp.generated.resources.pwm_wiz_result_plateau
import volty.composeapp.generated.resources.pwm_wiz_result_ratio
import volty.composeapp.generated.resources.pwm_wiz_result_title
import volty.composeapp.generated.resources.pwm_wiz_save
import volty.composeapp.generated.resources.pwm_wiz_save_failed
import volty.composeapp.generated.resources.pwm_wiz_start
import volty.composeapp.generated.resources.pwm_wiz_threshold_caught
import volty.composeapp.generated.resources.pwm_wiz_threshold_waiting
import volty.composeapp.generated.resources.pwm_wiz_title
import volty.composeapp.generated.resources.pwm_wiz_to_plateau
import volty.composeapp.generated.resources.vehicle_hub_back

private fun one(v: Float): String = ((v * 10f).roundToInt() / 10f).toString()
private fun three(v: Float): String = ((v * 1000f).roundToInt() / 1000f).toString()
private fun two(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()

/**
 * A renderer for [PwmCalibrationComponent.state]. Which step shows, which buttons exist and every number are the
 * machine's; this only turns them into words and a column. The instructions are deliberately short and never
 * suggest riding: the wheel is lifted and spun slowly by hand.
 *
 * **Layout not verified on a device** — Compose is not unit-testable in this repo; this has been compiled, not looked at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PwmCalibrationScreen(component: PwmCalibrationComponent) {
    val ui by component.state.collectAsState()
    val w = ui.wizard

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(Res.string.pwm_wiz_title))
                        ui.vehicleName?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = component::onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.vehicle_hub_back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            w.notice?.let { Notice(stringResource(noticeText(it))) }

            if (w.step != PwmWizardStep.RESULT) LiveReadout(w)

            when (w.step) {
                PwmWizardStep.INTRO -> {
                    Body(stringResource(Res.string.pwm_wiz_intro_lift))
                    Body(stringResource(if (w.hasAlarmSignal || w.lastSample == null) Res.string.pwm_wiz_intro_alarm else Res.string.pwm_wiz_intro_plateau))
                    Button(onClick = component::onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pwm_wiz_start)) }
                }
                PwmWizardStep.THRESHOLD -> {
                    val last = w.onsets.lastOrNull()
                    if (last == null || !w.runCaptured) {
                        Body(stringResource(Res.string.pwm_wiz_threshold_waiting))
                    }
                    last?.let {
                        Body(stringResource(Res.string.pwm_wiz_threshold_caught, one(it.speedKmh), one(it.packV), w.onsets.size))
                    }
                    if (w.canRunAgain) {
                        OutlinedButton(onClick = component::onRunAgain, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pwm_wiz_again)) }
                    }
                    OutlinedButton(onClick = component::onToPlateau, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pwm_wiz_to_plateau)) }
                    if (w.onsets.isNotEmpty()) {
                        Button(onClick = component::onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pwm_wiz_finish)) }
                    }
                }
                PwmWizardStep.PLATEAU -> {
                    val p = w.plateau
                    if (p == null) Body(stringResource(Res.string.pwm_wiz_plateau_waiting))
                    else Body(stringResource(Res.string.pwm_wiz_plateau_caught, one(p.kmh), one(p.packV)))
                    if (w.onsets.isNotEmpty() || p != null) {
                        Button(onClick = component::onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.pwm_wiz_finish)) }
                    }
                }
                PwmWizardStep.RESULT -> w.result?.let { r ->
                    SettingsGroup(stringResource(Res.string.pwm_wiz_result_title)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(Res.string.pwm_wiz_result_k, three(r.calibration.k)),
                                fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            )
                            r.onsetKmh?.let { Body(stringResource(Res.string.pwm_wiz_result_onset, one(it))) }
                            r.plateauKmh?.let { Body(stringResource(Res.string.pwm_wiz_result_plateau, one(it))) }
                            r.ratio?.let { Body(stringResource(Res.string.pwm_wiz_result_ratio, two(it))) }
                            if (r.ratioWarning) Notice(stringResource(Res.string.pwm_wiz_ratio_warn))
                        }
                    }
                    if (ui.saveFailed) Notice(stringResource(Res.string.pwm_wiz_save_failed))
                    Button(onClick = component::onSave, enabled = !ui.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pwm_wiz_save))
                    }
                    OutlinedButton(onClick = component::onRestart, enabled = !ui.saving, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(Res.string.pwm_wiz_restart))
                    }
                }
            }

            OutlinedButton(onClick = component::onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(Res.string.action_cancel)) }
        }
    }
}

private fun noticeText(n: PwmWizardNotice) = when (n) {
    PwmWizardNotice.DISCONNECTED -> Res.string.pwm_wiz_notice_disconnected
    PwmWizardNotice.NOT_CONNECTED -> Res.string.pwm_wiz_notice_not_connected
    PwmWizardNotice.NO_DATA -> Res.string.pwm_wiz_notice_no_data
    PwmWizardNotice.NOTHING_CAPTURED -> Res.string.pwm_wiz_notice_nothing
}

@Composable
private fun LiveReadout(w: PwmWizardState) {
    val s = w.lastSample
    val speed = s?.speedKmh?.takeIf { it.isFinite() }
    val volts = s?.packV
    Text(
        text = if (speed != null && volts != null) stringResource(Res.string.pwm_wiz_live, one(speed), one(volts))
        else stringResource(Res.string.pwm_wiz_live_none),
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun Notice(text: String) {
    Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
}

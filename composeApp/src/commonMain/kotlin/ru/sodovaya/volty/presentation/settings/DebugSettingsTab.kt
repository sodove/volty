package ru.sodovaya.volty.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import ru.sodovaya.volty.domain.model.BleDiagnosticRecord
import ru.sodovaya.volty.domain.model.BleDiagnosticLink
import ru.sodovaya.volty.domain.model.BleDiagnosticBatterySample
import ru.sodovaya.volty.domain.model.BleDiagnosticControllerSample
import ru.sodovaya.volty.domain.model.BmsData
import ru.sodovaya.volty.domain.model.ControllerData
import volty.composeapp.generated.resources.Res
import volty.composeapp.generated.resources.debug_capture_active
import volty.composeapp.generated.resources.debug_capture_stopped
import volty.composeapp.generated.resources.debug_start_capture
import volty.composeapp.generated.resources.debug_stop_capture
import volty.composeapp.generated.resources.debug_clear
import volty.composeapp.generated.resources.debug_copy
import volty.composeapp.generated.resources.debug_empty
import volty.composeapp.generated.resources.debug_links
import volty.composeapp.generated.resources.debug_battery
import volty.composeapp.generated.resources.debug_controller
import volty.composeapp.generated.resources.debug_records
import volty.composeapp.generated.resources.debug_unknown
import volty.composeapp.generated.resources.debug_identity
import volty.composeapp.generated.resources.debug_protocol
import volty.composeapp.generated.resources.debug_model
import volty.composeapp.generated.resources.debug_hardware
import volty.composeapp.generated.resources.debug_firmware
import volty.composeapp.generated.resources.debug_voltage
import volty.composeapp.generated.resources.debug_soc
import volty.composeapp.generated.resources.debug_current
import volty.composeapp.generated.resources.debug_power
import volty.composeapp.generated.resources.debug_speed
import volty.composeapp.generated.resources.debug_pwm
import volty.composeapp.generated.resources.debug_phase_current
import volty.composeapp.generated.resources.debug_record_time
import volty.composeapp.generated.resources.debug_device
import volty.composeapp.generated.resources.debug_size
import volty.composeapp.generated.resources.debug_payload
import volty.composeapp.generated.resources.debug_vehicle
import volty.composeapp.generated.resources.debug_cell_voltage
import volty.composeapp.generated.resources.debug_average_cell_voltage
import volty.composeapp.generated.resources.debug_known_flags
import volty.composeapp.generated.resources.debug_known
import volty.composeapp.generated.resources.debug_not_known
import volty.composeapp.generated.resources.debug_flag_soc
import volty.composeapp.generated.resources.debug_flag_current
import volty.composeapp.generated.resources.debug_flag_power
import volty.composeapp.generated.resources.debug_flag_input_voltage
import volty.composeapp.generated.resources.debug_flag_speed
import volty.composeapp.generated.resources.debug_flag_duty
import volty.composeapp.generated.resources.debug_flag_battery_current
import volty.composeapp.generated.resources.debug_flag_cell_voltage
import volty.composeapp.generated.resources.debug_flag_phase_current
import volty.composeapp.generated.resources.debug_flag_battery_voltage
import volty.composeapp.generated.resources.debug_record_count
import volty.composeapp.generated.resources.debug_local_index
import volty.composeapp.generated.resources.debug_global_index

@Composable
fun DebugSettingsTab(
    state: SettingsComponent.State,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SettingsCard {
            Text(
                stringResource(if (state.diagnostics.isCapturing) Res.string.debug_capture_active else Res.string.debug_capture_stopped),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.debug_record_count, state.diagnostics.records.size),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onStart, enabled = !state.diagnostics.isCapturing) { Text(stringResource(Res.string.debug_start_capture)) }
                TextButton(onClick = onStop, enabled = state.diagnostics.isCapturing) { Text(stringResource(Res.string.debug_stop_capture)) }
                TextButton(onClick = onClear) { Text(stringResource(Res.string.debug_clear)) }
                TextButton(onClick = { clipboard.setText(AnnotatedString(copyBundle(state))) }) { Text(stringResource(Res.string.debug_copy)) }
            }
        }
        SettingsCard {
            DebugHeading(stringResource(Res.string.debug_links))
            state.activeVehicle?.let { DebugValue(stringResource(Res.string.debug_vehicle), "${it.name} · ${it.id}") }
            if (state.diagnostics.activeLinks.isEmpty()) {
                Text(stringResource(Res.string.debug_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else state.diagnostics.activeLinks.forEach { link ->
                DebugValue(stringResource(Res.string.debug_device), listOfNotNull(link.deviceName, link.deviceAddress).joinToString(" · "))
                DebugValue(stringResource(Res.string.debug_protocol), link.protocol)
                link.controllerIdentity?.let { DebugValue(stringResource(Res.string.debug_identity), it) }
                link.model?.takeIf(String::isNotBlank)?.let { DebugValue(stringResource(Res.string.debug_model), it) }
                link.hardwareCode?.takeIf(String::isNotBlank)?.let { DebugValue(stringResource(Res.string.debug_hardware), it) }
                link.firmwareVersion?.takeIf(String::isNotBlank)?.let { DebugValue(stringResource(Res.string.debug_firmware), it) }
                link.batteries.forEach { battery ->
                    DebugHeading(stringResource(Res.string.debug_battery) + battery.packIdentity?.let { " · $it" }.orEmpty())
                    DebugValue(stringResource(Res.string.debug_local_index), battery.localPackIndex.toString())
                    DebugValue(stringResource(Res.string.debug_global_index), battery.globalPackIndex?.toString() ?: stringResource(Res.string.debug_not_known))
                    DebugBatteryValues(battery)
                }
                link.controllers.forEach { controller ->
                    DebugHeading(stringResource(Res.string.debug_controller) + controller.controllerIdentity?.let { " · $it" }.orEmpty())
                    DebugValue(stringResource(Res.string.debug_local_index), controller.localControllerIndex.toString())
                    DebugValue(stringResource(Res.string.debug_global_index), controller.globalControllerIndex?.toString() ?: stringResource(Res.string.debug_not_known))
                    DebugControllerValues(link, controller)
                }
                if (link.batteries.isEmpty() && link.controllers.isEmpty()) {
                    Text(stringResource(Res.string.debug_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        SettingsCard {
            DebugHeading(stringResource(Res.string.debug_records))
            val records = state.diagnostics.records.asReversed().take(200)
            if (records.isEmpty()) Text(stringResource(Res.string.debug_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
            records.forEach { record -> DebugRecord(record) }
        }
    }
}

@Composable
private fun DebugHeading(text: String) = Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)

@Composable
private fun DebugBatteryValues(sample: BleDiagnosticBatterySample) {
    val b = sample.data
    val voltageKnown = batteryVoltageKnown(b)
    val cellsKnown = b.cellVoltages.isNotEmpty()
    DebugValue(stringResource(Res.string.debug_voltage), measured(b.voltage, voltageKnown, "V"))
    DebugValue(stringResource(Res.string.debug_soc), measured(b.soc, b.isConnected && b.socKnown, "%"))
    DebugValue(stringResource(Res.string.debug_current), measured(b.current, b.isConnected && b.hasCurrent, "A"))
    DebugValue(stringResource(Res.string.debug_power), measured(b.power, b.isConnected && b.hasPower, "W"))
    DebugValue(stringResource(Res.string.debug_average_cell_voltage), if (cellsKnown) "${b.cellVoltages.average()} V" else "—")
    DebugValue(stringResource(Res.string.debug_cell_voltage), if (cellsKnown) b.cellVoltages.mapIndexed { index, value -> "${index + 1}: $value V" }.joinToString(" · ") else "—")
    DebugHeading(stringResource(Res.string.debug_known_flags))
    DebugFlag(stringResource(Res.string.debug_flag_battery_voltage), voltageKnown)
    DebugFlag(stringResource(Res.string.debug_flag_soc), b.isConnected && b.socKnown)
    DebugFlag(stringResource(Res.string.debug_flag_current), b.isConnected && b.hasCurrent)
    DebugFlag(stringResource(Res.string.debug_flag_power), b.isConnected && b.hasPower)
    DebugFlag(stringResource(Res.string.debug_flag_cell_voltage), cellsKnown)
}

@Composable
private fun DebugControllerValues(link: BleDiagnosticLink, sample: BleDiagnosticControllerSample) {
    val c = sample.data
    val phaseKnown = phaseCurrentKnown(link, sample)
    DebugValue(stringResource(Res.string.debug_voltage), measured(c.inputVoltageV, c.isConnected && c.hasInputVoltage, "V"))
    DebugValue(stringResource(Res.string.debug_speed), measured(c.speedKmh, c.isConnected && c.speedKnown, "km/h"))
    DebugValue(stringResource(Res.string.debug_pwm), measured(c.dutyPercent, c.isConnected && c.hasDuty, "%"))
    DebugValue(stringResource(Res.string.debug_current), measured(c.batteryCurrentA, c.isConnected && c.hasBatteryCurrent, "A"))
    DebugValue(stringResource(Res.string.debug_phase_current), measured(c.motorCurrentA, phaseKnown, "A"))
    DebugValue(stringResource(Res.string.debug_power), measured(c.powerW, c.isConnected && c.hasPower, "W"))
    DebugHeading(stringResource(Res.string.debug_known_flags))
    DebugFlag(stringResource(Res.string.debug_flag_input_voltage), c.isConnected && c.hasInputVoltage)
    DebugFlag(stringResource(Res.string.debug_flag_speed), c.isConnected && c.speedKnown)
    DebugFlag(stringResource(Res.string.debug_flag_duty), c.isConnected && c.hasDuty)
    DebugFlag(stringResource(Res.string.debug_flag_battery_current), c.isConnected && c.hasBatteryCurrent)
    DebugFlag(stringResource(Res.string.debug_flag_phase_current), phaseKnown)
    DebugFlag(stringResource(Res.string.debug_flag_power), c.isConnected && c.hasPower)
}

@Composable
private fun DebugFlag(label: String, known: Boolean) {
    DebugValue(label, stringResource(if (known) Res.string.debug_known else Res.string.debug_not_known))
}

@Composable
private fun DebugValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), fontSize = 12.sp)
    }
}

@Composable
private fun DebugRecord(record: BleDiagnosticRecord) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        DebugValue(stringResource(Res.string.debug_record_time), record.timestampEpochMillis.toString())
        DebugValue(stringResource(Res.string.debug_device), listOfNotNull(record.deviceName, record.deviceAddress).joinToString(" · "))
        DebugValue(stringResource(Res.string.debug_protocol), "${record.protocol} · ${record.notifyUuid}")
        DebugValue(stringResource(Res.string.debug_size), "${record.payloadLength} B")
        Text(stringResource(Res.string.debug_payload) + ": " + record.payloadHex, fontSize = 11.sp)
    }
}

private fun measured(value: Float, known: Boolean, unit: String): String =
    if (known) "$value $unit" else "—"

private fun batteryVoltageKnown(battery: BmsData): Boolean =
    battery.isConnected && battery.voltage.isFinite() && battery.voltage > 0f

private fun phaseCurrentKnown(link: BleDiagnosticLink, sample: BleDiagnosticControllerSample): Boolean =
    sample.data.isConnected && link.protocol.equals("VETERAN", ignoreCase = true) &&
        sample.controllerIdentity in setOf("VETERAN", "NOSFET")

private fun copyBundle(state: SettingsComponent.State): String = buildString {
    appendLine("BLE diagnostics")
    appendLine("capturing=${state.diagnostics.isCapturing}")
    appendLine("capturedRecords=${state.diagnostics.records.size}")
    state.activeVehicle?.let { appendLine("vehicle=${it.name} ${it.id}") }
    state.diagnostics.activeLinks.forEach { link ->
        appendLine("link=${link.deviceName.orEmpty()} ${link.deviceAddress} protocol=${link.protocol} identity=${link.controllerIdentity.orEmpty()} model=${link.model.orEmpty()} hardware=${link.hardwareCode.orEmpty()} firmware=${link.firmwareVersion.orEmpty()}")
        for (sample in link.batteries) {
            val b = sample.data
            val voltageKnown = batteryVoltageKnown(b)
            val cellsKnown = b.cellVoltages.isNotEmpty()
            appendLine("  battery localIndex=${sample.localPackIndex} globalIndex=${sample.globalPackIndex ?: "unknown"} identity=${sample.packIdentity.orEmpty()}")
            appendLine("    voltage=${known(b.voltage, voltageKnown)} soc=${known(b.soc, b.isConnected && b.socKnown)} current=${known(b.current, b.isConnected && b.hasCurrent)} power=${known(b.power, b.isConnected && b.hasPower)}")
            appendLine("    cellVoltageKnown=$cellsKnown averageCellVoltage=${if (cellsKnown) b.cellVoltages.average() else "unknown"} cells=${if (cellsKnown) b.cellVoltages.joinToString(",") else "unknown"}")
            appendLine("    knownFlags voltage=$voltageKnown soc=${b.isConnected && b.socKnown} current=${b.isConnected && b.hasCurrent} power=${b.isConnected && b.hasPower} cellVoltage=$cellsKnown")
        }
        for (sample in link.controllers) {
            val c = sample.data
            val phaseKnown = phaseCurrentKnown(link, sample)
            appendLine("  controller localIndex=${sample.localControllerIndex} globalIndex=${sample.globalControllerIndex ?: "unknown"} identity=${sample.controllerIdentity.orEmpty()}")
            appendLine("    voltage=${known(c.inputVoltageV, c.isConnected && c.hasInputVoltage)} speed=${known(c.speedKmh, c.isConnected && c.speedKnown)} pwm=${known(c.dutyPercent, c.isConnected && c.hasDuty)} batteryCurrent=${known(c.batteryCurrentA, c.isConnected && c.hasBatteryCurrent)} phaseCurrent=${known(c.motorCurrentA, phaseKnown)} power=${known(c.powerW, c.isConnected && c.hasPower)}")
            appendLine("    knownFlags inputVoltage=${c.isConnected && c.hasInputVoltage} speed=${c.isConnected && c.speedKnown} duty=${c.isConnected && c.hasDuty} batteryCurrent=${c.isConnected && c.hasBatteryCurrent} phaseCurrent=$phaseKnown power=${c.isConnected && c.hasPower}")
        }
    }
    appendLine("records newest first")
    var used = length
    for (record in state.diagnostics.records.asReversed().take(200)) {
        val row = "${record.timestampEpochMillis} ${record.deviceName.orEmpty()} ${record.deviceAddress} ${record.protocol} ${record.notifyUuid} ${record.payloadLength} ${record.payloadHex}\n"
        if (used + row.length > MAX_COPY_CHARS) break
        append(row)
        used += row.length
    }
}.take(MAX_COPY_CHARS)

private fun known(value: Float, known: Boolean): String = if (known) value.toString() else "unknown"
private const val MAX_COPY_CHARS = 256 * 1024

package com.volty.app.presentation.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.volty.app.domain.model.ConnectionState
import com.volty.app.domain.repository.DiscoveredDevice
import kotlin.math.abs
import kotlin.math.round

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(component: DebugComponent) {
    val state by component.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Volty debug") }) }
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(12.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Connection: ${state.connection::class.simpleName}",
                style = MaterialTheme.typography.labelLarge
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = component::onScanClicked) {
                    Text(if (state.isScanning) "Stop scan" else "Scan BMS")
                }
                if (state.connection is ConnectionState.Connected) {
                    OutlinedButton(onClick = component::onDisconnect) { Text("Disconnect") }
                }
            }

            HorizontalDivider()

            Text("Discovered (${state.devices.size})", style = MaterialTheme.typography.labelMedium)
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(state.devices, key = { it.address }) { d ->
                    DeviceRow(d, onClick = { component.onConnect(d) })
                }
            }

            HorizontalDivider()

            if (state.connection is ConnectionState.Connected) {
                Text("Live data", style = MaterialTheme.typography.labelMedium)
                val d = state.data
                Text(
                    "SOC: ${fmt0(d.soc)}%  " +
                        "V: ${fmt2(d.voltage)}  " +
                        "A: ${fmtSigned2(d.current)}  " +
                        "W: ${fmtSigned1(d.power)}"
                )
                Text("Charge: ${fmt2(d.charge)} / ${fmt2(d.capacity)} Ah   Cycles: ${d.numCycles}")
                Text("Cells (${d.cellVoltages.size}): " + d.cellVoltages.joinToString(", ") { fmt3(it) })
                Text("Temps: " + d.temperatures.joinToString(", ") { "${fmt1(it)}°C" })
            }
        }
    }
}

@Composable
private fun DeviceRow(d: DiscoveredDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(d.name ?: d.address) },
        supportingContent = { Text("${d.bmsType.label}  ·  ${d.rssi} dBm") },
        trailingContent = { TextButton(onClick = onClick) { Text("Connect") } }
    )
}

private fun fmt0(v: Float): String = round(v).toInt().toString()

private fun fmt1(v: Float): String = roundTo(v, 1)

private fun fmt2(v: Float): String = roundTo(v, 2)

private fun fmt3(v: Float): String = roundTo(v, 3)

private fun fmtSigned1(v: Float): String = (if (v >= 0f) "+" else "") + fmt1(v)

private fun fmtSigned2(v: Float): String = (if (v >= 0f) "+" else "") + fmt2(v)

private fun roundTo(value: Float, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = round(value.toDouble() * factor) / factor
    val negative = rounded < 0
    val abs = abs(rounded)
    val intPart = abs.toLong()
    val fracScaled = round((abs - intPart) * factor).toLong()
    val sign = if (negative) "-" else ""
    if (decimals == 0) return "$sign$intPart"
    val fracStr = fracScaled.toString().padStart(decimals, '0')
    return "$sign$intPart.$fracStr"
}

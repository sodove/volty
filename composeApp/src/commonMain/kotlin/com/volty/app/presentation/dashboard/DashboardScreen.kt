package com.volty.app.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.volty.app.presentation.common.MetricCard
import com.volty.app.presentation.common.MetricCardVariant
import com.volty.app.presentation.common.PowerRangeBar
import com.volty.app.presentation.common.SparklineGraph
import com.volty.app.presentation.common.VehiclePill
import kotlin.math.abs
import kotlin.math.round

@Composable
fun DashboardScreen(component: DashboardComponent) {
    val state by component.state.collectAsState()
    val data = state.data
    val vehicle = state.vehicle

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VehiclePill(
            name = vehicle?.name ?: "No battery",
            statusText = if (data.isConnected) "● Connected · ${vehicle?.bmsType?.label ?: "—"}" else "● Disconnected",
            onClick = component::onPillClicked
        )

        HeroCard(state)

        // 2-col metric grid
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                label = "Voltage",
                value = "${fmt2(data.voltage)} V",
                modifier = Modifier.weight(1f),
                sub = if (vehicle?.cellCount != null && data.cellVoltages.isNotEmpty()) {
                    "${data.cellVoltages.size}s · ${fmt2(data.voltage / data.cellVoltages.size)} V/cell"
                } else null
            )
            MetricCard(
                label = "Power",
                value = "${fmt0(data.power)} W",
                modifier = Modifier.weight(1f),
                variant = MetricCardVariant.Tertiary,
                extra = {
                    Column {
                        PowerRangeBar(
                            min = state.powerMin, peak = state.powerPeak, now = data.power,
                            modifier = Modifier.fillMaxWidth().height(12.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${state.powerMin.toInt()} W", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                            Text("peak ${state.powerPeak.toInt()} W", fontSize = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }

        // Wide sparkline
        MetricCard(
            label = "Power · last 5 min",
            value = "",
            modifier = Modifier.fillMaxWidth().height(60.dp),
            extra = {
                SparklineGraph(values = state.sparkline, modifier = Modifier.fillMaxWidth().height(34.dp))
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            MetricCard(
                label = "Temperature",
                value = if (data.temperatures.isEmpty()) "—" else "${fmt0(data.temperatures.first())}° C",
                modifier = Modifier.weight(1f),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        data.temperatures.forEachIndexed { i, t ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("T${i + 1} ${fmt0(t)}°", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            )
            MetricCard(
                label = "Cells · Δ ${state.cellsDeltaMv} mV",
                value = if (data.cellVoltages.isEmpty()) "—" else "${fmt2(data.cellVoltages.average().toFloat())} V avg",
                modifier = Modifier.weight(1f),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(18.dp)) {
                        val cells = data.cellVoltages
                        val min = state.cellsMinV
                        val max = state.cellsMaxV
                        val range = (max - min).takeIf { it > 0f } ?: 1f
                        cells.forEach { v ->
                            val frac = ((v - min) / range).coerceIn(0f, 1f)
                            val barH = (4 + (frac * 14)).dp
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(barH)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.Bottom)
                            )
                        }
                    }
                }
            )
        }

        Spacer(Modifier.weight(1f))

        // Bottom tab group
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TabButton("Live", true) { component.onTabClicked(DashboardComponent.Tab.Live) }
            TabButton("Cells", false) { component.onTabClicked(DashboardComponent.Tab.Cells) }
            TabButton("Graph", false) { component.onTabClicked(DashboardComponent.Tab.Graph) }
            TabButton("⚙", false) { component.onTabClicked(DashboardComponent.Tab.Settings) }
        }
    }

    if (state.sheetOpen) {
        VehicleSheet(
            saved = state.savedVehicles,
            activeId = state.vehicle?.id,
            onSwitch = component::onSwitchVehicle,
            onAdd = component::onAddBattery,
            onDisconnect = component::onDisconnect,
            onDismiss = component::onSheetDismiss
        )
    }
}

@Composable
private fun RowScope.TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(
                RoundedCornerShape(
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp,
                    if (active) 16.dp else 20.dp,
                    if (active) 24.dp else 20.dp
                )
            )
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun HeroCard(state: DashboardComponent.State) {
    val data = state.data
    val v = state.vehicle
    val isCharging = state.isCharging
    val containerColor = if (isCharging) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
    val onColor = if (isCharging) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer
    val barColor = if (isCharging) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val nominalV = v?.let { (v.cellCount ?: 1) * v.chemistry.nominalCellV } ?: data.voltage.coerceAtLeast(1f)
    val eta = timeToEmptyDescription(data.charge, state.avgPowerW, nominalV)
    val socFraction = (data.soc / 100f).coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp, 36.dp, 28.dp, 36.dp))
            .background(containerColor)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = if (isCharging) "CHARGING TO FULL" else "STATE OF CHARGE",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = onColor.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt0(data.soc), fontSize = 80.sp, fontWeight = FontWeight.Medium, color = barColor)
                Text("%", fontSize = 24.sp, color = onColor.copy(alpha = 0.65f))
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(fmt1(data.charge), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text(" / ${fmt1(data.capacity)}", fontSize = 12.sp, color = onColor.copy(alpha = 0.65f))
                Text("AH REMAINING", fontSize = 10.sp, color = onColor.copy(alpha = 0.7f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(onColor.copy(alpha = 0.1f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(socFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("≈ $eta", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text("to empty · avg ${state.avgPowerW.toInt()} W", fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${fmtSigned1(data.current)} A", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text("now", fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
            }
        }
    }
}

// --- KMP-safe number formatters (no JVM String.format) ---

private fun fmt0(v: Float): String = round(v).toInt().toString()
private fun fmt1(v: Float): String = roundTo(v, 1)
private fun fmt2(v: Float): String = roundTo(v, 2)

private fun fmtSigned1(v: Float): String = (if (v >= 0f) "+" else "") + fmt1(v)

private fun roundTo(value: Float, decimals: Int): String {
    var factor = 1.0
    repeat(decimals) { factor *= 10.0 }
    val rounded = round(value.toDouble() * factor) / factor
    val negative = rounded < 0
    val absVal = abs(rounded)
    val intPart = absVal.toLong()
    val fracScaled = round((absVal - intPart) * factor).toLong()
    val sign = if (negative) "-" else ""
    if (decimals == 0) return "$sign$intPart"
    val fracStr = fracScaled.toString().padStart(decimals, '0')
    return "$sign$intPart.$fracStr"
}

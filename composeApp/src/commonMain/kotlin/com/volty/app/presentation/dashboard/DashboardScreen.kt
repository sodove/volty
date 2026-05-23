package com.volty.app.presentation.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.volty.app.domain.model.ConnectionState
import com.volty.app.presentation.common.MetricCard
import com.volty.app.presentation.common.PowerRangeBar
import com.volty.app.presentation.common.SparklineGraph
import com.volty.app.presentation.common.VehiclePill
import kotlin.math.abs
import kotlin.math.round

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(component: DashboardComponent) {
    val state by component.state.collectAsState()
    val data = state.data
    val vehicle = state.vehicle

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val (statusLabel, statusColor) = when (val c = state.connection) {
            is ConnectionState.Connected -> "● Connected · ${vehicle?.bmsType?.label ?: "—"}" to MaterialTheme.colorScheme.tertiary
            is ConnectionState.Connecting -> "● Connecting…" to MaterialTheme.colorScheme.secondary
            is ConnectionState.Failed -> "● ${c.reason}" to MaterialTheme.colorScheme.error
            ConnectionState.Disconnected -> "● Disconnected" to MaterialTheme.colorScheme.outline
            ConnectionState.Scanning -> "● Scanning…" to MaterialTheme.colorScheme.secondary
            ConnectionState.Idle -> "● Idle" to MaterialTheme.colorScheme.outline
        }
        VehiclePill(
            name = vehicle?.name ?: "No battery",
            statusText = statusLabel,
            statusColor = statusColor,
            onClick = component::onPillClicked
        )

        HeroCard(state)

        // 2-col metric grid
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            MetricCard(
                label = "Voltage",
                value = "${fmt2(data.voltage)} V",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                sub = if (vehicle?.cellCount != null && data.cellVoltages.isNotEmpty()) {
                    "${data.cellVoltages.size}s · ${fmt2(data.voltage / data.cellVoltages.size)} V/cell"
                } else null
            )
            val powerCharging = data.current > 0.05f
            // Fixed dark-green palette matches the hero card while charging so the
            // two cards visually agree regardless of dynamic-color wallpaper.
            val powerChargingContainer = Color(0xFF184D24)
            val powerChargingOn = Color(0xFFE8F5EA)
            val powerSubColor = if (powerCharging) powerChargingOn.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSurfaceVariant
            MetricCard(
                label = "Power",
                value = "${fmt0(data.power)} W",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                containerColor = if (powerCharging) powerChargingContainer else null,
                onColor = if (powerCharging) powerChargingOn else null,
                extra = {
                    Column {
                        PowerRangeBar(
                            min = state.powerMin, peak = state.powerPeak, now = data.power,
                            modifier = Modifier.fillMaxWidth().height(12.dp),
                            marker = if (powerCharging) powerChargingOn else Color.White
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${state.powerMin.toInt()} W", fontSize = 10.sp, color = powerSubColor)
                            Text("peak ${state.powerPeak.toInt()} W", fontSize = 10.sp, color = powerSubColor)
                        }
                    }
                }
            )
        }

        // Wide sparkline
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(12.dp)
        ) {
            Column {
                Text(
                    "POWER · LAST 5 MIN",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                )
                Spacer(Modifier.height(6.dp))
                SparklineGraph(
                    values = state.sparkline,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    minRange = 100f
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        ) {
            MetricCard(
                label = "Temperature",
                value = if (data.temperatures.isEmpty()) "—" else "${fmt0(data.temperatures.first())}° C",
                modifier = Modifier.weight(1f).fillMaxHeight(),
                extra = {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                modifier = Modifier.weight(1f).fillMaxHeight(),
                extra = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(18.dp)) {
                        val cells = data.cellVoltages
                        val min = state.cellsMinV
                        val max = state.cellsMaxV
                        val range = (max - min).takeIf { it > 0f } ?: 1f
                        cells.forEach { v ->
                            val rawFrac = ((v - min) / range).coerceIn(0f, 1f)
                            // animateFloatAsState is call-site-keyed, so each per-cell
                            // call in this forEach gets its own animation state.
                            val animFrac by animateFloatAsState(
                                targetValue = rawFrac,
                                animationSpec = tween(durationMillis = 400),
                                label = "cellMini"
                            )
                            val barH = (4 + (animFrac * 14)).dp
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
private fun HeroCard(state: DashboardComponent.State) {
    val data = state.data
    val v = state.vehicle
    // Direction from short window (30 s) — switches fast on real flips, ignores
    // brief regen blips during a long discharge. We keep the per-vehicle long
    // window (state.avgPowerW) for the ETA magnitude below.
    val dirAvg = state.recentAvgPowerW
    val isCharging = when {
        dirAvg > 1f -> true
        dirAvg < -1f -> false
        else -> data.power > 0.05f  // fallback when truly balanced
    }
    // Fixed darker-green palette for the charging hero so dynamic-color wallpapers
    // can't wash the card out. Other UI keeps the dynamic palette.
    val chargingContainer = Color(0xFF184D24) // dark forest green
    val chargingOn = Color(0xFFE8F5EA)        // near-white on-container
    val chargingAccent = Color(0xFF7FCB8A)    // mid green for bar + SOC %
    val containerColor = if (isCharging) chargingContainer else MaterialTheme.colorScheme.primaryContainer
    val onColor = if (isCharging) chargingOn else MaterialTheme.colorScheme.onPrimaryContainer
    val barColor = if (isCharging) chargingAccent else MaterialTheme.colorScheme.primary
    val nominalV = v?.cellCount?.let { count -> count * v.chemistry.nominalCellV }
        ?: data.voltage.coerceAtLeast(1f)
    val eta = timeRemainingDescription(
        isCharging = isCharging,
        remainingAh = data.charge,
        capacityAh = data.capacity,
        avgPowerW = state.avgPowerW,
        nominalV = nominalV
    )
    val etaLabel = if (isCharging) "to full" else "to empty"
    val socFraction = (data.soc / 100f).coerceIn(0f, 1f)
    val animatedSoc by animateFloatAsState(
        targetValue = data.soc,
        animationSpec = tween(durationMillis = 600),
        label = "soc"
    )
    val animatedSocFraction by animateFloatAsState(
        targetValue = socFraction,
        animationSpec = tween(durationMillis = 400),
        label = "socFraction"
    )
    val animatedContainer by animateColorAsState(
        targetValue = containerColor,
        animationSpec = tween(durationMillis = 400),
        label = "heroBg"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp, 36.dp, 28.dp, 36.dp))
            .background(animatedContainer)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .animateContentSize()
    ) {
        Text(
            text = if (isCharging) "CHARGING TO FULL" else "STATE OF CHARGE",
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = onColor.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmt0(animatedSoc), fontSize = 80.sp, fontWeight = FontWeight.Medium, color = onColor)
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
                    .fillMaxWidth(animatedSocFraction)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("≈ $eta", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = onColor)
                Text("$etaLabel · avg ${abs(state.avgPowerW).toInt()} W", fontSize = 10.sp, color = onColor.copy(alpha = 0.55f))
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
